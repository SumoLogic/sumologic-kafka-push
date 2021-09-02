package com.sumologic.sumopush

import com.sumologic.sumopush.model._
import com.typesafe.config.{Config, ConfigFactory}

import java.time.Instant

class PromMetricModelSpec extends BaseTest {
  val cfg: Config = ConfigFactory.load()
  val dataType: SumoDataType.Value = SumoDataType.withName(cfg.getString("sumopush.dataType"))
  val appConfig: AppConfig = AppConfig(dataType, cfg)

  "PromMetricEvent" should "serialize/deserialize properly" in {
    val promMetricJson =
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
        "name": "container_tasks_state",
        "timestamp": "2020-04-24T20:13:26Z",
        "value": "0"
      }
      """.stripMargin
    val pme = PromMetricEventSerializer.fromJson(promMetricJson)

    assert(pme.name == "container_tasks_state")
    assert(pme.timestamp == Instant.parse("2020-04-24T20:13:26Z"))
    assert(pme.value == "0")
    val whassup = pme.convertToLogLine(appConfig)
    System.out.println(whassup)
  }
}

object PushTest {
  def main(args: Array[String]): Unit = {
    //val json = Source.fromInputStream(getClass.getResourceAsStream("/log.json")).mkString
    //val message = LogSerializer.fromJson(json)
  }
}
