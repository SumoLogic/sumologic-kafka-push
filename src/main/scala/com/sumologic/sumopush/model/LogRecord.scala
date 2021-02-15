package com.sumologic.sumopush.model

trait LogRecord {
  val log: Any
}

case class RawJsonRequest(log: Map[String, Any]) extends LogRecord

case class LogRequest(timestamp: Long, log: String) extends LogRecord

case class MetricRequest(log: String) extends LogRecord
