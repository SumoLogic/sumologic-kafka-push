package com.sumologic.sumopush.actor

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, RetryFlow}
import akka.stream.{FlowShape, Graph}
import akka.util.ByteString
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.model.SumoRequest._
import com.sumologic.sumopush.model.{SumoDataFormat, SumoLogsSerializer, SumoRequest}
import io.prometheus.client.Counter
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object LogPusher {
  lazy val log: Logger = LoggerFactory.getLogger(getClass)

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

  case class PushRequest(endpointName: String, contentLength: Int, offsets: Seq[CommittableOffset])

  def apply(config: AppConfig)(implicit system: ActorSystem[Nothing]): Graph[FlowShape[(Option[SumoRequest], Seq[CommittableOffset]), Seq[CommittableOffset]], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder => {
      import akka.stream.scaladsl.GraphDSL.Implicits._

      implicit val executionContext: ExecutionContext = system.executionContext

      val partition = builder.add(Partition[(Option[SumoRequest], Seq[CommittableOffset])](2,
        {
          case (Some(_), _) => 0
          case _ => 1
        }))
      val merge = builder.add(Merge[Seq[CommittableOffset]](2))
      val pool = Http()(system).superPool[PushRequest]()
      val httpFlow: Flow[(SumoRequest, Seq[CommittableOffset]), Try[Seq[CommittableOffset]], NotUsed] = Flow[(SumoRequest, Seq[CommittableOffset])]
        .map { case (sumoRequest, offsets) => createHttpRequest(sumoRequest, offsets, config) }
        .via(pool)
        .map {
          case (Success(HttpResponse(StatusCodes.OK, _, _, _)), pushRequest) =>
            api_bytes_total.labels(pushRequest.endpointName).inc(pushRequest.contentLength)
            api_response_total.labels(StatusCodes.OK.intValue.toString).inc()
            Success(pushRequest.offsets)
          case (Success(response@HttpResponse(status, _, entity, _)), _) =>
            api_response_total.labels(status.intValue().toString).inc()
            val payload = Unmarshal(entity).to[String]
            response.discardEntityBytes()
            Failure(new Exception(s"Sumo api response entity: $payload"))
          case (Failure(e), _) =>
            Failure(e)
        }
      val retryFlow = RetryFlow.withBackoff(config.initRetryDelay.second, config.retryDelayMax.second, config.retryDelayFactor, config.retryMaxAttempts, httpFlow)(
        decideRetry = {
          case (in, Failure(_)) => Some(in)
          case (_, Success(_)) => None
        })
        .map {
          case Success(offsets) => offsets
          case Failure(e) =>
            val exName = e.toString.split(":")(0)
            api_error_total.labels(exName).inc()
            log.error("Sumo api error response", e)
            Seq.empty
        }

      partition.out(0).map(v => (v._1.get, v._2)) ~> retryFlow ~> merge
      partition.out(1).map(_._2) ~> merge

      FlowShape(partition.in, merge.out)
    }
    })

  private def createHttpRequest(sumoRequest: SumoRequest, offsets: Seq[CommittableOffset], config: AppConfig): (HttpRequest, PushRequest) = {
    val endpoint = config.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.map(_._2).get
    val moveFieldsIntoPayload = endpoint.jsonOptions.flatMap(_.moveFieldsIntoPayload).getOrElse(false)
    val requestBytes = if (moveFieldsIntoPayload) {
      SumoLogsSerializer.toJson(sumoRequest).getBytes(UTF_8)
    } else {
      sumoRequest.format match {
        case SumoDataFormat.json => SumoLogsSerializer.toJson(sumoRequest).getBytes(UTF_8)
        case SumoDataFormat.text => sumoRequest.logs.map(_.log).mkString("\n").getBytes(UTF_8)
        case f => throw new UnsupportedOperationException(s"Unsupported sumo format $f")
      }
    }

    log.debug("Source: {}, bytes: {}, endpoint: {}", sumoRequest.sourceName, requestBytes.length, sumoRequest.endpointName)

    val headers = collection.mutable.Buffer(RawHeader(XSumoNameHeader, sumoRequest.sourceName),
      RawHeader(XSumoCategoryHeader, sumoRequest.sourceCategory),
      RawHeader(XSumoHostHeader, sumoRequest.sourceHost),
      RawHeader(XSumoClientHeader, "sumologic-kafka-push"))
    if (sumoRequest.fields.nonEmpty) {
      headers += RawHeader(XSumoFields, sumoRequest.fields.map(_.toHeaderPart).mkString(","))
    }

    val request = HttpRequest()
      .withUri(sumoRequest.endpoint)
      .withMethod(HttpMethods.POST)
      .withHeaders(headers.toSeq)
      .withEntity(HttpEntity.Strict(contentType = sumoRequest.dataType.contentType, ByteString(requestBytes)))
    (encodeRequest(request, config.encoding), PushRequest(sumoRequest.endpointName, requestBytes.length, offsets))
  }

  def encodeRequest(request: HttpRequest, encoding: String): HttpRequest = {
    val encoder = encoding match {
      case "gzip" =>
        Coders.Gzip
      case "deflate" =>
        Coders.Deflate
      case _ =>
        Coders.NoCoding
    }
    encoder.encodeMessage(request)
  }
}