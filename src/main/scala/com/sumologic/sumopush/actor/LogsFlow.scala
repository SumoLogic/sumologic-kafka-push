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
import com.sumologic.sumopush.actor.LogProcessor.ConsumerLogMessage
import com.sumologic.sumopush.model.{LogEvent, SumoRequest}

import scala.concurrent.duration.DurationInt
import scala.util.Try

object LogsFlow {
  def apply(
             context: ActorContext[ConsumerCommand], config: AppConfig, stats: MessageProcessor.Stats
           ): Graph[FlowShape[CommittableMessage[String, Try[LogEvent[Any]]], (Option[SumoRequest], Option[CommittableOffset])], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder => {
      implicit val timeout: Timeout = 5.minutes

      val ref = context.spawn(LogProcessor(config, stats), "log-processor")
      context.watch(ref)

      builder.add(Flow[CommittableMessage[String, Try[LogEvent[Any]]]]
        .via(ActorFlow.ask(10)(ref) {
          (message: CommittableMessage[String, Try[LogEvent[Any]]], replyTo: ActorRef[(Option[Seq[SumoRequest]], CommittableOffset)]) =>
            ConsumerLogMessage(message.record, message.committableOffset, replyTo)
        })
        .mapConcat {
          case (Some(requests), offset) if requests.isEmpty => List((None, Some(offset)))
          case (Some(requests), offset) => ((Some(requests.head), Some(offset)) +: requests.drop(1).map(r => (Some(r), None))).toList
          case (None, offset) => List((None, Some(offset)))
        })
    }
    })
}