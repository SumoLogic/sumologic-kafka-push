package com.sumologic.sumopush.actor

import io.prometheus.client.Counter

object MessageProcessor {
  val MetadataKeyPrefix = "sumologic.com"
  val ExcludeAnnotation = s"$MetadataKeyPrefix/exclude"
  val CategoryAnnotationPrefix = s"$MetadataKeyPrefix/sourceCategory"
  val EndpointAnnotationPrefix = s"$MetadataKeyPrefix/ep"
  val FormatAnnotationPrefix = s"$MetadataKeyPrefix/format"
  val EndpointFilterAnnotationPrefix = s"$MetadataKeyPrefix/filter"
  val SourceNameAnnotationPrefix = s"$MetadataKeyPrefix/sourceName"

  def registerStats(): Stats = {
    val messagesProcessed: Counter = Counter.build()
      .name("messages_processed")
      .help("Total messages processed")
      .labelNames("container", "sumo_endpoint").register()
    val messagesIgnored = Counter.build()
      .name("messages_ignored")
      .help("Total messages ignored")
      .labelNames("container").register()

    Stats(messagesProcessed, messagesIgnored)
  }

  case class Stats(messagesProcessed: Counter, messagesIgnored: Counter)
}

trait MessageProcessor {
  def findPodMetadataValue[V](metadataKeyPrefix: String, defaultValue: V, metadata: Map[String, String],
                                      container: String, validateOpt: Option[String => Boolean] = None,
                                      convertValueOpt: Option[String => V] = None): V = {
    val metadataKey = s"$metadataKeyPrefix.${container}"
    Seq(metadataKey, metadataKeyPrefix).view.flatMap { k => metadata.get(k) }
      .find(metadataValue => validateOpt match {
        case Some(validate) => validate(metadataValue)
        case _ => true
      }).map(metadataValue => convertValueOpt match {
      case Some(convertValue) => convertValue(metadataValue)
      case _ => metadataValue.asInstanceOf[V]
    }).getOrElse(defaultValue)
  }
}
