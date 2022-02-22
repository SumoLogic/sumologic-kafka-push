package com.sumologic.sumopush.model

import akka.http.scaladsl.model._
import com.sumologic.sumopush.model.SumoDataFormat.Format
import org.json4s.JsonAST.{JString, JValue}
import org.json4s.native.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats}

case object SumoRequest {
  val XSumoNameHeader = "X-Sumo-Name"
  val XSumoCategoryHeader = "X-Sumo-Category"
  val XSumoHostHeader = "X-Sumo-Host"
  val XSumoClientHeader = "X-Sumo-Client"
  val XSumoFields = "X-Sumo-Fields"
}

case class SumoRequest(key: SumoKey,
                       dataType: SumoDataType.Val,
                       format: Format,
                       endpointName: String,
                       endpoint: Uri,
                       sourceName: String,
                       sourceCategory: String,
                       sourceHost: String,
                       fields: Seq[HeaderField],
                       logs: Seq[LogRecord])

case class HeaderField private(key: String, value: String) {
  def toHeaderPart = s"$key=$value"
}
object HeaderField {
  def apply(key: String, value: String): HeaderField = {
    new HeaderField(
      key.replace(",", "").replace("=", ""),
      value.replace(",", "").replace("=", ""),
    )
  }
}

sealed trait SumoKey {
  val value: String
}

case class DefaultLogKey(value: String) extends SumoKey

case class KubernetesLogKey(podName: String, containerName: String, value: String) extends SumoKey

case class MetricKey(value: String) extends SumoKey

object SumoDataType extends Enumeration {

  case class Val(contentType: ContentType) extends super.Val

  val logs: Val = Val(ContentTypes.`text/plain(UTF-8)`)
  val metrics: Val = Val(MediaType.customWithFixedCharset("application", "vnd.sumologic.prometheus", HttpCharsets.`UTF-8`))
}

object SumoDataFormat extends Enumeration {
  type Format = Value
  val json, text = Value

  def withNameOpt(v: String): Option[Format] = SumoDataFormat.values.find(_.toString == v)
}

object SumoLogsSerializer extends CustomSerializer[SumoRequest](_ => ( {
  case _: JValue => throw new UnsupportedOperationException("sumo request deserialization unsupported")
}, {
  case request: SumoRequest =>
    implicit val formats: DefaultFormats.type = DefaultFormats
    JString(request.logs
      .map {
        case rjr: RawJsonRequest => rjr.log
        case lr: LogRequest => Map("timestamp" -> lr.timestamp, "log" -> lr.log)
      }.map(Serialization.write(_)).mkString("\n"))
})) {
  implicit val formats: Formats = DefaultFormats + SumoLogsSerializer

  def toJson(request: SumoRequest): String = Extraction.decompose(request).extract[String]
}