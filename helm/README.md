# Sumo Logic kafka-push Helm Chart

This chart is used to deploy kafka-push in kubernetes and should be used
in conjunction with helm. 

## Installation
[Helm](https://helm.sh/) must be installed to use this chart. Please refer to Helm's [documentation](https://helm.sh/docs/)
to get started.

Once Helm is set up properly, add the repo as follows:
```
helm repo add sumologic-kafka-push https://sumologic.github.io/sumologic-kafka-push
```
You can then run `helm search repo sumologic-kafka-push` to see the charts.

## Configuration
This chart supports common application configurations via the values settings. If additional
configuration settings are needed this may be accomplished via mounting a secret specified by the
`endpointsSecret` value. This file is in json (hocon) format and will be merged with the default
[application.conf](https://github.com/SumoLogic/sumologic-kafka-push/blob/main/src/main/resources/application.conf).
This is particularly useful when configuring multiple endpoints or kafka consumer settings.

## Autoscaling
This chart supports autoscaling based cpu (hpa) or based on kafka lag metrics in
prometheus (keda). The default autoscaling is configured based on the resources cpu request/limit
as well as the cpu threshold.

Keda autoscaling is based on kafka lag metrics in prometheus
and is configured using the lag threshold in number of messages. Dependencies needed to use this
feature include: [keda](https://keda.sh/), [prometheus](https://github.com/prometheus-operator/prometheus-operator),
and [kafka lag exporter](https://github.com/lightbend/kafka-lag-exporter).

## Extra Volumes
Extra volumes may be specified using `extraVolumes/extraVolumeMounts`. May be used to mount binary truststore/certstore
 files stored in a secret.

####Example
To create secret: `kubectl create secret generic ssl-truststore --from-file=truststore.jks`
Values snippet:
```
extraVolumes:
  - name: truststore
    secret:
      defaultMode: 420
      secretName: ssl-truststore
extraVolumeMounts:
  - mountPath: /opt/ssl
    name: truststore
    readOnly: true 
```
Then reference the file `/opt/ssl/truststore.jks` file in your endpoints secret kafka configuration.

## Values
The `values.yaml` contains variables used to configure a deployment of this chart.

| Name | Description      | Default  |
|------|------------------|----------|
| image | The docker image to use | public.ecr.aws/sumologic/sumologic-kafka-push:latest |
| metricsPort | The port to expose prometheus metrics on | 8080 |
| logLevel | Logging level | warn |
| replicas | Desired replica count to deploy | 1 |
| dataType | Data type to process (logs or metrics) | logs |
| groupedSize | Message batch size to use for sending data | 30000 |
| groupedDuration | Batch timeout if `groupedSize` is not hit | 1s |
| endpointsSecret | Secret to mount containing endpoint and additional configuration | null |
| extraEnvVars | Extra environment variables to set in the push container | [] |
| extraVolumes | Extra volumes to mount in the push container | [] |
| extraVolumeMounts | Extra volume mounts in the push container | [] |
| cluster | Cluster metadata to attach to metrics | null |
| logs.uri | Sumo logic api uri for logs (required if using default endpoint config and logs data type) | null |
| metrics.uri | Sumo logic api uri for metrics (required if using default endpoint config and metrics data type) | null |
| kafka.bootstrapServers | Bootstrap server kafka configuration | localhost:9092 |
| kafka.serdeClass | Serde class for deserializing messages from kafka topic | default serde |
| kafka.topic | Kafka topic to read messages from | kafka-push-logs |
| kafka.consumerGroup | Kafka consumer group | kafka-push-logs |
| autoscale.type | Pod autoscaling mechanism (hpa or keda) | hpa |
| autoscale.minReplicaCount | Minimum replica count | 1 |
| autoscale.maxReplicaCount | Maximum replica count | 8 |
| autoscale.keda.ratePerSecond | Keda rate per second | 9000 |
| autoscale.keda.cooldownPeriod | Keda cooldown period | 1200 |
| autoscale.keda.pollingInterval | Metric polling interval in seconds | 30 |
| autoscale.keda.lagThreshold | Lag threshold to scale on | 90 |
| autoscale.keda.prometheus | Prometheus endpoint to scrape metrics from | http://localhost:9090 |
| autoscale.hpa.cpuThreshold | CPU threshold to scale on | 90 |
| resources.limits.cpu | CPU limit | 2 |
| resources.limits.memory | Memory limit | 2Gi |
| resources.requests.cpu | CPU request | 1 |
| resources.requests.memory | Memory request | 512Mi |
| servicemonitor.enabled | Deploy a servicemonitor for scraping metrics | true |
| servicemonitor.labels | Labels to append to servicemonitor for scraping metrics | {} |

## For Developers
#### Publish Chart
To publish a new chart, first ensure the main branch has the latest changes and Chart.yaml has been updated with the new
version/appVersion.
```
git checkout main && git pull && git checkout gh-pages && git rebase main
cd docs/
helm package ../helm
helm repo index . --merge index.yaml --url https://sumologic.github.io/sumologic-kafka-push/
git add . && git commit -m "Publish helm chart 0.x.x" && git push --force
```