package com.sumologic.sumopush.serde

import com.sumologic.sumopush.model.LogEvent
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

import scala.util.Try

abstract class LogEventSerde[T] extends Serializer[LogEvent[T]] with Deserializer[Try[LogEvent[T]]] {
  override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = super.configure(configs, isKey)
}