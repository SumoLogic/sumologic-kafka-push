package com.sumologic.sumopush

import com.sumologic.sumopush.model.{JsonLogEvent, JsonLogEventSerializer}

import scala.io.Source

object Utils {
  def jsonLogEventFromResource(fileName: String): JsonLogEvent = {
    val source = Source.fromResource(fileName)
    val content = try source.mkString finally source.close()
    JsonLogEventSerializer.fromJson(content)
  }
}
