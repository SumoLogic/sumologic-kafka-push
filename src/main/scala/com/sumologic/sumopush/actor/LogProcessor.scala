package com.sumologic.sumopush.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.kafka.ConsumerMessage.CommittableOffset
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.google.re2j.Pattern
import com.jayway.jsonpath.JsonPath
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.model.SumoDataFormat.Format
import com.sumologic.sumopush.model._
import io.prometheus.client.Counter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.json4s.JsonAST.JObject
import org.json4s.{DefaultFormats, Formats}
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success, Try}

object LogProcessor {

  case class EndpointFilter(name: String, container: String, regex: Option[String], pattern: Option[Pattern])

  final val messages_processed = Counter.build()
    .name("messages_processed")
    .help("Total messages processed")
    .labelNames("container", "sumo_endpoint").register()
  final val messages_ignored = Counter.build()
    .name("messages_ignored")
    .help("Total messages ignored")
    .labelNames("container").register()
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

  val MetadataKeyPrefix = "sumologic.com"
  val ExcludeAnnotation = s"$MetadataKeyPrefix/exclude"
  val CategoryAnnotationPrefix = s"$MetadataKeyPrefix/category"
  val EndpointAnnotationPrefix = s"$MetadataKeyPrefix/ep"
  val FormatAnnotationPrefix = s"$MetadataKeyPrefix/format"
  val EndpointFilterAnnotationPrefix = s"$MetadataKeyPrefix/filter"
  val SourceNameAnnotationPrefix = s"$MetadataKeyPrefix/source.name"

  sealed trait LogMessage[K] {
    val record: ConsumerRecord[K, Try[LogEvent[Any]]]
  }

  case class ConsumerLogMessage[K](record: ConsumerRecord[K, Try[LogEvent[Any]]], offset: CommittableOffset, replyTo: ActorRef[(Option[Seq[SumoRequest]], CommittableOffset)]) extends LogMessage[K]

  def apply(config: AppConfig): Behavior[LogMessage[_]] = Behaviors.setup { context =>
    Behaviors.receiveMessage[LogMessage[_]] {
      case ConsumerLogMessage(record, offset, replyTo) =>
        context.system.log.trace("log key: {}", record.key())
        val reply = record.value() match {
          case Success(log@JsonLogEvent(_)) =>
            val requests = createSumoRequestsFromLogEvent(config, record.topic(), log)
            requests.foreach(request => messages_processed.labels(request.key.value, request.endpointName).inc())
            (Some(requests), offset)
          case Success(log@KubernetesLogEvent(_, _, _, metadata)) =>
            val req = if (ignoreLog(config, log)) {
              messages_ignored.labels(metadata.container).inc()
              None
            } else {
              val requests = createSumoRequestsFromLogEvent(config, log)
              requests.foreach(request => messages_processed.labels(log.metadata.container, request.endpointName).inc())
              Some(requests)
            }
            (req, offset)
          case Failure(e) =>
            val exName = e.toString.split(":")(0)
            messages_failed.labels(exName).inc()
            context.log.error("unable to parse log message {}", e.getMessage, e)
            (None, offset)
          case _ => throw new UnsupportedOperationException("unknown LogEvent type")
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

  def createSumoRequestsFromLogEvent(config: AppConfig, topic: String, logEvent: JsonLogEvent): Seq[SumoRequest] = {
    implicit val formats: Formats = DefaultFormats
    val endpoint = config.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.map(_._2).get
    val jsonOptions = endpoint.jsonOptions.map(opts => (opts.fieldJsonPaths, opts.payloadJsonPath))
      .getOrElse((None, None))

    val sourceCategory = findSourceNameOrCategory(endpoint.jsonOptions.flatMap(opts => opts.sourceCategoryFixed),
                           endpoint.jsonOptions.flatMap(opts => opts.sourceCategoryJsonPath), topic, logEvent)
    val sourceName = findSourceNameOrCategory(endpoint.jsonOptions.flatMap(opts => opts.sourceNameFixed),
                       endpoint.jsonOptions.flatMap(opts => opts.sourceNameJsonPath), topic, logEvent)

    val wrapperKey = endpoint.jsonOptions.flatMap(_.payloadWrapperKey)

    val payload = (jsonOptions match {
      case (_, Some(jp)) => jp.read(logEvent.message).asInstanceOf[Any] match {
        case s: String => s
        case m: Map[_, _] => m.asInstanceOf[Map[String, Any]]
        case jo: JObject => jo.extract[Map[String, Any]]
        case _ => logEvent.message.extract[Map[String, Any]]
      }
      case _ => logEvent.message.extract[Map[String, Any]]
    }) match {
      case s: String => Map(wrapperKey.getOrElse("log") -> s)
      case m: Map[_, _] => wrapperKey match {
        case Some(wk) => Map(wk -> m)
        case None => m.asInstanceOf[Map[String, Any]]
      }
    }

    val fields: Map[String, String] = jsonOptions match {
      case (Some(m), _) => m.map { case (key, path) => (key, path.read(logEvent.message).asInstanceOf[Any]) }
        .collect { case (k: String, v: String) => (k, v) }
      case _ => Map.empty
    }

    Seq(SumoRequest(
      key = DefaultLogKey(sourceCategory),
      dataType = SumoDataType.logs,
      format = SumoDataFormat.json,
      endpointName = endpoint.name.getOrElse("default"),
      sourceName = sourceName,
      sourceCategory = sourceCategory,
      sourceHost = config.host,
      fields = Seq.empty,
      endpoint = endpoint.uri,
      logs = Seq(RawJsonRequest((Map("timestamp" -> Instant.now().toEpochMilli).toSeq ++ payload.toSeq ++ fields.toSeq).toMap))
    ))
  }

  def findSourceNameOrCategory(fixed: Option[String], jsonPath: Option[JsonPath], topic: String, logEvent: JsonLogEvent): String = {
    val jsonOpts =  (fixed, jsonPath)
    jsonOpts match {
      case (Some(fixed), _) => fixed
      case (_, Some(jp)) => jp.read(logEvent.message).asInstanceOf[String] match {
        case null | "" => topic
        case v => v
      }
      case _ => topic
    }
  }

  def createSumoRequestsFromLogEvent(config: AppConfig, logEvent: KubernetesLogEvent): Seq[SumoRequest] = {
    val endpointName = findEndpointName(config, logEvent)
    val endpointFilters = findEndpointFilters(config, logEvent)
    val request = SumoRequest(key = KubernetesLogKey(podName = logEvent.metadata.name, containerName = logEvent.metadata.container, value = endpointName),
      dataType = SumoDataType.logs,
      format = findEndpointFormat(config, logEvent),
      endpointName = endpointName,
      sourceName = findSourceName(config, logEvent),
      sourceCategory = findSourceCategory(config, logEvent),
      sourceHost = "",
      fields = Seq(s"container=${logEvent.metadata.container}",
        s"node=${logEvent.metadata.nodeName}",
        s"pod=${logEvent.metadata.name}",
        s"namespace=${logEvent.metadata.namespace}"),
      endpoint = config.sumoEndpoints(endpointName).uri,
      logs = Seq(LogRequest(logEvent.timestamp.getOrElse(Instant.now().toEpochMilli), logEvent.message)))

    request +: endpointFilters.map { ep =>
      request.copy(key = request.key.asInstanceOf[KubernetesLogKey].copy(value = ep.name), endpointName = ep.name,
        logs = request.logs.filter { case log: LogRequest => ep.pattern.forall(_.matches(log.log)) })
    }.filterNot(_.logs.isEmpty)
  }

  private def ignoreLog(config: AppConfig, logEvent: KubernetesLogEvent): Boolean = {
    Seq(s"${logEvent.metadata.namespace}.${logEvent.metadata.container}", logEvent.metadata.namespace).view
      .exists(key => config.containerExclusions.get(key) match {
        case Some(exclusion) => exclusion.toBoolean
        case _ => false
      }) ||
      findPodMetadataValue(ExcludeAnnotation, false, logEvent, convertValueOpt = Some(_.toBoolean))
  }

  private def findEndpointFilters(config: AppConfig, logEvent: KubernetesLogEvent): Seq[EndpointFilter] = {
    val annotationPrefix = s"$EndpointFilterAnnotationPrefix.${logEvent.metadata.container}."
    logEvent.metadata.labels.withFilter { case (k, _) => k.startsWith(annotationPrefix) && k.length > annotationPrefix.length && config.sumoEndpoints.contains(k.substring(annotationPrefix.length)) }
      .map { case (k, v) => EndpointFilter(name = k.substring(annotationPrefix.length), container = logEvent.metadata.container, regex = Some(v), None) }
      .map { ep => ep.copy(pattern = patterns.get(ep)) }
      .toSeq
  }

  private def findPodMetadataValue[V](metadataKeyPrefix: String, defaultValue: V, logEvent: KubernetesLogEvent,
                                      validateOpt: Option[String => Boolean] = None,
                                      convertValueOpt: Option[String => V] = None, annotation: Boolean = true): V = {
    val metadata = if (annotation) logEvent.metadata.annotations else logEvent.metadata.labels
    val metadataKey = s"$metadataKeyPrefix.${logEvent.metadata.container}"
    Seq(metadataKey, metadataKeyPrefix).view.flatMap { k => metadata.get(k) }
      .find(metadataValue => validateOpt match {
        case Some(validate) => validate(metadataValue)
        case _ => true
      }).map(metadataValue => convertValueOpt match {
      case Some(convertValue) => convertValue(metadataValue)
      case _ => metadataValue.asInstanceOf[V]
    }).getOrElse(defaultValue)
  }

  private def findSourceName(config: AppConfig, logEvent: KubernetesLogEvent): String = {
    val sourceNameOverride = config.sourceNameOverrides.get(s"${logEvent.metadata.namespace}.${logEvent.metadata.container}")
    sourceNameOverride match {
      case Some(sourceName) => sourceName
      case _ => findPodMetadataValue(SourceNameAnnotationPrefix, s"${logEvent.metadata.name}.${logEvent.metadata.container}", logEvent, None, None)
    }
  }

  private def findSourceCategory(config: AppConfig, logEvent: KubernetesLogEvent): String = {
    val sourceCategoryOverride = config.sourceCategoryOverrides.get(s"${logEvent.metadata.namespace}.${logEvent.metadata.name}")
    sourceCategoryOverride match {
      case Some(sourceCategory) => sourceCategory
      case _ => findPodMetadataValue(CategoryAnnotationPrefix, s"kubernetes/${logEvent.metadata.namespace.replace('-', '/')}", logEvent)
    }
  }

  private def findEndpointName(config: AppConfig, logEvent: KubernetesLogEvent): String = {
    val endpointNameOverride = config.endpointNameOverrides.get(s"${logEvent.metadata.namespace}.${logEvent.metadata.container}")
    endpointNameOverride match {
      case Some(endpointName) => endpointName
      case _ => findPodMetadataValue(EndpointAnnotationPrefix, config.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.get._1, logEvent,
        Some(ep => config.sumoEndpoints.contains(ep)))
    }
  }

  private def findEndpointFormat(config: AppConfig, logEvent: KubernetesLogEvent): Format = {
    config.endpointNameOverrides.get(s"${logEvent.metadata.namespace}.${logEvent.metadata.container}") match {
      case Some(_) => SumoDataFormat.text
      case _ => findPodMetadataValue(FormatAnnotationPrefix, SumoDataFormat.json, logEvent,
        Some(format => SumoDataFormat.withNameOpt(format).isDefined), Some(v => SumoDataFormat.withNameOpt(v).get))
    }
  }
}