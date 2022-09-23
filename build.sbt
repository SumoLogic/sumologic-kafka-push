import Dependencies._
import com.amazonaws.regions.{Region, Regions}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import scala.sys.process._

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
//        ApacheKafkaConnect,
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
      dockerUsername := Option(System.getenv("DOCKER_USERNAME")).orElse(None),
      dockerBaseImage := "public.ecr.aws/sumologic/sumologic-kafka-push:focal-corretto-11",
      publish in Docker := Def.sequential(
        publishLocal in Docker
      ).value
  ) 

lazy val sumologicKafkaPush =
  Project(id = "sumologic-kafka-push", base = file("."))
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(AshScriptPlugin)
    .enablePlugins(DockerPlugin)
    .enablePlugins(EcrPlugin)
    .enablePlugins(BuildInfoPlugin)
    .settings(dockerBuildxSettings)

region in Ecr := Region.getRegion(Regions.US_WEST_2)
repositoryName in Ecr := "sumologic/sumologic-kafka-push"
repositoryTags in Ecr := Seq(version.value)
localDockerImage in Ecr := (dockerRepository in Docker).value.map(repo => s"$repo/").getOrElse("") + (packageName in Docker).value + ":" + (version in Docker).value
