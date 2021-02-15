package com.sumologic.sumopush.serde

import com.sumologic.sumopush.model.{KubernetesLogEvent, KubernetesLogEventSerializer, LogEvent}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

object KubernetesLogEventSerde extends LogEventSerde[String] {
  override def serialize(topic: String, data: LogEvent[String]): Array[Byte] = KubernetesLogEventSerializer.toJson(data).getBytes(UTF_8)

  override def deserialize(topic: String, data: Array[Byte]): Try[KubernetesLogEvent] = Try(KubernetesLogEventSerializer.fromJson(data))
}