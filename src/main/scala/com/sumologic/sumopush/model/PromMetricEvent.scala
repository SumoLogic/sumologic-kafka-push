package com.sumologic.sumopush.model

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

import com.sumologic.sumopush.AppConfig
import com.sumologic.sumopush.actor.MetricsK8sMetadataCache.K8sMetaResponse
import org.json4s.JsonAST.{JString, JValue}
import org.json4s.JsonDSL._
import org.json4s.native.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats, StreamInput}

case class PromMetricEvent(name: String,
                           timestamp: Instant,
                           value: String,
                           labels: Map[String, String],
                           k8sMetadata: Option[K8sMetaResponse] = None) {
  final val excludeLabels = List("__name__", "container_name", "id", "image", "name", "pod_name")
  def convertToLogLine(config: AppConfig): String = {
    val podLabels = k8sMetadata match {
      case Some(meta) => meta.podLabels.map{ case (k, v) => ("pod_labels_" + k, v) }
      case _ => Map.empty[String, String]
    }
    val allLabels = labels.filterNot { case (k, _) => excludeLabels.contains(k) } ++
      podLabels ++ Map("cluster" -> config.cluster, "_origin" -> "kubernetes")
    val labelString = allLabels.map { case (k, v) => s"""${k}="${v}"""" }.mkString(",")
    s"${name}{${labelString}} ${value} ${timestamp.toEpochMilli}"
  }
}

object PromMetricEventSerializer extends CustomSerializer[PromMetricEvent](_ => ( {
  case v: JValue =>
    implicit val formats: Formats = DefaultFormats + InstantSerializer
    PromMetricEvent(
      name = (v \ "name").extract[String],
      timestamp = (v \ "timestamp").extract[Instant],
      value = (v \ "value").extract[String],
      labels = (v \ "labels").extractOrElse[Map[String, String]](Map.empty)
    )
}, {
  case pme: PromMetricEvent =>
    implicit val formats: Formats = DefaultFormats + InstantSerializer
    (("name" -> pme.name) ~
      ("timestamp" -> Extraction.decompose(pme.timestamp)) ~
      ("value" -> pme.value) ~
      ("labels" -> pme.labels))
})) {
  implicit val formats: Formats = DefaultFormats + InstantSerializer

  def toJson(promMetricEvent: PromMetricEvent): String = Serialization.write(promMetricEvent)

  def fromJson(message: Array[Byte]): PromMetricEvent = Serialization.read[PromMetricEvent](StreamInput(new ByteArrayInputStream(message)))

  def fromJson(message: String): PromMetricEvent = Serialization.read[PromMetricEvent](message)
}

/** The default `InstantSerializer` for ISO-8601 strings. */
object InstantSerializer extends InstantSerializer(DateTimeFormatter.ISO_INSTANT)

class InstantSerializer (val format: DateTimeFormatter) extends CustomSerializer[Instant](_ => (
  {
    case JString(s) => Instant.parse(s)
  },
  {
    case t: Instant => JString(format.format(t))
  }
))


