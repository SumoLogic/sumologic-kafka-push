package com.sumologic.sumopush.actor

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Terminated}
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorFlow
import akka.stream.{ActorAttributes, Supervision}
import akka.util.Timeout
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.ConsumerCommand.ConsumerShutdown
import com.sumologic.sumopush.actor.LogProcessor.ConsumerLogMessage
import com.sumologic.sumopush.actor.LogPusher.{PusherStop, SumoApiRequest}
import com.sumologic.sumopush.model.{LogEvent, SumoRequest}
import com.sumologic.sumopush.serde.LogEventSerde
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.Logger

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.runtime.universe
import scala.util.{Failure, Success, Try}

object LogConsumer {

  def loadSerde(log: Logger, className: String): LogEventSerde[Any] = {
    try {
      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
      val module = runtimeMirror.staticModule(className)
      runtimeMirror.reflectModule(module).instance.asInstanceOf[LogEventSerde[Any]]
    } catch {
      case e: Throwable =>
        log.error("failed to load kafka serde", e)
        sys.exit(1)
    }
  }

  def apply(config: AppConfig): Behavior[ConsumerCommand] = Behaviors.setup[ConsumerCommand] { context =>
    implicit val system: ActorSystem = context.system.toClassic
    implicit val executionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    implicit val timeout: Timeout = 5.minutes

    val logPusher = context.spawn(LogPusher(config), "log-pusher")
    val logProcessor = context.spawn(LogProcessor(config), "log-processor")
    context.watch(logPusher)
    context.watch(logProcessor)

    val serde = loadSerde(context.log, config.serdeClass)

    val settings = ConsumerSettings(system, new StringDeserializer, serde)
      .withBootstrapServers(bootstrapServers = config.bootstrapServers)
      .withGroupId(config.consumerGroupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.offsetReset)
      .withStopTimeout(Duration.Zero)
    val committerSettings = CommitterSettings(system)

    val control = Consumer.committableSource(settings, Subscriptions.topicPattern(config.topic))
      .via(ActorFlow.ask(10)(logProcessor) {
        (message: CommittableMessage[String, Try[LogEvent[Any]]], replyTo: ActorRef[(Option[Seq[SumoRequest]], CommittableOffset)]) =>
          ConsumerLogMessage(message.record, message.committableOffset, replyTo)
      })
      .mapConcat {
        case (Some(requests), offset) => ((Some(requests.head), Some(offset)) +: requests.drop(1).map(r => (Some(r), None))).toList
        case (None, offset) => List((None, Some(offset)))
      }
      .groupBy(1_000, {
        case (Some(request), _) => request.key
        case (None, _) => ""
      })
      .groupedWithin(config.groupedSize, config.groupedDuration)
      .mapAsync(10) { batch => Future((batch.map(_._1).reduce((l, r) => l.flatMap(lv => r.map(rv => rv.copy(logs = lv.logs ++ rv.logs)))), batch.flatMap(_._2))) }
      .via(ActorFlow.ask(10)(logPusher) {
        (flow: (Option[SumoRequest], Seq[CommittableOffset]), replyTo: ActorRef[Seq[CommittableOffset]]) => SumoApiRequest(flow, replyTo)
      })
      .mergeSubstreams
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
        logPusher ! PusherStop
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