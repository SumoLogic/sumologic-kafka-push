package com.sumologic.sumopush.model

trait LogEvent[+T] {
  val message: T
}

trait ContainerLogEvent extends LogEvent[String] {
  val timestamp: Option[Long]
  val metadata: KubernetesLogEventMetadata
}

trait KubernetesLogEventMetadata

trait PodMetadata extends KubernetesLogEventMetadata {
  val name: String
  val namespace: String
  val nodeName: String
  val container: String
  val containerImage: Option[String]
  val annotations: Map[String, String]
  val labels: Map[String, String]
}
