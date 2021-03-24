package com.sumologic.sumopush.serde

import com.sumologic.sumopush.model.{KubernetesLogEvent, KubernetesLogEventSerializer, LogEvent}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.{Failure, Success, Try}

object KubernetesLogEventSerde extends LogEventSerde[String] {
  override def serialize(topic: String, data: LogEvent[String]): Array[Byte] = KubernetesLogEventSerializer.toJson(data).getBytes(UTF_8)

  override def deserialize(topic: String, data: Array[Byte]): Try[KubernetesLogEvent] = {
    try {
      Success(KubernetesLogEventSerializer.fromJson(data))
    } catch {
      case e: Exception => Failure(new Exception(s"payload: ${new String(data, UTF_8)}", e))
    }
  }
}