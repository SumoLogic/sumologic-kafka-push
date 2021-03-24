package com.sumologic.sumopush.serde

import com.sumologic.sumopush.model.{JsonLogEvent, JsonLogEventSerializer, LogEvent}
import org.json4s.JsonAST.JValue

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.{Failure, Success, Try}

object JsonLogEventSerde extends LogEventSerde[JValue] {
  override def serialize(topic: String, data: LogEvent[JValue]): Array[Byte] = JsonLogEventSerializer.toJson(data).getBytes(UTF_8)

  override def deserialize(topic: String, data: Array[Byte]): Try[JsonLogEvent] = {
    try {
      Success(JsonLogEventSerializer.fromJson(data))
    } catch {
      case e: Exception => Failure(new Exception(s"payload: ${new String(data, UTF_8)}", e))
    }
  }
}
