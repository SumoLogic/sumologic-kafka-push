package com.sumologic.sumopush

import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import com.jayway.jsonpath
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonProvider
import com.jayway.jsonpath.spi.mapper.{JacksonMappingProvider, MappingProvider}
import com.sumologic.sumopush.actor.ConsumerCommand.ConsumerShutdown
import com.sumologic.sumopush.actor.MetricsServer._
import com.sumologic.sumopush.actor.{LogConsumer, MetricConsumer, MetricsK8sMetadataCache, MetricsServer}
import com.sumologic.sumopush.json.Json4sProvider
import com.sumologic.sumopush.model.SumoDataType
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import java.io.File
import java.util
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._

object SumoPushMain {
  def apply(config: AppConfig): Behavior[NotUsed] = Behaviors.supervise(
    Behaviors.setup[NotUsed] { context =>
      implicit val executionContext: ExecutionContext = context.executionContext

      val k8sMetadataCache = context.spawn(MetricsK8sMetadataCache(), "k8s-meta-cache")
      val metricsServer = context.spawn(MetricsServer(config), "metrics-server")
      val consumer = config.dataType match {
        case SumoDataType.logs => context.spawn(LogConsumer(config), "log-consumer")
        case SumoDataType.metrics =>
          context.spawn(MetricConsumer(config, k8sMetadataCache), "metric-consumer")
      }
      context.watch(consumer)

      CoordinatedShutdown(context.system).addJvmShutdownHook {
        consumer ! ConsumerShutdown
        metricsServer ! Stop
        Await.result(context.system.whenTerminated.map(_ => context.log.info("shutdown complete")), 1.minute)
      }
      Behaviors.same
    }
  ).onFailure[DeathPactException](akka.actor.typed.SupervisorStrategy.stop)

  def main(args: Array[String]): Unit = {
    val log = LoggerFactory.getLogger(getClass)
    log.info("starting sumo push...")

    // jsonpath config
    Configuration.setDefaults(new Configuration.Defaults {
      override def jsonProvider(): JsonProvider = Json4sProvider

      override def options(): util.Set[jsonpath.Option] = Set[jsonpath.Option]().asJava

      override def mappingProvider(): MappingProvider = new JacksonMappingProvider()
    })

    val confFile = new File("/opt/docker/conf/application.conf")
    val config = if (confFile.exists() && confFile.canRead) {
      ConfigFactory.load()
        .withFallback(ConfigFactory.parseFile(confFile))
        .resolve()
    } else ConfigFactory.load()

    ActorSystem(SumoPushMain(AppConfig(config)).behavior, "sumo-push-main", config)
  }
}