package com.sumologic.sumopush.actor

import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import com.lonelyplanet.prometheus.PrometheusResponseTimeRecorder
import com.lonelyplanet.prometheus.api.MetricsEndpoint
import com.sumologic.sumopush.AppConfig
import io.prometheus.client.hotspot.DefaultExports

import scala.util.{Failure, Success}

object MetricsServer {
  sealed trait Message

  private final case class StartFailed(cause: Throwable) extends Message

  private final case class Started(binding: ServerBinding) extends Message

  case object Stop extends Message

  def apply(config: AppConfig): Behavior[Message] = Behaviors.setup { ctx =>
    implicit val system: akka.actor.ActorSystem = ctx.system.toClassic

    DefaultExports.initialize()
    val prometheusRegistry = PrometheusResponseTimeRecorder.DefaultRegistry
    val metricsEndpoint = new MetricsEndpoint(prometheusRegistry)

    val routes = metricsEndpoint.routes
    val bindFuture = Http().bindAndHandle(routes, interface = "0.0.0.0",
      port = config.metricsServerPort)
    ctx.pipeToSelf(bindFuture) {
      case Success(binding) => Started(binding)
      case Failure(ex) => StartFailed(ex)
    }

    def running(binding: ServerBinding): Behavior[Message] =
      Behaviors.receiveMessagePartial[Message] {
        case Stop =>
          ctx.log.info(
            "Stopping metrics server http://{}:{}/",
            binding.localAddress.getHostString,
            binding.localAddress.getPort)
          Behaviors.stopped
      }.receiveSignal {
        case (_, PostStop) =>
          binding.unbind()
          Behaviors.same
      }

    def starting(wasStopped: Boolean): Behaviors.Receive[Message] =
      Behaviors.receiveMessage[Message] {
        case StartFailed(cause) =>
          throw new RuntimeException("Server failed to start", cause)
        case Started(binding) =>
          ctx.log.info(
            "Metrics server online at http://{}:{}/",
            binding.localAddress.getHostString,
            binding.localAddress.getPort)
          if (wasStopped) ctx.self ! Stop
          running(binding)
        case Stop =>
          // we got a stop message but haven't completed starting yet,
          // we cannot stop until starting has completed
          starting(wasStopped = true)
      }

    starting(wasStopped = false)
  }
}

