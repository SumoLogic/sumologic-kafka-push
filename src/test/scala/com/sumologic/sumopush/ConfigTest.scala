package com.sumologic.sumopush

import akka.http.scaladsl.model.{ContentTypes, Uri}
import com.sumologic.sumopush.actor.LogProcessor
import com.sumologic.sumopush.model.{KubernetesLogEventSerializer, PromMetricEventSerializer, SumoDataType}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should._


class ConfigTest extends AnyFlatSpec with Matchers {
  val cfgEndpoints: Config = ConfigFactory.load().withFallback(ConfigFactory.load("test-endpoints"))
  val cfg: Config = ConfigFactory.load()
  val dataType: SumoDataType.Value = SumoDataType.withName(cfg.getString("sumopush.dataType"))
  val appConfig: AppConfig = AppConfig(dataType, cfg)

  "config" should "load overlay properly" in {
    assert(cfgEndpoints.getString("sumopush.cluster") == "default")
    assert(cfgEndpoints.getString("sumopush.dataType") == "logs")
    assert(cfgEndpoints.getConfig("endpoints.sbulogs").entrySet.size == 2)
  }

  "config" should "return the proper endpoint using namespace" in {
    val epAppConfig = AppConfig(dataType, cfgEndpoints)
    val logEventJson =
      """
      {
        "file": "/var/log/pods/kute-test-0_5712test-244f-4743-aaa1-e8069a65test/logs/0.log",
        "kns": "test",
        "kubernetes": {
          "container_name": "test",
          "container_image": "docker/test",
          "pod_labels": {
            "app": "kube-test",
            "controller-revision-hash": "kube-test-7b78d5test",
            "statefulset.kubernetes.io/pod-name": "kube-test-0"
          },
          "pod_node_name": "ec2",
          "pod_name": "kube-test-0",
          "pod_namespace": "foo",
          "pod_uid": "beefbeef-beef-beef-aaa1-beefbeefbeef"
        },
        "log_start": "2020-05-29 18:02:37",
        "message": "2020-05-29 18:02:37.505199 I | http: TLS handshake error from 10.0.0.176:57946: remote error: tls: bad certificate\\nINFO             Round trip: GET https://172.1.0.1:443/api/v1/namespaces/test/pods?limit=500, code: 200, duration: 4.23751ms tls:version: 303 forward/fwd.go:196",
        "source_type": "kubernetes_logs",
        "stream": "stderr",
        "timestamp": "2020-05-29T18:02:37.505244795Z"
      }
      """.stripMargin
    val logEvent = KubernetesLogEventSerializer.fromJson(logEventJson)
    assert(LogProcessor.findEndpointName(epAppConfig, logEvent) == "sbulogs")
  }

  "config" should "load properly" in {
    assert(cfg.getString("sumopush.cluster") == "default")
    assert(cfg.getString("sumopush.dataType") == "logs")
    assert(cfg.getConfig("endpoints.metricsSecond").entrySet.size == 2)
  }

  "config" should "deserialize to AppConfig" in {
    assert(appConfig.cluster == "default")
    val first = appConfig.sumoEndpoints("metrics")
    val default = appConfig.sumoEndpoints.find { case (_, endpoint) => endpoint.default }.get._2
    assert(SumoDataType.logs == appConfig.dataType)
    assert(SumoDataType.logs.contentType == ContentTypes.`text/plain(UTF-8)`)
    assert(first.name.get == "metrics")
    assert(first.uri == Uri("http://sumologic.com/ingest/metrics"))
    assert(first.fieldPattern.get.toString == "container.+")
    assert(first.fieldName.contains("name"))
    assert(default.name.get == "logs")
    assert(default.uri == Uri("http://sumologic.com/ingest/logs"))
    assert(default.fieldName.isEmpty)
  }

  "config" should "retrieve matching endpoint for prom metric event" in {
    val promMetricJson =
      """
      {
        "labels": {
          "__name__": "container_tasks_state",
          "container": "argo-server",
          "container_name": "argo-server",
          "endpoint": "https-metrics",
          "id": "/kubepods/besteffort/podctest/test123",
          "image": "argoproj/argocli@sha256:testd126b3d2959f9535f70ftest4899d637740d2f41185214c133e9dcec526",
          "instance": "10.0.1.226:10110",
          "job": "kubelet",
          "metrics_path": "/metrics/cadvisor",
          "name": "k8s_argo-server_argo-server-test-test_0",
          "namespace": "argo",
          "node": "ip-10-0-1-226.us-west-23.compute.internal",
          "pod": "argo-server-test-test",
          "pod_name": "argo-server-test-test",
          "prometheus": "kube-monitoring/kube-prometheus",
          "prometheus_replica": "prometheus-kube-prometheus-0",
          "service": "kube-prometheus-kubelet",
          "state": "running"
        },
        "name": "container_tasks_state",
        "timestamp": "2020-04-24T20:13:26Z",
        "value": "0"
      }
      """.stripMargin
    val pme = PromMetricEventSerializer.fromJson(promMetricJson)
    val se = appConfig.getMetricEndpoint(pme)
    assert(se.map(_.name.contains("metrics")).isDefined)

    val promMetricJson2 =
      """
      {
        "labels": {
          "__name__": "blah_tasks_state",
          "container": "argo-server",
          "container_name": "argo-server",
          "endpoint": "https-metrics",
          "id": "/kubepods/besteffort/podctest/test123",
          "image": "argoproj/argocli@sha256:testd126b3d2959f9535f70ftest4899d637740d2f41185214c133e9dcec526",
          "instance": "10.0.1.226:10110",
          "job": "kubelet",
          "metrics_path": "/metrics/cadvisor",
          "name": "k8s_argo-server_argo-server-test-test_0",
          "namespace": "argo",
          "node": "ip-10-0-1-226.us-west-23.compute.internal",
          "pod": "argo-server-test-test",
          "pod_name": "argo-server-test-test",
          "prometheus": "kube-monitoring/kube-prometheus",
          "prometheus_replica": "prometheus-kube-prometheus-0",
          "service": "kube-prometheus-kubelet",
          "state": "running"
        },
        "name": "blah_tasks_state",
        "timestamp": "2020-04-24T20:13:26Z",
        "value": "0"
      }
      """.stripMargin
    val pme2 = PromMetricEventSerializer.fromJson(promMetricJson2)
    val se2 = appConfig.getMetricEndpoint(pme2)
    assert(se2.map(_.name.contains("metricsThird")).isDefined)
  }

  "appConfig" should "retrieve default endpoint for prom metric event" in {
    val missingMetricJson =
      """
      {
        "labels": {
          "__name__": "container_tasks_state",
          "container": "argo-server",
          "container_name": "argo-server",
          "endpoint": "https-metrics",
          "id": "/kubepods/besteffort/podctest/test123",
          "image": "argoproj/argocli@sha256:testd126b3d2959f9535f70ftest4899d637740d2f41185214c133e9dcec526",
          "instance": "10.0.1.226:10110",
          "job": "kubelet",
          "metrics_path": "/metrics/cadvisor",
          "name": "k8s_argo-server_argo-server-test-test_0",
          "namespace": "argo",
          "node": "ip-10-0-1-226.us-west-23.compute.internal",
          "pod": "argo-server-test-test",
          "pod_name": "argo-server-test-test",
          "prometheus": "kube-monitoring/kube-prometheus",
          "prometheus_replica": "prometheus-kube-prometheus-0",
          "service": "kube-prometheus-kubelet",
          "state": "running"
        },
        "name": "foo_tasks_state",
        "timestamp": "2020-04-24T20:13:26Z",
        "value": "0"
      }
      """.stripMargin
    val missingPme = PromMetricEventSerializer.fromJson(missingMetricJson)
    val se = appConfig.getMetricEndpoint(missingPme)
    assert(se.map(_.name.contains("metricsSecond")).isDefined)
  }
}
