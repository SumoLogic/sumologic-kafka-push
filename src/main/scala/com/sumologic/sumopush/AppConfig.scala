package com.sumologic.sumopush

import com.sumologic.sumopush.model.{PromMetricEvent, SumoDataType, SumoEndpoint, SumoEndpointSerializer}
import com.typesafe.config.{Config, ConfigRenderOptions}

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

object AppConfig {

  def apply(dataType: SumoDataType.Value, config: Config): AppConfig = {
    val kafkaConfig = config.getConfig("sumopush.kafka")
    val apiRetryConfig = config.getConfig("sumopush.apiRetry")
    val endpoints = createEndpoints(config.getConfig("endpoints"))
    AppConfig(serdeClass = kafkaConfig.getString("serdeClass"),
      bootstrapServers = kafkaConfig.getString("bootstrap.servers"),
      topic = kafkaConfig.getString("topic"),
      consumerGroupId = kafkaConfig.getString("groupId"),
      offsetReset = kafkaConfig.getString("auto.offset.reset"),
      host = if (config.hasPath("sumopush.host")) config.getString("sumopush.host") else InetAddress.getLocalHost.getHostName,
      endpoints = endpoints,
      sumoEndpoints = endpoints.flatMap(ep => ep.name.map(_ -> ep)).toMap,
      cluster = config.getString("sumopush.cluster"),
      dataType = dataType,
      groupedSize = config.getInt("sumopush.grouped.size"),
      groupedDuration = FiniteDuration(config.getDuration("sumopush.grouped.duration").toNanos, TimeUnit.NANOSECONDS),
      streamsMax = config.getInt("sumopush.streams.max"),
      initRetryDelay = apiRetryConfig.getInt("initDelay"),
      retryDelayFactor = apiRetryConfig.getDouble("delayFactor").toFloat,
      retryDelayMax = apiRetryConfig.getInt("delayMax"),
      retryMaxAttempts = apiRetryConfig.getInt("maxAttempts"),
      metricsServerPort = config.getInt("sumopush.metricsPort"),
      encoding = config.getString("sumopush.encoding"),
      containerExclusions = getConfigOverrides(config, "sumopush.container.exclusions"),
      endpointNameOverrides = getConfigOverrides(config, "sumopush.logs.endpoint.names"),
      sourceNameOverrides = getConfigOverrides(config, "sumopush.logs.source.names"),
      sourceCategoryOverrides = getConfigOverrides(config, "sumopush.logs.source.categories"))
  }

  private def getConfigOverrides(config: Config, key: String): Map[String, String] = {
    if (config.hasPath(key))
      config.getString(key).split(',').map(_.split(":")).collect { case Array(k, v) => (k, v) }.toMap
    else Map.empty
  }

  private def createEndpoints(endpoints: Config): List[SumoEndpoint] = {
    val endpointKeys = endpoints.entrySet().asScala.map { x => x.getKey.split("\\.").head }
    endpointKeys.map { x =>
      SumoEndpointSerializer.fromJson(
        endpoints.getConfig(x).root().render(ConfigRenderOptions.concise())).copy(name = Some(x))
    }.toList.sortBy(_.default)
  }
}

final case class AppConfig(serdeClass: String,
                           bootstrapServers: String,
                           topic: String,
                           consumerGroupId: String,
                           offsetReset: String,
                           host: String,
                           sumoEndpoints: Map[String, SumoEndpoint] = Map.empty,
                           cluster: String,
                           dataType: SumoDataType.Value,
                           groupedSize: Int,
                           groupedDuration: FiniteDuration,
                           streamsMax: Int,
                           initRetryDelay: Int = 2,
                           retryDelayFactor: Float = 1.5f,
                           retryDelayMax: Int = 120000,
                           retryMaxAttempts: Int = 10,
                           metricsServerPort: Int = 8080,
                           encoding: String,
                           containerExclusions: Map[String, String],
                           endpointNameOverrides: Map[String, String],
                           sourceNameOverrides: Map[String, String],
                           sourceCategoryOverrides: Map[String, String],
                           endpoints: List[SumoEndpoint]) {
  def getMetricEndpoint(promMetricEvent: PromMetricEvent): Option[SumoEndpoint] = {
    endpoints.find(_.matchesPromMetric(promMetricEvent))
  }
}