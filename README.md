# sumologic-kafka-push

## Introduction
A containerized application for scalable, high-performance log and metric ingestion to the Sumo Logic collection API
from Kafka. May either be run in Kubernetes or Docker environments.

## Installation
The latest sumologic-kafka-push docker image is hosted in our public repository at `public.ecr.aws/sumologic/sumologic-kafka-push:0.3.13`
### Docker
A docker compose file is available on request.
### Kubernetes
We maintain a [helm chart](https://github.com/SumoLogic/sumologic-kafka-push/tree/main/helm) for running kafka-push in
kubernetes.

## Supported message formats
### JSON Logs
Arbitrary JSON log format is supported using `com.sumologic.sumopush.serde.JsonLogEventSerde`. Log payload, additional
metadata fields, and source name and category are all configurable using jsonpath expressions to reference fields in the
message. See `Endpoint configuration` below.

### Kubernetes Logs
Kubernetes logs are JSON format but follow a consistent field naming convention. An example:

```
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
    "pod_namespace": "test",
    "pod_uid": "beefbeef-beef-beef-aaa1-beefbeefbeef"
  },
  "log_start": "2020-05-29 18:02:37",
  "message": "2020-05-29 18:02:37.505199 I | http: TLS handshake error from 10.0.0.176:57946: remote error: tls: bad certificate\\nINFO             Round trip: GET https://172.1.0.1:443/api/v1/namespaces/test/pods?limit=500, code: 200, duration: 4.23751ms tls:version: 303 forward/fwd.go:196",
  "source_type": "kubernetes_logs",
  "stream": "stderr",
  "timestamp": "2020-05-29T18:02:37.505244795Z"
}
```
Thus, log payload is expected to have field name `message`, source name is set by default to `<pod_name>.<container_name>`

## Configuration
Configuration is generally made using the following environment variables:

| Name        | Description | Default |
| ----------- | ----------- | ------- |
| KAFKA_SERDE_CLASS_NAME    | Serde class used to deserialize json messages (`com.sumologic.sumopush.serde.KubernetesLogEventSerde` or `com.sumologic.sumopush.serde.JsonLogEventSerde`)    | com.sumologic.sumopush.serde.KubernetesLogEventSerde        |
| KAFKA_BOOTSTRAP_SERVERS   | Kafka bootstrap connect string        | localhost:9092 |
| KAFKA_TOPIC               | Kafka topic(s) to be consumed. This may be a regex (java [Pattern](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html)) to match multiple topics. | logs |
| KAFKA_CONSUMER_GROUP_ID   | Kafka consumer group                  | sumopush |
| KAFKA_AUTO_OFFSET_RESET   | Kafka auto offset reset setting       | earliest |
| CLUSTER_NAME              | Cluster name metadata field            | default |
| DATA_TYPE                 | Data type setting (logs or metrics)    | logs    |
| API_RETRY_INIT_DELAY      | Initial retry delay in milliseconds     | 2    |
| API_RETRY_DELAY_FACTOR    | Retry delay factor                      | 1.5 |
| API_RETRY_DELAY_MAX       | Retry delay max in milliseconds         | 120000 |
| METRICS_SERVER_PORT       | Metrics server port                     | 8080 |
| HTTP_ENCODING             | Encoding for api requests               | gzip |
| GROUPED_SIZE              | Message batch size for api requests     | 1000 |
| GROUPED_DURATION          | Maximum message batch duration          | 1s   |
| SEND_BUFFER               | Send buffer size                        | 10000 |
| KAFKA_CONSUMER_COMMIT_TIMEOUT | Kafka consumer commit timeout       | 30s |
| MAX_HTTP_CONNECTIONS      | Maximum size for http connection pool   | 50 |
| MAX_HTTP_OPEN_REQUESTS    | Maximum http connection pool open requests | 32 |
| KAFKA_MAX_BATCH_COMMIT    | Kafka max batch commit                   | 10000 |
| SUMO_LOGS_URI             | Sumo ingestion URI for logs              | N/A |
| SUMO_METRICS_URI          | Sumo ingestion URI for metrics           | N/A |

More complex configuration can be accomplished by mounting a json config file at `/opt/docker/conf/application.conf` in the
container.  Configuration from this file will be merged with the default at
`https://github.com/SumoLogic/sumologic-kafka-push/blob/main/src/main/resources/application.conf`.

### Endpoint configuration
For the generic JSON log format, source name and category settings as well as payload and metadata fields may be configured
on a per-endpoint basis using jsonOptions.  

Source name and category are set by default to the topic name the message is consumed from. Other options for these
fields are fixed and jsonpath.  Jsonpath allows these fields to be set to the value of a field in the message itself.
Fixed is a fixed string to be used for all messages.

An example:

```
endpoints: {
  logs: {
    uri: <ingestion uri>,
    default: true,
    namespaces: ["foo", "bar"],
    sourceName: "weblogs",
    jsonOptions: {
      sourceCategoryJsonPath: "$.sourceSystem",
      payloadJsonPath: "$.logMessage"
    }
  }
}
```

Supported configuration for endpoints:

| Name      | Description         |
| ---------- | ------------------ |
| uri       | URI address of sumo data ingestion endpoint |
| name      | Name of endpoint  |
| namespaces | Override default and return this endpoint if any of the namespaces in the list match the log event namespace (kubernetes only) |
| jsonOptions | Options for generic JSON logs (see below) |
| fieldName  | Log field name to match against.      |
| fieldPattern | Pattern to match against when selecting this endpoint. In the case of metrics, this matches against the metric name. For logs, this matches against the value of `fieldName`  |
| default    | Use this endpoint as the default in case no match is made |
| sourceCategory | Default source category | 
| sourceName | Default source name | 
| missingSrcCatStrategy | What to do if unable to determine source category or source name based on the configuration. `FallbackToTopic` (default) sets missing metadata to topic name, `Drop` logs the error and ignores the message |

Supported configuration for endpoint jsonOptions:

| Name      | Description       |
| ----------| ------------------|
| sourceCategoryJsonPath | Jsonpath to source category in message |
| sourceNameJsonPath     | Jsonpath to source name in message |
| fieldJsonPaths     | Map of field name to jsonpath for metadata fields |
| payloadWrapperKey  | Message key which contains the actual message  |
| payloadJsonPath    | Jsonpath to the log payload  |
| payloadText        | Sends the payload as raw text. The wrapper key will be ignored with this option. true or false  |

## Kubernetes configuration
Overrides are available in kubernetes using pod annotations. These settings take precedence over default or endpoint settings.
Any override settings can be applied on a per-container basis by appending `.<container name>` to the annotation.

| Annotation | Description     |
|------------|-----------------|
| sumologic.com/exclude | Exclude from data collection |
| sumologic.com/sourceCategory | Source category override |
| sumologic.com/ep | Endpoint name override |
| sumologic.com/format | Configure text/json format for sending data |
| sumologic.com/filter | Endpoint filter to send duplicate logs matching a regex for a specific container to another endpoint.  The syntax for using this feature is `sumologic.com/filter.<container name>.<endpoint name>` with the regex pattern to match in the annotation value. Endpoint name must match an endpoint in the configuration. |
| sumologic.com/sourceName | Source name override |

## For developers
First ensure docker and git are installed and configured and you have credentials to access the account containing the
public.ecr.aws/sumologic repository.
### Update Version
Update the sumologic-kafka-push application version by executing `make version RELEASE_VERSION=<new-version>`.
### Build Base Image
This is only necessary if the focal-corretto-11 base image hasn't been published to the public.ecr.aws/sumologic repository yet.  To build the base image, run
`make build-base`.
### Publish Base Image
This is only necessary if the focal-corretto-11 base image hasn't been published to the public.ecr.aws/sumologic repository yet.  To publish the base image, run
`make publish-base`.
### Build Docker Image
After updating the application version, build a new image by executing `make build-docker`.
### Publish Docker Image
After building a new docker image, publish it to the public.ecr.aws/sumologic repository by execting `make publish-docker`.
