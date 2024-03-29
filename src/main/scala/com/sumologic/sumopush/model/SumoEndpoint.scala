package com.sumologic.sumopush.model

import akka.http.scaladsl.model.Uri
import com.jayway.jsonpath.JsonPath
import org.json4s.JsonAST.{JString, JValue}
import org.json4s.ext.EnumNameSerializer
import org.json4s.native.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Formats, StreamInput}

import java.io.ByteArrayInputStream
import java.util.regex.Pattern

case class SumoEndpoint(name: Option[String],
                        uri: Uri,
                        namespaces: Option[List[String]],
                        jsonOptions: Option[JsonOptions],
                        fieldName: Option[String],
                        fieldPattern: Option[Pattern],
                        sourceCategory: Option[String],
                        sourceName: Option[String],
                        missingSrcCatStrategy: MissingSrcCatStrategy.Strategy = MissingSrcCatStrategy.FallbackToTopic,
                        default: Boolean = false) {
  def matchesPromMetric(promMetricEvent: PromMetricEvent): Boolean = {
    val fieldValue = fieldName match {
      case Some(name) => promMetricEvent.labels.getOrElse(name, "")
      case _ => promMetricEvent.name
    }

    fieldPattern match {
      case Some(p) => p.matcher(fieldValue).matches()
      case _ => default
    }
  }
}

object MissingSrcCatStrategy extends Enumeration {
  type Strategy = Value
  val FallbackToTopic, Drop = Value

  def withNameOpt(v: String): Option[Strategy] = MissingSrcCatStrategy.values.find(_.toString == v)
}

case class JsonOptions(sourceCategoryJsonPath: Option[JsonPath],
                       sourceNameJsonPath: Option[JsonPath],
                       fieldJsonPaths: Option[Map[String, JsonPath]],
                       payloadWrapperKey: Option[String],
                       payloadJsonPath: Option[JsonPath],
                       payloadText: Option[Boolean])

object SumoEndpointSerializer extends CustomSerializer[SumoEndpoint](_ => ( {
  case v: JValue =>
    implicit val formats: Formats = DefaultFormats + JsonOptionsSerializer + RegexSerializer + UriSerializer + new EnumNameSerializer(MissingSrcCatStrategy)
    SumoEndpoint(
      name = (v \ "name").extract[Option[String]],
      uri = (v \ "uri").extract[Uri],
      namespaces = (v \ "namespaces").extractOpt[List[String]],
      jsonOptions = (v \ "jsonOptions").extractOpt[JsonOptions],
      fieldName = (v \ "fieldName").extract[Option[String]],
      fieldPattern = (v \ "fieldPattern").extract[Option[Pattern]],
      sourceCategory = (v \ "sourceCategory").extractOpt[String],
      sourceName = (v \ "sourceName").extractOpt[String],
      missingSrcCatStrategy = (v \ "missingSrcCatStrategy").extractOpt[String].flatMap(MissingSrcCatStrategy.withNameOpt).getOrElse(MissingSrcCatStrategy.FallbackToTopic),
      default = (v \ "default").extractOrElse[Boolean](false)
    )
}, {
  case _: SumoEndpoint =>
    throw new UnsupportedOperationException("Serialization of SumoEndpoint not supported")
})) {
  implicit val formats: Formats = DefaultFormats + JsonOptionsSerializer + RegexSerializer + UriSerializer + new EnumNameSerializer(MissingSrcCatStrategy)

  def fromJson(message: Array[Byte]): SumoEndpoint = Serialization.read[SumoEndpoint](StreamInput(new ByteArrayInputStream(message)))

  def fromJson(message: String): SumoEndpoint = Serialization.read[SumoEndpoint](message)
}

object JsonOptionsSerializer extends CustomSerializer[JsonOptions](_ => ( {
  case v: JValue =>
    implicit val formats: Formats = DefaultFormats
    JsonOptions(
      sourceCategoryJsonPath = (v \ "sourceCategoryJsonPath").extractOpt[String].map(JsonPath.compile(_)),
      sourceNameJsonPath = (v \ "sourceNameJsonPath").extractOpt[String].map(JsonPath.compile(_)),
      fieldJsonPaths = (v \ "fieldJsonPaths").extractOpt[Map[String, String]].map { m => m map { case (k, v) => (k, JsonPath.compile(v)) } },
      payloadWrapperKey = (v \ "payloadWrapperKey").extractOpt[String],
      payloadText = (v \ "payloadText").extractOpt[Boolean],
      payloadJsonPath = (v \ "payloadJsonPath").extractOpt[String].map(JsonPath.compile(_))
    )
}, {
  case _: JsonOptions =>
    throw new UnsupportedOperationException("Serialization of SumoEndpoint not supported")
}))

object RegexSerializer extends CustomSerializer[Pattern](_ => ( {
  case JString(s) => s.r.pattern
}, {
  case p: Pattern => JString(p.toString)
}
))

object UriSerializer extends CustomSerializer[Uri](_ => ( {
  case JString(s) => Uri(s)
}, {
  case u: Uri => JString(u.toString)
}
))
