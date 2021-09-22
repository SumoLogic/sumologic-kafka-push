package com.sumologic.sumopush.actor

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import akka.stream.scaladsl.{Flow, GraphDSL}
import akka.stream.typed.scaladsl.ActorFlow
import akka.stream.{FlowShape, Graph}
import akka.util.Timeout
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.MetricProcessor.ConsumerMetricMessage
import com.sumologic.sumopush.actor.MetricsK8sMetadataCache.K8sMetaRequest
import com.sumologic.sumopush.model.{PromMetricEvent, SumoRequest}
import io.prometheus.client.Counter
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object MetricsFlow {
  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  final val messages_failed = Counter.build()
    .name("messages_failed")
    .help("Total messages failed processing")
    .labelNames("exception").register()

  case class ParsedMetricMessage(pme: Option[PromMetricEvent], offset: CommittableOffset)

  def apply(
             context: ActorContext[ConsumerCommand], config: AppConfig, stats: MessageProcessor.Stats
           ): Graph[FlowShape[CommittableMessage[String, Try[PromMetricEvent]], (Option[SumoRequest], Option[CommittableOffset])], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder => {
      implicit val timeout: Timeout = 5.minutes

      val k8sMetaCache = context.spawn(MetricsK8sMetadataCache(), "k8s-meta-cache")
      context.watch(k8sMetaCache)

      val processor = context.spawn(MetricProcessor(config, stats), "metric-processor")
      context.watch(processor)

      builder.add(Flow[CommittableMessage[String, Try[PromMetricEvent]]]
        .mapConcat { msg =>
          msg.record.value match {
            case Success(pme) => Seq(ParsedMetricMessage(Some(pme), msg.committableOffset))
            case Failure(e) =>
              val exName = e.toString.split(":")(0)
              messages_failed.labels(exName).inc()
              log.error("Unable to parse prom metric message {}", e.getMessage, e)
              Seq(ParsedMetricMessage(None, msg.committableOffset))
          }
        }
        .via(ActorFlow.ask(10)(k8sMetaCache) {
          (message: ParsedMetricMessage, replyTo: ActorRef[ParsedMetricMessage]) =>
            K8sMetaRequest(message, replyTo)
        })
        .via(ActorFlow.ask(10)(processor) {
          (message: ParsedMetricMessage, replyTo: ActorRef[(Option[SumoRequest], Option[CommittableOffset])]) =>
            ConsumerMetricMessage(message.pme, message.offset, replyTo)
        }))
    }
    })
}