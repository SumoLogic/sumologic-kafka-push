package com.sumologic.sumopush.model

import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.json4s.native.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset, ZonedDateTime}

case class KubernetesLogEvent(message: String,
                              stream: String,
                              timestamp: Option[Long],
                              metadata: KubernetesPodMetadata) extends ContainerLogEvent

case class KubernetesPodMetadata(name: String,
                                 uid: String,
                                 namespace: String,
                                 nodeName: String,
                                 container: String,
                                 containerImage: Option[String],
                                 annotations: Map[String, String],
                                 labels: Map[String, String]) extends PodMetadata

object KubernetesLogEventSerializer extends CustomSerializer[KubernetesLogEvent](_ => ( {
  case v: JValue =>
    implicit val formats: Formats = DefaultFormats + KubernetesSerializer
    KubernetesLogEvent(
      message = (v \ "message").extract[String].stripLineEnd,
      stream = (v \ "stream").extract[String],
      timestamp = (v \ "timestamp").extractOpt[String].map(ZonedDateTime.parse(_).toInstant.toEpochMilli),
      metadata = (v \ "kubernetes").extract[KubernetesPodMetadata]
    )
}, {
  case kle: KubernetesLogEvent =>
    implicit val formats: Formats = DefaultFormats + KubernetesSerializer
    ("message" -> kle.message) ~
      ("stream" -> kle.stream) ~
      ("timestamp" -> kle.timestamp.map(Instant.ofEpochMilli(_).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))) ~
      ("kubernetes" -> Extraction.decompose(kle.metadata))
})) {
  implicit val formats: Formats = DefaultFormats + KubernetesLogEventSerializer + KubernetesSerializer

  def toJson(logEvent: LogEvent[String]): String = Serialization.write(logEvent)

  def fromJson(message: Array[Byte]): KubernetesLogEvent = Serialization.read[KubernetesLogEvent](new String(message, UTF_8))

  def fromJson(message: String): KubernetesLogEvent = Serialization.read[KubernetesLogEvent](message)
}

object KubernetesSerializer extends CustomSerializer[KubernetesPodMetadata](_ => ( {
  case v: JValue =>
    implicit val formats: DefaultFormats.type = DefaultFormats
    KubernetesPodMetadata(
      name = (v \ "pod_name").extract[String],
      uid = (v \ "pod_uid").extract[String],
      namespace = (v \ "pod_namespace").extract[String],
      nodeName = (v \ "pod_node_name").extract[String],
      container = (v \ "container_name").extract[String],
      containerImage = (v \ "container_image").extractOpt[String],
      annotations = (v \ "pod_annotations").extractOrElse[Map[String, String]](Map.empty),
      labels = (v \ "pod_labels").extractOrElse[Map[String, String]](Map.empty)
    )
}, {
  case vk: KubernetesPodMetadata =>
    ("pod_name" -> vk.name) ~
      ("pod_uid" -> vk.uid) ~
      ("pod_namespace" -> vk.namespace) ~
      ("pod_node_name" -> vk.nodeName) ~
      ("container_name" -> vk.container) ~
      ("container_image" -> vk.containerImage) ~
      ("pod_annotations" -> vk.annotations) ~
      ("pod_labels" -> vk.labels)
}))