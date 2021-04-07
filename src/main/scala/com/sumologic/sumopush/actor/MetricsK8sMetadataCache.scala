package com.sumologic.sumopush.actor

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.google.common.cache.{Cache, CacheBuilder}
import com.sumologic.sumopush.actor.MetricConsumer.ParsedMetricMessage
import io.prometheus.client.Counter
import org.slf4j.{Logger, LoggerFactory}
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.sync._
import scalacache.memoization._
import skuber.{Pod, k8sInit}
import skuber.api.client.KubernetesClient
import skuber.json.format._

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object MetricsK8sMetadataCache {
  final val cache_attempts = Counter.build()
    .name("cache_attempts")
    .help("Total cache attempts")
    .labelNames("cache").register()
  final val cache_misses = Counter.build()
    .name("cache_misses")
    .help("Total cache misses")
    .labelNames("cache").register()
  final val cache_errors = Counter.build()
    .name("cache_errors")
    .help("Total cache errors")
    .labelNames("cache").register()

  sealed trait Message
  case class K8sMetaRequest(metricMessage: ParsedMetricMessage,
                            replyTo: ActorRef[ParsedMetricMessage]) extends Message
  case class K8sMetaResponse(podLabels: Map[String, String], podAnnotations: Map[String, String]) extends Message

  def apply(): Behavior[Message] = Behaviors.setup { ctx =>
    implicit val system: akka.actor.ActorSystem = ctx.system.toClassic
    implicit val dispatcher: ExecutionContextExecutor = system.dispatcher

    val podMetadataCache: Cache[String, Entry[Try[K8sMetaResponse]]] = CacheBuilder.newBuilder()
      .build[String, Entry[Try[K8sMetaResponse]]]
    implicit val k8sMetadataCache: GuavaCache[Try[K8sMetaResponse]] = GuavaCache(podMetadataCache)
    lazy val log: Logger = LoggerFactory.getLogger(getClass)

    def running(): Behaviors.Receive[Message] =
      Behaviors.receiveMessagePartial[Message] {
        case K8sMetaRequest(message, replyTo) =>
          cache_attempts.labels(getClass.getSimpleName).inc()
          val response = message.pme match {
            case Some(pme) =>
              if (pme.labels.contains("pod") && pme.labels.contains("namespace")) {
                val response = getCachedValue (pme.labels("namespace"), pme.labels("pod"))
                response match {
                  case Success(_) =>
                    message.copy (pme = Some (pme.copy (k8sMetadata = response.toOption) ) )
                  case _ =>
                    cache_errors.labels(getClass.getSimpleName).inc()
                    message
                }
              } else {
                message
              }
            case None =>
              message
          }
          replyTo ! response
          Behaviors.same
      }

    def getCachedValue(namespace: String, pod: String): Try[K8sMetaResponse] = memoizeSync(Some(1.hour)) {
      cache_misses.labels(getClass.getSimpleName).inc()
      val k8s: KubernetesClient = k8sInit
      val podMeta = k8s.getInNamespace[Pod](pod, namespace).map {
        pod => K8sMetaResponse(pod.metadata.labels, pod.metadata.annotations)
      }
      podMeta.onComplete {
        case Success(_) =>
          k8s.close
        case Failure(ex) =>
          log.warn("Unable to cache k8s metadata", ex)
          k8s.close
      }
      Try(Await.result(podMeta, 10.seconds))
    }
    running()
  }
}
