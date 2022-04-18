package com.sumologic.sumopush.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.kafka.ConsumerMessage.CommittableOffset
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.google.re2j.Pattern
import com.jayway.jsonpath.JsonPath
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.MessageProcessor._
import com.sumologic.sumopush.model.SumoDataFormat.Format
import com.sumologic.sumopush.model._
import io.prometheus.client.Counter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.json4s.JsonAST.{JArray, JObject}
import org.json4s.native.JsonMethods.{compact, render}
import org.json4s.native.{JsonMethods, Serialization}
import org.json4s.{DefaultFormats, Formats, JNothing, JValue, JsonAST}
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success, Try}

object LogProcessor extends MessageProcessor {

  case class EndpointFilter(name: String, container: String, regex: Option[String], pattern: Option[Pattern])

  private val defaultJsonKey = "log"

  final val messages_failed = Counter.build()
    .name("messages_failed")
    .help("Total messages failed processing")
    .labelNames("exception").register()

  lazy val patterns: LoadingCache[EndpointFilter, Option[Pattern]] = CacheBuilder.newBuilder()
    .expireAfterAccess(1L, TimeUnit.HOURS)
    .build(new CacheLoader[EndpointFilter, Option[Pattern]] {
      lazy val log: Logger = LoggerFactory.getLogger(getClass)

      override def load(ep: EndpointFilter): Option[Pattern] = {
        ep.regex.map(r => Try(Pattern.compile(r))).flatMap {
          case Success(pattern) => Some(pattern)
          case Failure(e) =>
            log.error(s"invalid regex for label($EndpointFilterAnnotationPrefix/${ep.container}/${ep.name}", e)
            None
        }
      }
    })


  sealed trait LogMessage[K] {
    val record: ConsumerRecord[K, Try[LogEvent[Any]]]
  }

  case class ConsumerLogMessage[K](record: ConsumerRecord[K, Try[LogEvent[Any]]], offset: CommittableOffset, replyTo: ActorRef[(Option[Seq[SumoRequest]], CommittableOffset)]) extends LogMessage[K]

  def apply(config: AppConfig, stats: MessageProcessor.Stats): Behavior[LogMessage[_]] = Behaviors.setup { context =>
    Behaviors.receiveMessage[LogMessage[_]] {
      case ConsumerLogMessage(record, offset, replyTo) =>
        context.system.log.trace("log key: {}", record.key())
        val reply =  try {
          record.value() match {
            case Success(_@JsonLogEvent(JNothing)) =>
              context.log.warn("ignoring empty message")
              (None, offset)
            case null =>
              context.log.warn("ignoring null message")
              (None, offset)
            case Success(log@JsonLogEvent(_)) =>
              val requests = createSumoRequestsFromLogEvent(config, record.topic(), log, context.log)
              if (requests.isEmpty) (None, offset)
              else {
                requests.foreach(request => stats.messagesProcessed.labels(request.key.value, request.endpointName).inc())
                (Some(requests), offset)
              }
            case Success(log@KubernetesLogEvent(_, _, _, metadata)) =>
              val req = if (findContainerExclusion(log)) {
                stats.messagesIgnored.labels(metadata.container).inc()
                None
              } else {
                val requests = createSumoRequestsFromLogEvent(config, log)
                requests.foreach(request => stats.messagesProcessed.labels(log.metadata.container, request.endpointName).inc())
                Some(requests)
              }
              (req, offset)
            case Failure(e) =>
              val exName = e.toString.split(":")(0)
              messages_failed.labels(exName).inc()
              context.log.error("unable to parse log message {}", e.getMessage, e)
              (None, offset)
            case ev => throw new UnsupportedOperationException(s"unknown LogEvent type: $ev")
          }
        } catch {
          case e: Exception =>
            context.log.error("Error in Log Processor {}, skipping message", e.getMessage, e)
            (None, offset)
        }
        replyTo ! reply
        Behaviors.same
    }.receiveSignal {
      case (context, Terminated(ref)) =>
        context.log.info("Actor stopped: {}", ref.path.name)
        Behaviors.stopped {
          () => context.log.info("Processor stopped")
        }
    }
  }

  def createSumoRequestsFromLogEvent(config: AppConfig, topic: String, logEvent: JsonLogEvent, logger: Logger): Seq[SumoRequest] = {
    val endpoint = config.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.map(_._2).get
    val jsonOptions = endpoint.jsonOptions.map(opts => (opts.fieldJsonPaths, opts.payloadJsonPath, opts.payloadText.getOrElse(false)))
      .getOrElse((None, None, false))

    val fallback = endpoint.missingSrcCatStrategy match {
      case MissingSrcCatStrategy.FallbackToTopic => Some(topic)
      case MissingSrcCatStrategy.Drop => None
    }
    val sourceCategory = findSourceNameOrCategory(endpoint.sourceCategory,
      endpoint.jsonOptions.flatMap(opts => opts.sourceCategoryJsonPath), fallback, logEvent)
    val sourceName = findSourceNameOrCategory(endpoint.sourceName,
      endpoint.jsonOptions.flatMap(opts => opts.sourceNameJsonPath), fallback, logEvent)

    val format = if (jsonOptions._3) SumoDataFormat.text else SumoDataFormat.json
    val wrapperKey = endpoint.jsonOptions.flatMap(_.payloadWrapperKey)

    (sourceCategory, sourceName) match {
      case (Some(cat), Some(name)) =>
        (logEvent.message match {
          case ja: JArray =>
            ja.arr.map(jv => findPayloadAndFields(jv, jsonOptions, wrapperKey, logger))
          case jv: JValue =>
            List(findPayloadAndFields(jv, jsonOptions, wrapperKey, logger))
        }).map {
          case (payload, fields) =>
            val request = payload match {
              case v: String => LogRequest(Instant.now().toEpochMilli, v)
              case map: Map[String, Any] => RawJsonRequest((Map("timestamp" -> Instant.now().toEpochMilli).toSeq ++ map.toSeq).toMap)
            }
            SumoRequest(
              key = DefaultLogKey(cat),
              dataType = SumoDataType.logs,
              format = format,
              endpointName = endpoint.name.getOrElse("default"),
              sourceName = name,
              sourceCategory = cat,
              sourceHost = config.host,
              fields = fields,
              endpoint = endpoint.uri,
              logs = Seq(request))
        }
      case _ =>
        logger.warn(s"Unable to determine source category or name in the message: ${JsonMethods.compact(JsonMethods.render(logEvent.message))}")
        Nil
    }
  }

  def findPayloadAndFields(json: JValue, jsonOptions: (Option[Map[String, JsonPath]], Option[JsonPath], Boolean), key: Option[String], logger: Logger): (Any, Seq[HeaderField]) = {
    implicit val formats: Formats = DefaultFormats
    val payload = (jsonOptions match {
      case (_, Some(jp), text) =>
        val value = jp.read(json).asInstanceOf[Any] match {
          case s: String => s
          case m: Map[_, _] => m.asInstanceOf[Map[String, Any]]
          case ja: JArray =>
            if (ja.arr.isEmpty) {
              logger.warn(s"Unable to extract payload from empty array field: ${compact(render(json))}")
              json.extract[Map[String, Any]]
            } else {
              ja.arr.head match {
                case _: JObject => ja.extract[List[Map[String, Any]]]
                case _ => ja.extract[List[Any]]
              }
            }
          case jo: JObject => jo.extract[Map[String, Any]]
          case _ => json.extract[Map[String, Any]]
        }
        (text, value)
      case _ => (false, json.extract[Map[String, Any]])
    }) match {
      case (true, v) => v match {
        case s: String => s
        case v => Serialization.write(v)
      }
      case (false, m: Map[_, _]) => key match {
        case Some(wk) => Map(wk -> m)
        case None => m.asInstanceOf[Map[String, Any]]
      }
      case (false, v) => Map(key.getOrElse(defaultJsonKey) -> v)
    }

    val fields: Seq[HeaderField] = jsonOptions match {
      case (Some(m), _, _) => m.map {
        case (key, path) =>
          val value = path.read(json).asInstanceOf[Any] match {
            case s: String => s
            case b: JsonAST.JBool => b.value.toString
            case v: BigInt => v.toString()
            case v: Int => v.toString
            case v: Long => v.toString
            case v => v
          }
          (key, value)
      }.collect { case (k: String, v: String) => HeaderField(k, v) }.toSeq
      case _ => Seq.empty
    }
    (payload, fields)
  }

  def findSourceNameOrCategory(fixed: Option[String], jsonPath: Option[JsonPath], fallback: Option[String], logEvent: JsonLogEvent): Option[String] = {
    val jsonOpts = (fixed, jsonPath)
    jsonOpts match {
      case (_, Some(jp)) =>
        jp.read(logEvent.message).asInstanceOf[Any] match {
          case null | "" => fallback
          case v: String => Some(v)
          case _ => fallback
        }
      case (Some(fixed), _) => Some(fixed)
      case _ => fallback
    }
  }

  def createSumoRequestsFromLogEvent(config: AppConfig, logEvent: KubernetesLogEvent): Seq[SumoRequest] = {
    val endpointName = findEndpointName(config, logEvent)
    val endpoint = config.sumoEndpoints.find { case (name, _) => name == endpointName }.map(_._2).get
    val endpointFilters = findEndpointFilters(config, logEvent)
    val request = SumoRequest(key = KubernetesLogKey(podName = logEvent.metadata.name, containerName = logEvent.metadata.container, value = endpointName),
      dataType = SumoDataType.logs,
      format = findEndpointFormat(logEvent),
      endpointName = endpointName,
      sourceName = findSourceName(logEvent, endpoint),
      sourceCategory = findSourceCategory(logEvent, endpoint),
      sourceHost = "",
      fields = Seq(HeaderField("container", logEvent.metadata.container),
        HeaderField("node", logEvent.metadata.nodeName),
        HeaderField("pod", logEvent.metadata.name),
        HeaderField("namespace", logEvent.metadata.namespace)),
      endpoint = config.sumoEndpoints(endpointName).uri,
      logs = Seq(LogRequest(logEvent.timestamp.getOrElse(Instant.now().toEpochMilli), logEvent.message)))

    request +: endpointFilters.map { ep =>
      request.copy(key = request.key.asInstanceOf[KubernetesLogKey].copy(value = ep.name), endpointName = ep.name,
        logs = request.logs.filter { case log: LogRequest => ep.pattern.forall(_.matches(log.log)) })
    }.filterNot(_.logs.isEmpty)
  }

  private def findEndpointFilters(config: AppConfig, logEvent: KubernetesLogEvent): Seq[EndpointFilter] = {
    val annotationPrefix = s"$EndpointFilterAnnotationPrefix.${logEvent.metadata.container}."
    logEvent.metadata.labels.withFilter { case (k, _) => k.startsWith(annotationPrefix) && k.length > annotationPrefix.length && config.sumoEndpoints.contains(k.substring(annotationPrefix.length)) }
      .map { case (k, v) => EndpointFilter(name = k.substring(annotationPrefix.length), container = logEvent.metadata.container, regex = Some(v), None) }
      .map { ep => ep.copy(pattern = patterns.get(ep)) }
      .toSeq
  }

  private def findSourceName(logEvent: KubernetesLogEvent, endpoint: SumoEndpoint): String = {
    findPodMetadataValue(SourceNameAnnotationPrefix, endpoint.sourceName.getOrElse(s"${logEvent.metadata.name}.${logEvent.metadata.container}"),
      logEvent.metadata.annotations, logEvent.metadata.container)
  }

  private def findSourceCategory(logEvent: KubernetesLogEvent, endpoint: SumoEndpoint): String = {
    findPodMetadataValue(CategoryAnnotationPrefix, endpoint.sourceCategory.getOrElse(s"kubernetes/${logEvent.metadata.namespace.replace('-', '/')}"),
      logEvent.metadata.annotations, logEvent.metadata.container)
  }

  def findEndpointName(config: AppConfig, logEvent: KubernetesLogEvent): String = {
    val defaultEp = config.sumoEndpoints.find{ case(_, endpoint) =>
      endpoint.namespaces.getOrElse(List.empty[String]).contains(logEvent.metadata.namespace)} match {
      case Some(ep) => ep._1
      case None => config.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.get._1
    }
    findPodMetadataValue(EndpointAnnotationPrefix, defaultEp, logEvent.metadata.annotations,
      logEvent.metadata.container, Some(ep => config.sumoEndpoints.contains(ep)))
  }

  private def findEndpointFormat(logEvent: KubernetesLogEvent): Format = {
    findPodMetadataValue(FormatAnnotationPrefix, SumoDataFormat.json, logEvent.metadata.annotations, logEvent.metadata.container,
      Some(format => SumoDataFormat.withNameOpt(format).isDefined), Some(v => SumoDataFormat.withNameOpt(v).get))
  }

  private def findContainerExclusion(logEvent: KubernetesLogEvent): Boolean = {
    findPodMetadataValue(ExcludeAnnotation, false, logEvent.metadata.annotations, logEvent.metadata.container,
      None, Some(exclusion => exclusion.toBoolean))
  }
}