package com.sumologic.sumopush.actor

import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorFlow
import akka.stream.{ActorAttributes, Supervision}
import akka.util.Timeout
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.ConsumerCommand.ConsumerShutdown
import com.sumologic.sumopush.actor.LogPusher.{PusherStop, SumoApiRequest}
import com.sumologic.sumopush.actor.MetricProcessor.ConsumerMetricMessage
import com.sumologic.sumopush.actor.MetricsK8sMetadataCache.K8sMetaRequest
import com.sumologic.sumopush.model.{PromMetricEvent, SumoRequest}
import com.sumologic.sumopush.serde.PromMetricEventSerde
import io.prometheus.client.Counter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object MetricConsumer {
  final val messages_failed = Counter.build()
    .name("messages_failed")
    .help("Total messages failed processing")
    .labelNames("exception").register()

  case class ParsedMetricMessage(pme: Option[PromMetricEvent], offset: CommittableOffset)

  def apply(config: AppConfig, k8sMetaCache: ActorRef[MetricsK8sMetadataCache.Message]): Behavior[ConsumerCommand] = Behaviors.setup[ConsumerCommand] { context =>
    implicit val system: ActorSystem = context.system.toClassic
    implicit val executionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    implicit val timeout: Timeout = 5.minutes

    val metricPusher = context.spawn(LogPusher(config), "metric-pusher")
    val metricProcessor = context.spawn(MetricProcessor(config), "metric-processor")
    context.watch(metricPusher)
    context.watch(metricProcessor)

    val settings = ConsumerSettings(system, new StringDeserializer, PromMetricEventSerde)
      .withBootstrapServers(bootstrapServers = config.bootstrapServers)
      .withGroupId(config.consumerGroupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.offsetReset)
      .withStopTimeout(Duration.Zero)
    val committerSettings = CommitterSettings(system)

    val control = Consumer.committableSource(settings, Subscriptions.topics(config.topic))
      .mapConcat { msg =>
        msg.record.value match {
          case Success(pme) => Seq(ParsedMetricMessage(Some(pme), msg.committableOffset))
          case Failure(e) =>
            val exName = e.toString.split(":")(0)
            messages_failed.labels(exName).inc()
            context.log.error("Unable to parse prom metric message {}", e.getMessage, e)
            Seq(ParsedMetricMessage(None, msg.committableOffset))
        }
      }
      .via(ActorFlow.ask(10)(k8sMetaCache) {
        (message: ParsedMetricMessage, replyTo: ActorRef[ParsedMetricMessage]) =>
          K8sMetaRequest(message, replyTo)
      })
      .via(ActorFlow.ask(10)(metricProcessor) {
        (message: ParsedMetricMessage, replyTo: ActorRef[(Option[SumoRequest], CommittableOffset)]) =>
          ConsumerMetricMessage(message.pme, message.offset, replyTo)
      })
      .groupBy(1_000, {
        case (Some(request), _) => request.key
        case (None, _) => ""
      })
      .groupedWithin(config.groupedSize, config.groupedDuration)
      .mapAsync(10) { batch => Future((batch.map(_._1).reduce((l, r) => l.flatMap(lv => r.map(rv => rv.copy(logs = lv.logs ++ rv.logs)))), batch.map(_._2))) }
      .via(ActorFlow.ask(10)(metricPusher) {
        (flow: (Option[SumoRequest], Seq[CommittableOffset]), replyTo: ActorRef[Seq[CommittableOffset]]) => SumoApiRequest(flow, replyTo)
      })
      .mergeSubstreamsWithParallelism(10)
      .mapConcat(identity)
      .toMat(Committer.sink(committerSettings))(Keep.both)
      .mapMaterializedValue(DrainingControl.apply[Done])
      .withAttributes(ActorAttributes.withSupervisionStrategy(e => {
        context.log.error("stream error", e)
        context.self ! ConsumerShutdown
        Supervision.Stop
      }))
      .run()

    def shutdown(): Unit = {
      context.log.info("draining kafka consumer...")
      val drain = control.drainAndShutdown()
      drain.onComplete {
        case Success(_) => context.log.info("kafka consumer cleanly drained")
        case Failure(_) => context.log.info("stream failed with decider")
      }
      Await.result(drain, 30.seconds)
    }

    Behaviors.receiveMessage[ConsumerCommand] {
      case ConsumerShutdown =>
        metricPusher ! PusherStop
        shutdown()
        Behaviors.stopped { () =>
          context.log.info("kafka consumer stopped")
        }
    }.receiveSignal {
      case (context, Terminated(ref)) =>
        context.log.info("Actor stopped: {}", ref.path.name)
        shutdown()
        Behaviors.stopped {
          () => context.log.info("kafka consumer stopped")
        }
    }
  }
}