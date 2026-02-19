# MAL Comparison Test Coverage

Tracks which pre-compiled MAL YAML files have comparison tests and how many
metric expressions each covers.

## meter-analyzer-config/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| spring-micrometer.yaml | 23 | SpringMicrometerTest | auto-discovery |
| python-runtime.yaml | 9 | PythonRuntimeTest | auto-discovery |
| ruby-runtime.yaml | 13 | RubyRuntimeTest | auto-discovery |
| go-runtime.yaml | 31 | GoRuntimeTest | explicit (histogram) |
| go-agent.yaml | 7 | GoAgentTest | explicit (histogram) |
| java-agent.yaml | 7 | JavaAgentTest | explicit (histogram) |
| datasource.yaml | 1 | DatasourceTest | auto-discovery |
| continuous-profiling.yaml | 5 | ContinuousProfilingTest | auto-discovery |
| satellite.yaml | 8 | SatelliteTest | auto-discovery |
| threadpool.yaml | 1 | ThreadpoolTest | auto-discovery |
| network-profiling.yaml | 42 | NetworkProfilingTest | explicit (histogram, forEach) |

**Subtotal**: 147 expressions, 11/11 files covered

## otel-rules/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| activemq/activemq-broker.yaml | 21 | ActivemqBrokerTest | auto-discovery |
| activemq/activemq-cluster.yaml | 20 | ActivemqClusterTest | explicit (tagEqual) |
| activemq/activemq-destination.yaml | 15 | ActivemqDestinationTest | explicit (tagEqual) |
| apisix.yaml | 26 | ApisixTest | explicit (tagEqual, histogram) |
| aws-dynamodb/dynamodb-endpoint.yaml | 19 | DynamodbEndpointTest | explicit (tagEqual/tagMatch) |
| aws-dynamodb/dynamodb-service.yaml | 28 | DynamodbServiceTest | explicit (tagMatch) |
| aws-eks/eks-cluster.yaml | 8 | EksClusterTest | auto-discovery |
| aws-eks/eks-node.yaml | 16 | EksNodeTest | auto-discovery |
| aws-eks/eks-service.yaml | 6 | EksServiceTest | auto-discovery |
| aws-gateway/gateway-endpoint.yaml | 10 | GatewayEndpointTest | auto-discovery |
| aws-gateway/gateway-service.yaml | 10 | GatewayServiceTest | auto-discovery |
| aws-s3/s3-service.yaml | 10 | S3ServiceTest | auto-discovery |
| banyandb/banyandb-instance.yaml | 25 | BanyandbInstanceTest | explicit (tagMatch) |
| banyandb/banyandb-service.yaml | 25 | BanyandbServiceTest | explicit (tagMatch) |
| bookkeeper/bookkeeper-cluster.yaml | 10 | BookkeeperClusterTest | auto-discovery |
| bookkeeper/bookkeeper-node.yaml | 23 | BookkeeperNodeTest | auto-discovery |
| clickhouse/clickhouse-instance.yaml | 44 | ClickhouseInstanceTest | auto-discovery |
| clickhouse/clickhouse-service.yaml | 40 | ClickhouseServiceTest | auto-discovery |
| elasticsearch/elasticsearch-cluster.yaml | 14 | ElasticsearchClusterTest | explicit (tagNotEqual) |
| elasticsearch/elasticsearch-index.yaml | 43 | ElasticsearchIndexTest | explicit (tagNotEqual) |
| elasticsearch/elasticsearch-node.yaml | 49 | ElasticsearchNodeTest | explicit (tagNotEqual/tagEqual) |
| flink/flink-job.yaml | 15 | FlinkJobTest | auto-discovery |
| flink/flink-jobManager.yaml | 19 | FlinkJobManagerTest | auto-discovery |
| flink/flink-taskManager.yaml | 22 | FlinkTaskManagerTest | auto-discovery |
| istio-controlplane.yaml | 30 | IstioControlplaneTest | explicit (tagEqual, histogram) |
| k8s/k8s-cluster.yaml | 25 | K8sClusterTest | explicit (tagEqual, retagByK8sMeta) |
| k8s/k8s-instance.yaml | 1 | K8sInstanceTest | explicit (tagEqual, retagByK8sMeta) |
| k8s/k8s-node.yaml | 16 | K8sNodeTest | explicit (tagEqual) |
| k8s/k8s-service.yaml | 11 | K8sServiceTest | explicit (tagEqual, retagByK8sMeta) |
| kafka/kafka-broker.yaml | 27 | KafkaBrokerTest | explicit (tagMatch) |
| kafka/kafka-cluster.yaml | 8 | KafkaClusterTest | auto-discovery |
| kong/kong-endpoint.yaml | 5 | KongEndpointTest | explicit (tagMatch, histogram) |
| kong/kong-instance.yaml | 12 | KongInstanceTest | explicit (tagMatch, histogram) |
| kong/kong-service.yaml | 10 | KongServiceTest | explicit (histogram) |
| mongodb/mongodb-cluster.yaml | 13 | MongodbClusterTest | explicit (tagNotEqual/tagEqual) |
| mongodb/mongodb-node.yaml | 34 | MongodbNodeTest | explicit (tagEqual) |
| mysql/mysql-instance.yaml | 19 | MysqlInstanceTest | explicit (tagEqual/tagMatch) |
| mysql/mysql-service.yaml | 16 | MysqlServiceTest | explicit (tagEqual/tagMatch) |
| nginx/nginx-endpoint.yaml | 7 | NginxEndpointTest | explicit (tagMatch, histogram) |
| nginx/nginx-instance.yaml | 8 | NginxInstanceTest | explicit (tagMatch, histogram) |
| nginx/nginx-service.yaml | 8 | NginxServiceTest | explicit (tagMatch, histogram) |
| oap.yaml | 50 | OapTest | explicit (tagMatch, histogram) |
| postgresql/postgresql-instance.yaml | 32 | PostgresqlInstanceTest | explicit (tagEqual/tagMatch) |
| postgresql/postgresql-service.yaml | 23 | PostgresqlServiceTest | explicit (tagEqual/tagMatch) |
| pulsar/pulsar-broker.yaml | 16 | PulsarBrokerTest | auto-discovery |
| pulsar/pulsar-cluster.yaml | 12 | PulsarClusterTest | auto-discovery |
| rabbitmq/rabbitmq-cluster.yaml | 22 | RabbitmqClusterTest | auto-discovery |
| rabbitmq/rabbitmq-node.yaml | 22 | RabbitmqNodeTest | explicit (tagEqual) |
| redis/redis-instance.yaml | 14 | RedisInstanceTest | auto-discovery |
| redis/redis-service.yaml | 15 | RedisServiceTest | auto-discovery |
| rocketmq/rocketmq-broker.yaml | 4 | RocketmqBrokerTest | auto-discovery |
| rocketmq/rocketmq-cluster.yaml | 15 | RocketmqClusterTest | auto-discovery |
| rocketmq/rocketmq-topic.yaml | 11 | RocketmqTopicTest | auto-discovery |
| vm.yaml | 23 | VmTest | explicit (tagEqual) |
| windows.yaml | 12 | WindowsTest | explicit (tagMatch) |

**Subtotal**: 1044 expressions, 55/55 files covered

## log-mal-rules/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| nginx.yaml | 2 | LogNginxTest | auto-discovery |
| placeholder.yaml | 0 | — | skipped (empty) |

**Subtotal**: 2 expressions, 1/1 file covered (excluding placeholder)

## envoy-metrics-rules/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| envoy.yaml | 19 | EnvoyTest | explicit (tagMatch) |
| envoy-svc-relation.yaml | 7 | EnvoySvcRelationTest | explicit (tagMatch) |

**Subtotal**: 26 expressions, 2/2 files covered

## telegraf-rules/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| vm.yaml | 20 | TelegrafVmTest | explicit (tagEqual, rate/irate) |

**Subtotal**: 20 expressions, 1/1 file covered

## zabbix-rules/

| YAML File | Expressions | Test Class | Mode |
|---|---|---|---|
| agent.yaml | 15 | ZabbixAgentTest | explicit (tagEqual, custom YAML) |

**Subtotal**: 15 expressions, 1/1 file covered

## Staleness Detection

`MALYamlStalenessTest` compares SHA-256 hashes of all tracked YAML files
against `src/test/resources/mal-yaml-sha256.properties`. When a YAML file
changes (e.g. after submodule update), the test fails listing which files
need their test classes regenerated.

## Combination Pattern

Multiple YAML files from different data sources (otel, telegraf, zabbix) may
define metrics with the same name (e.g. `meter_vm_cpu_load1`). Within a single
file, duplicate metric names also occur (e.g. gateway-endpoint.yaml has two
`4xx` rules for HTTP API and REST API). These are intentional aggregation
combinations — all metrics flow into the meter system.

The precompiler assigns deterministic suffixes: first occurrence uses the plain
metric name, subsequent occurrences get `_1`, `_2`, etc. Expression SHA-256
hashes in `META-INF/mal-groovy-expression-hashes.txt` enable test resolution
of the correct pre-compiled class regardless of compilation order.

## Summary

| Category | Files | Expressions | Covered |
|---|---|---|---|
| meter-analyzer-config | 11 | 147 | 11 |
| otel-rules | 55 | 1044 | 55 |
| log-mal-rules | 1 | 2 | 1 |
| envoy-metrics-rules | 2 | 26 | 2 |
| telegraf-rules | 1 | 20 | 1 |
| zabbix-rules | 1 | 15 | 1 |
| **Total** | **71** | **1254** | **71** |
