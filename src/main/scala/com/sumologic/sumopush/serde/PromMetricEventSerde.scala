package com.sumologic.sumopush.serde

import java.nio.charset.StandardCharsets.UTF_8

import com.sumologic.sumopush.model.{PromMetricEvent, PromMetricEventSerializer}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

import scala.util.Try

object PromMetricEventSerde extends Serializer[PromMetricEvent] with Deserializer[Try[PromMetricEvent]] {
  override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = super.configure(configs, isKey)

  override def serialize(topic: String, data: PromMetricEvent): Array[Byte] = PromMetricEventSerializer.toJson(data).getBytes(UTF_8)

  override def deserialize(topic: String, data: Array[Byte]): Try[PromMetricEvent] = Try(PromMetricEventSerializer.fromJson(data))
}