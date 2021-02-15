package com.sumologic.sumopush.actor

import java.util.regex.Pattern

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.kafka.ConsumerMessage.CommittableOffset
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.model._
import io.prometheus.client.Counter

object MetricProcessor {
  final val messages_processed = Counter.build()
    .name("messages_processed")
    .help("Total messages processed")
    .labelNames("container", "sumo_endpoint").register()
  final val messages_ignored = Counter.build()
    .name("messages_ignored")
    .help("Total messages ignored")
    .labelNames("container").register()

  def kubeletMetricFilter: Pattern =
    "(?:container_cpu_usage_seconds_total|container_memory_working_set_bytes|container_fs_usage_bytes|container_fs_limit_bytes)".r.pattern

  sealed trait MetricMessage {}

  case class ConsumerMetricMessage(msg: Option[PromMetricEvent], offset: CommittableOffset,
                                   replyTo: ActorRef[(Option[SumoRequest], CommittableOffset)]) extends MetricMessage

  def apply(config: AppConfig): Behavior[MetricMessage] = Behaviors.receive { (context, message) =>
    message match {
      case ConsumerMetricMessage(Some(pme), offset, replyTo) =>
        val reply = if (ignoreMetric(config, pme)) {
          messages_ignored.labels(if (pme.labels.contains("container")) pme.labels("container") else "").inc()
          (None, offset)
        } else {
          config.getMetricEndpoint(pme) match {
            case Some(endpoint@SumoEndpoint(Some(name), _, _, _, _, _)) =>
              context.log.trace("sumo endpoint name: {}", name)
              messages_processed.labels(if (pme.labels.contains("container")) pme.labels("container") else "", name).inc()
              (Some(createSumoRequestFromLogEvent(config, endpoint, pme)), offset)
            case _ =>
              messages_ignored.labels(if (pme.labels.contains("container")) pme.labels("container") else "").inc()
              (None, offset)
          }
        }
        replyTo ! reply
      case ConsumerMetricMessage(None, offset, replyTo) =>
        replyTo ! ((None, offset))
    }
    Behaviors.same
  }

  def createSumoRequestFromLogEvent(config: AppConfig, endpoint: SumoEndpoint, promMetricEvent: PromMetricEvent): SumoRequest = {
    val endpointName = endpoint.name.get
    SumoRequest(key = MetricKey(value = endpointName),
      dataType = SumoDataType.metrics,
      format = SumoDataFormat.text,
      endpointName = endpointName,
      sourceName = endpointName,
      sourceCategory = s"${config.cluster}/$endpointName",
      sourceHost = "",
      fields = Seq.empty[String],
      endpoint = endpoint.uri,
      logs = Seq(MetricRequest(promMetricEvent.convertToLogLine(config))))
  }

  private def ignoreMetric(config: AppConfig, pme: PromMetricEvent): Boolean = {
    (pme.labels.contains("job") && pme.labels("job") == "kubelet" &&
      (!pme.labels.contains("container") || pme.labels("container") == "POD") &&
      kubeletMetricFilter.matcher(pme.name).matches) ||
      Seq[Option[String]](pme.labels.get("namespace").flatMap(ns => pme.labels.get("container").map(c => s"$ns.$c")), pme.labels.get("namespace")).view
        .exists(k => k match {
          case Some(key) => config.containerExclusions.get(key).exists(_.toBoolean)
          case _ => false
        })
  }
}