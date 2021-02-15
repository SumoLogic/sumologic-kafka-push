package com.sumologic.sumopush.model

import org.json4s.JsonAST.JValue
import org.json4s.native.Serialization
import org.json4s.{DefaultFormats, Formats, StreamInput}

import java.io.ByteArrayInputStream

case class JsonLogEvent(message: JValue) extends LogEvent[JValue]

case object JsonLogEventSerializer {
  implicit val formats: Formats = DefaultFormats

  def toJson(logEvent: LogEvent[JValue]): String = Serialization.write(logEvent.message)

  def fromJson(message: Array[Byte]): JsonLogEvent = JsonLogEvent(message = Serialization.read[JValue](StreamInput(new ByteArrayInputStream(message))))

  def fromJson(message: String): JsonLogEvent = JsonLogEvent(message = Serialization.read[JValue](message))
}