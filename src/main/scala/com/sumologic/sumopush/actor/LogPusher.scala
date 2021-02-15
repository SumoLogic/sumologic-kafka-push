package com.sumologic.sumopush.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.{Deflate, Gzip, NoCoding}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.util.{ByteString, Timeout}
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.model.SumoRequest._
import com.sumologic.sumopush.model.{SumoDataFormat, SumoLogsSerializer, SumoRequest}
import io.prometheus.client.Counter

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object LogPusher {
  final val api_response_total = Counter.build()
    .name("api_response_total")
    .help("Total api http responses")
    .labelNames("code").register()
  final val api_error_total = Counter.build()
    .name("api_error_total")
    .help("Total api http errors")
    .labelNames("exception").register()
  final val api_bytes_total = Counter.build()
    .name("api_bytes_total")
    .help("Total api bytes")
    .labelNames("sumo_endpoint").register()

  sealed trait PusherCommand

  sealed trait PusherHttpCommand

  final case object PusherStop extends PusherCommand

  final case class PusherSuccess(statusCode: StatusCode) extends PusherCommand

  final case class PusherFailure(e: Throwable) extends PusherCommand

  final case class SumoApiRequest(flow: (Option[SumoRequest], Seq[CommittableOffset]), replyTo: ActorRef[Seq[CommittableOffset]]) extends PusherCommand

  final case class SumoApiRequestRetry(rwb: RequestWithBackOffDuration) extends PusherCommand

  final case object HttpSourceComplete extends PusherHttpCommand

  final case class RequestWithBackOffDuration(replyTo: ActorRef[PusherCommand], sumoRequest: SumoRequest, requestBytes: Array[Byte], config: AppConfig, cur: Int = 0) extends PusherHttpCommand {
    def getHttpRequest: HttpRequest = {
      val headers = collection.mutable.Buffer(RawHeader(XSumoNameHeader, sumoRequest.sourceName),
        RawHeader(XSumoCategoryHeader, sumoRequest.sourceCategory),
        RawHeader(XSumoHostHeader, sumoRequest.sourceHost),
        RawHeader(XSumoClientHeader, "sumologic-kafka-push"))
      if (sumoRequest.fields.nonEmpty) {
        headers += RawHeader(XSumoFields, sumoRequest.fields.mkString(","))
      }

      val request = HttpRequest()
        .withUri(sumoRequest.endpoint)
        .withMethod(HttpMethods.POST)
        .withHeaders(headers.toSeq)
        .withEntity(HttpEntity.Strict(contentType = sumoRequest.dataType.contentType, ByteString(requestBytes)))
      encodeRequest(request, config.encoding)
    }

    def getNext: RequestWithBackOffDuration = {
      copy(cur =
        if (cur == 0)
          config.initRetryDelay
        else
          Math.min(config.retryDelayMax, Math.ceil(cur * config.retryDelayFactor).toInt)
      )
    }
  }

  def encodeRequest(request: HttpRequest, encoding: String): HttpRequest = {
    val encoder = encoding match {
      case "gzip" =>
        Gzip
      case "deflate" =>
        Deflate
      case _ =>
        NoCoding
    }
    encoder.encodeMessage(request)
  }

  def apply(config: AppConfig): Behavior[PusherCommand] = Behaviors.setup[PusherCommand] { context =>
    implicit val timeout: Timeout = 5.minutes
    implicit val system: akka.actor.ActorSystem = context.system.toClassic
    implicit val dispatcher: ExecutionContext = system.dispatcher

    val pool = Http()(system).superPool[RequestWithBackOffDuration]()
    val http = ActorSource.actorRef[PusherHttpCommand](completionMatcher = {
      case HttpSourceComplete => CompletionStrategy.draining
    }, PartialFunction.empty, config.sendBuffer, OverflowStrategy.fail)
      .collect { case rwb: RequestWithBackOffDuration => (rwb.getHttpRequest, rwb) }
      .via(pool)
      .map {
        case (Success(HttpResponse(StatusCodes.OK, _, _, _)), rwb) =>
          api_bytes_total.labels(rwb.sumoRequest.endpointName).inc(rwb.requestBytes.length)
          rwb.replyTo ! PusherSuccess(StatusCodes.OK)
        case (Success(response@HttpResponse(status, _, entity, _)), rwb) =>
          rwb.replyTo ! PusherSuccess(status)
          val nextRwb = rwb.getNext
          context.scheduleOnce(nextRwb.cur.millis, context.self, SumoApiRequestRetry(nextRwb))
          entity.toStrict(100.millis).map {
            _.data.utf8String
          }.andThen {
            case Success(resDoc) =>
              context.log.debug("Sumo api response entity: {}", resDoc)
            case Failure(e) =>
              context.log.warn("Unable to retrieve sumo response entity: {}", e.getMessage)
          }
          response.discardEntityBytes()
        case (Failure(e), rwb) =>
          rwb.replyTo ! PusherFailure(e)
      }
      .to(Sink.ignore)
      .run()
    context.watch(http)

    Behaviors.receiveMessage[PusherCommand] {
      case SumoApiRequestRetry(rwb) =>
        context.log.debug("Resubmitting request to {} after {} millis", rwb.sumoRequest.endpointName, rwb.cur)
        context.ask[PusherHttpCommand, PusherCommand](http, ref => rwb.copy(replyTo = ref)) {
          case Success(resp) => resp
          case Failure(e) => throw e
        }
        Behaviors.same
      case SumoApiRequest((Some(sumoRequest), offsets), replyTo) =>
        val requestBytes = sumoRequest.format match {
          case SumoDataFormat.json => SumoLogsSerializer.toJson(sumoRequest).getBytes(UTF_8)
          case SumoDataFormat.text => sumoRequest.logs.map(_.log).mkString("\n").getBytes(UTF_8)
          case f => throw new UnsupportedOperationException(s"Unsupported sumo format $f")
        }
        context.log.debug("Source: {}, bytes: {}, endpoint: {}", sumoRequest.sourceName, requestBytes.length, sumoRequest.endpointName)
        context.ask[PusherHttpCommand, PusherCommand](http, ref => RequestWithBackOffDuration(ref, sumoRequest, requestBytes, config)) {
          case Success(resp) =>
            replyTo ! offsets
            resp
          case Failure(e) => throw e
        }
        Behaviors.same
      case SumoApiRequest((None, offsets), replyTo) =>
        replyTo ! offsets
        Behaviors.same
      case PusherSuccess(statusCode) =>
        context.log.debug("Sumo api response {}: {}", statusCode.intValue, statusCode.reason())
        api_response_total.labels(statusCode.intValue.toString).inc()
        Behaviors.same
      case PusherFailure(e) =>
        val exName = e.toString.split(":")(0)
        api_error_total.labels(exName).inc()
        context.log.error("Sumo api error response", e)
        Behaviors.same
      case PusherStop =>
        context.log.info("Pusher stopping...")
        context.ask[PusherHttpCommand, Boolean](http, _ => HttpSourceComplete)(PartialFunction.empty)
        Behaviors.stopped {
          () => context.log.info("Pusher stopped")
        }
    }.receiveSignal {
      case (context, Terminated(ref)) =>
        context.log.info("Actor stopped: {}", ref.path.name)
        Behaviors.stopped {
          () => context.log.info("Pusher stopped")
        }
    }
  }
}