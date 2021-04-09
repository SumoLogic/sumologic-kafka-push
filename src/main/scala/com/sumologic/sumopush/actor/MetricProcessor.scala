package com.sumologic.sumopush.actor

import java.util.regex.Pattern
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.kafka.ConsumerMessage.CommittableOffset
import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.MessageProcessor._
import com.sumologic.sumopush.model._

object MetricProcessor extends MessageProcessor {

  def kubeletMetricFilter: Pattern =
    "(?:container_cpu_usage_seconds_total|container_memory_working_set_bytes|container_fs_usage_bytes|container_fs_limit_bytes)".r.pattern

  sealed trait MetricMessage {}

  case class ConsumerMetricMessage(msg: Option[PromMetricEvent], offset: CommittableOffset,
                                   replyTo: ActorRef[(Option[SumoRequest], Option[CommittableOffset])]) extends MetricMessage

  def apply(config: AppConfig): Behavior[MetricMessage] = Behaviors.receive { (context, message) =>
    message match {
      case ConsumerMetricMessage(Some(pme), offset, replyTo) =>
        val reply = if (ignoreMetric(pme)) {
          messages_ignored.labels(if (pme.labels.contains("container")) pme.labels("container") else "").inc()
          (None, Some(offset))
        } else {
          config.getMetricEndpoint(pme) match {
            case Some(endpoint@SumoEndpoint(Some(name), _, _, _, _, _, _, _)) =>
              context.log.trace("sumo endpoint name: {}", name)
              messages_processed.labels(if (pme.labels.contains("container")) pme.labels("container") else "", name).inc()
              (Some(createSumoRequestFromLogEvent(config, endpoint, pme)), Some(offset))
            case _ =>
              messages_ignored.labels(if (pme.labels.contains("container")) pme.labels("container") else "").inc()
              (None, Some(offset))
          }
        }
        replyTo ! reply
      case ConsumerMetricMessage(None, offset, replyTo) =>
        replyTo ! ((None, Some(offset)))
    }
    Behaviors.same
  }

  def createSumoRequestFromLogEvent(config: AppConfig, endpoint: SumoEndpoint, promMetricEvent: PromMetricEvent): SumoRequest = {
    val endpointName = endpoint.name.get
    SumoRequest(key = MetricKey(value = endpointName),
      dataType = SumoDataType.metrics,
      format = SumoDataFormat.text,
      endpointName = endpointName,
      sourceName = endpoint.sourceName.getOrElse(endpointName),
      sourceCategory = endpoint.sourceCategory.getOrElse(s"${config.cluster}/$endpointName"),
      sourceHost = "",
      fields = Seq.empty[String],
      endpoint = endpoint.uri,
      logs = Seq(MetricRequest(promMetricEvent.convertToLogLine(config))))
  }

  private def ignoreMetric(pme: PromMetricEvent): Boolean = {
    val excludeMetric =  for {
      container <- pme.labels.get("container")
      k8sMetadata <- pme.k8sMetadata
    } yield findContainerExclusion(k8sMetadata.podAnnotations, container)

    (pme.labels.contains("job") && pme.labels("job") == "kubelet" &&
      (!pme.labels.contains("container") || pme.labels("container") == "POD") &&
      kubeletMetricFilter.matcher(pme.name).matches) ||
    excludeMetric.getOrElse(false)
  }


  private def findContainerExclusion(annotations: Map[String, String], container: String): Boolean = {
    findPodMetadataValue(ExcludeAnnotation, false, annotations, container,
      None, Some(exclusion => exclusion.toBoolean))
  }
}