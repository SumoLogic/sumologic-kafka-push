package com.sumologic.sumopush.actor

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior, Terminated}
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer.{Control, DrainingControl}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{ActorAttributes, FlowShape, Graph, Supervision}
import akka.{Done, NotUsed}
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.ConsumerCommand.ConsumerShutdown
import com.sumologic.sumopush.model.SumoRequest
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object PushConsumer {

  private val log = LoggerFactory.getLogger(getClass)

  def apply[K, V](config: AppConfig, source: Source[CommittableMessage[K, V], Control],
                  createGraph: ActorContext[ConsumerCommand] => Graph[FlowShape[CommittableMessage[K, V], (Option[SumoRequest], Option[CommittableOffset])], NotUsed]): Behavior[ConsumerCommand] = Behaviors.setup[ConsumerCommand] { context => {
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val committerSettings = CommitterSettings(system)

    val control = source
      .via(createGraph(context))
      .groupBy(config.streamsMax, {
        case (Some(request), _) => request.key
        case (None, _) => ""
      })
      .groupedWithin(config.groupedSize, config.groupedDuration)
      .mapAsync(10) { batch => Future((batch.map(_._1).reduce((l, r) => l.flatMap(lv => r.map(rv => rv.copy(logs = lv.logs ++ rv.logs)))), batch.flatMap(_._2))) }
      .via(LogPusher(config)(context.system))
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
        case Success(_) => log.info("kafka consumer cleanly drained")
        case Failure(_) => log.info("stream failed with decider")
      }
      Await.result(drain, 30.seconds)
    }

    Behaviors.receiveMessage[ConsumerCommand] {
      case ConsumerShutdown =>
        shutdown()
        Behaviors.stopped
    }.receiveSignal {
      case (context, Terminated(ref)) =>
        context.log.info("Actor stopped: {}", ref.path.name)
        shutdown()
        Behaviors.stopped
    }
  }
  }
}