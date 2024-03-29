import sbt._

object Version {
  val TypesafeConfig = "1.4.1"
  val Scala = "2.13.5"
  val Akka = "2.6.13"
  val AkkaHttp = "10.2.4"
  val ScalaTest = "3.1.1"
  val Logback = "1.2.10"
  val Json4s = "3.6.7"
  val AkkaHttpJson4s = "1.31.0"
  val AkkaKafka = "2.0.3"
  val ApacheKafkaClient = "2.7.0"
  val PrometheusAkkaHttp = "0.5.1"
  val PrometheusClient = "0.8.1"
  val Re2j = "1.3"
  val Guava = "29.0-jre"
  val Skuber = "2.6.0"
  val Scalacache = "0.28.0"
  val JsonPath = "2.5.0"

  val Mockito = "3.2.9.0"
}

object Dependencies {
  val TypesafeConfig = "com.typesafe" % "config" % Version.TypesafeConfig
  val AkkaTyped = "com.typesafe.akka" %% "akka-actor-typed" % Version.Akka
  val AkkaStreamTyped = "com.typesafe.akka" %% "akka-stream-typed" % Version.Akka
  val AkkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Version.Akka
  val AkkaHttp = "com.typesafe.akka" %% "akka-http" % Version.AkkaHttp
  val Logback = "ch.qos.logback" % "logback-classic" % Version.Logback
  val Json4sNative = "org.json4s" %% "json4s-native" % Version.Json4s
  val Json4sExt = "org.json4s" %% "json4s-ext" % Version.Json4s
  val AkkaHttpJson4s = "de.heikoseeberger" %% "akka-http-json4s" % Version.AkkaHttpJson4s
  val AkkaKafka = "com.typesafe.akka" %% "akka-stream-kafka" % Version.AkkaKafka
  val ApacheKafkaClient = "org.apache.kafka" % "kafka-clients" % Version.ApacheKafkaClient
//  val ApacheKafkaConnect = "org.apache.kafka" % "connect-api" % Version.ApacheKafkaClient
  val PrometheusAkkaHttp = "com.varwise" %% "prometheus-akka-http" % Version.PrometheusAkkaHttp
  val PrometheusClient = "io.prometheus" % "simpleclient_hotspot" % Version.PrometheusClient
  val Re2j = "com.google.re2j" % "re2j" % Version.Re2j
  val Guava = "com.google.guava" % "guava" % Version.Guava
  val Skuber = "io.skuber" %% "skuber" % Version.Skuber
  val ScalacacheCore = "com.github.cb372" %% "scalacache-core" % Version.Scalacache
  val ScalacacheGuava = "com.github.cb372" %% "scalacache-guava" % Version.Scalacache
  val JsonPath = "com.jayway.jsonpath" % "json-path" % Version.JsonPath

  val ScalaTest = "org.scalatest" %% "scalatest" % Version.ScalaTest % Test
  val AkkaTypedTestKit = "com.typesafe.akka" %% "akka-actor-testkit-typed" % Version.Akka % Test
  val Mockito = "org.scalatestplus" %% "mockito-3-4" % Version.Mockito % Test
}
