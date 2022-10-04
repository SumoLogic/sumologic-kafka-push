import Dependencies._
import com.amazonaws.regions.{Region, Regions}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._

lazy val dockerBuildxSettings = Seq(
      name := "sumologic-kafka-push",
      organization := "com.sumologic.kafkapush",
      scalaVersion := Version.Scala,
      organizationName := "SumoLogic",
      description := "Push from Apache Kafka to Sumo Logic",

      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "info",

      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-target:jvm-1.8",
        "-Xlog-reflective-calls",
        "-Xlint",
        "-Ywarn-unused",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),
      scalacOptions in(Test, console) := (scalacOptions in(Compile, console)).value,

      libraryDependencies ++= Vector(
        TypesafeConfig,
        AkkaTyped,
        AkkaSlf4j,
        AkkaStreamTyped,
        AkkaHttp,
        ScalaTest,
        AkkaTypedTestKit,
        AkkaHttp,
        Logback,
        Json4sNative,
        Json4sExt,
        AkkaHttpJson4s,
        AkkaKafka,
        ApacheKafkaClient,
        PrometheusAkkaHttp,
        PrometheusClient,
        Re2j,
        Guava,
        Skuber,
        ScalacacheCore,
        ScalacacheGuava,
        JsonPath,
        Mockito
      ),
      daemonUser in Docker := "sumo",
      daemonGroup in Docker := "sumo",
      daemonUserUid in Docker := Some("1000"),
      daemonGroupGid in Docker := Some("1000"),
      dockerExposedPorts in Docker ++= Seq(8080),
      dockerRepository := Some("public.ecr.aws/sumologic"),
      dockerBaseImage := "public.ecr.aws/sumologic/sumologic-kafka-push:focal-corretto-11-multiarch"
  ) 

lazy val sumologicKafkaPush =
  Project(id = "sumologic-kafka-push", base = file("."))
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(AshScriptPlugin)
    .enablePlugins(BuildInfoPlugin)
    .enablePlugins(DockerPlugin)
    .settings(dockerBuildxSettings)
