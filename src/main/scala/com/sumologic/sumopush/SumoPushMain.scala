package com.sumologic.sumopush

import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import com.jayway.jsonpath
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonProvider
import com.jayway.jsonpath.spi.mapper.{JacksonMappingProvider, MappingProvider}
import com.sumologic.sumopush.actor.ConsumerCommand.ConsumerShutdown
import com.sumologic.sumopush.actor.MetricsServer._
import com.sumologic.sumopush.actor._
import com.sumologic.sumopush.json.Json4sProvider
import com.sumologic.sumopush.model.SumoDataType
import com.sumologic.sumopush.serde.{LogEventSerde, PromMetricEventSerde}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import java.io.File
import java.util
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._
import scala.reflect.runtime.universe

object SumoPushMain {

  private val log = LoggerFactory.getLogger(getClass)

  def apply(config: AppConfig): Behavior[NotUsed] = Behaviors.supervise(
    Behaviors.setup[NotUsed] { context =>
      implicit val executionContext: ExecutionContext = context.executionContext

      val metricsServer = context.spawn(MetricsServer(config), "metrics-server")
      val consumer = config.dataType match {
        case SumoDataType.logs =>
          val settings = commonConsumerSettings(config, ConsumerSettings(context.system, new StringDeserializer, loadSerde(config.serdeClass)))
          context.spawn(PushConsumer(config, Consumer.committableSource(settings, Subscriptions.topicPattern(config.topic)), context => LogsFlow(context, config)), "log-consumer")
        case SumoDataType.metrics =>
          val settings = commonConsumerSettings(config, ConsumerSettings(context.system, new StringDeserializer, PromMetricEventSerde))
          context.spawn(PushConsumer(config, Consumer.committableSource(settings, Subscriptions.topicPattern(config.topic)), context => MetricsFlow(context, config)), "metric-consumer")
      }
      context.watch(consumer)

      CoordinatedShutdown(context.system).addJvmShutdownHook {
        consumer ! ConsumerShutdown
        metricsServer ! Stop
        Await.result(context.system.whenTerminated.map(_ => log.info("shutdown complete")), 1.minute)
      }

      Behaviors.same
    }
  ).onFailure(akka.actor.typed.SupervisorStrategy.stop)

  def loadSerde(className: String): LogEventSerde[Any] = {
    try {
      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
      val module = runtimeMirror.staticModule(className)
      runtimeMirror.reflectModule(module).instance.asInstanceOf[LogEventSerde[Any]]
    } catch {
      case e: Throwable =>
        log.error("failed to load kafka serde", e)
        sys.exit(1)
    }
  }

  def commonConsumerSettings[K, V](config: AppConfig, settings: ConsumerSettings[K, V]): ConsumerSettings[K, V] = {
    settings.withBootstrapServers(bootstrapServers = config.bootstrapServers)
      .withGroupId(config.consumerGroupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.offsetReset)
      .withStopTimeout(Duration.Zero)
  }

  def configureJsonPath(): Unit = {
    // jsonpath config
    Configuration.setDefaults(new Configuration.Defaults {
      override def jsonProvider(): JsonProvider = Json4sProvider

      override def options(): util.Set[jsonpath.Option] = Set(jsonpath.Option.SUPPRESS_EXCEPTIONS).asJava

      override def mappingProvider(): MappingProvider = new JacksonMappingProvider()
    })
  }

  def main(args: Array[String]): Unit = {
    val log = LoggerFactory.getLogger(getClass)
    log.info("starting sumo push...")

    configureJsonPath()

    val confFile = new File("/opt/docker/conf/application.conf")
    val config = if (confFile.exists() && confFile.canRead) {
      ConfigFactory.load()
        .withFallback(ConfigFactory.parseFile(confFile))
        .resolve()
    } else ConfigFactory.load()

    val dataType = SumoDataType.withName(config.getString("sumopush.dataType"))
    val appConfig = AppConfig(dataType, dataType match {
      case SumoDataType.logs =>
        config.withoutPath("endpoints.metrics")
      case SumoDataType.metrics =>
        config.withoutPath("endpoints.logs")
      case t => throw new UnsupportedOperationException(s"invalid data type $t")
    })

    ActorSystem(SumoPushMain(appConfig).behavior, "sumo-push-main", config)
  }
}