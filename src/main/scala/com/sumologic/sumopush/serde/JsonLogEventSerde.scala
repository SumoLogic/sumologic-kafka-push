package com.sumologic.sumopush.serde

import com.sumologic.sumopush.model.{JsonLogEvent, JsonLogEventSerializer, LogEvent}
import org.json4s.JsonAST.JValue

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

object JsonLogEventSerde extends LogEventSerde[JValue] {
  override def serialize(topic: String, data: LogEvent[JValue]): Array[Byte] = JsonLogEventSerializer.toJson(data).getBytes(UTF_8)

  override def deserialize(topic: String, data: Array[Byte]): Try[JsonLogEvent] = Try(JsonLogEventSerializer.fromJson(data))
}
