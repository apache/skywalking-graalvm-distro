# E2E Test Cases — GraalVM Native OAP

Applicable end-to-end test cases immigrated from upstream `skywalking/test/e2e-v2/cases/`.

**Constraints:**
- OAP runs as **native binary** (not JVM)
- Storage: **BanyanDB only**
- Cluster: **standalone** or **Kubernetes** (no Zookeeper, no etcd)

Upstream cases that use Elasticsearch/MySQL/PostgreSQL storage, or require
Zookeeper/etcd clustering, are excluded.

---

## Implemented

Every case below is in the CI matrix (`.github/workflows/ci.yml`) and runs against the native image.

| Case | Dir | Modules Tested |
|------|-----|----------------|
| Simple Java Agent | `simple-java-agent/` | Core tracing, metrics, topology (Java agent) |
| Istio ALS | `istio-als/` | Envoy ALS receiver, LAL input-type routing, K8s cluster |
| Event | `event/` | `receiver-event` |
| Alarm | `alarm/` | `alarm` module, webhook |
| Log | `log/` | `receiver-log`, LAL engine |
| Meter | `meter/` | `receiver-meter`, MAL (`meter-analyzer-config`), virtual cache/DB |
| Trace Profiling | `trace-profiling/` | Trace profiling lifecycle |
| VM Telegraf | `vm-telegraf/` | `receiver-telegraf` |
| VM Zabbix | `vm-zabbix/` | `receiver-zabbix` (disabled by default) |
| Zipkin | `zipkin/` | `receiver-zipkin`, `query-zipkin` (disabled by default) |
| PromQL | `promql/` | `promql` query API |
| LogQL | `logql/` | `logql` query API |
| TraceQL | `traceql/` | `traceQL` (Zipkin datasource) |
| Baseline | `baseline/` | Baseline prediction, alarm |
| Auth | `auth/` | gRPC token authentication |
| OTLP Traces | `otlp-traces/` | OTLP traces / metrics / logs |
| Virtual MQ | `virtual-mq/` | Virtual MQ layer (Kafka-instrumented) |
| Kafka Exporter | `exporter/` | Kafka exporter |
| SSL | `ssl/` | gRPC TLS (JDK SSL provider) |
| mTLS | `mtls/` | gRPC mutual TLS |
| RabbitMQ | `rabbitmq/` | RabbitMQ OTel rules |
| RocketMQ | `rocketmq/` | RocketMQ OTel rules |
| ActiveMQ | `activemq/` | ActiveMQ OTel rules |
| Pulsar | `pulsar/` | Pulsar / BookKeeper OTel rules |
| Kafka Monitoring | `kafka-monitoring/` | Kafka OTel rules |
| Redis | `redis/` | Redis OTel rules |
| MongoDB | `mongodb/` | MongoDB OTel rules |
| Flink | `flink/` | Flink OTel rules |
| AWS DynamoDB | `aws-dynamodb/` | AWS DynamoDB (Firehose) |
| AWS S3 | `aws-s3/` | AWS S3 (Firehose) |
| AWS EKS | `aws-eks/` | AWS EKS OTel rules |
| AWS API Gateway | `aws-api-gateway/` | AWS API Gateway (Firehose) |
| Self Observability | `so11y/` | OAP self-observability (Prometheus telemetry via OTel collector) |
| MQE | `mqe/` | Metrics Query Engine |
| Virtual GenAI | `virtual-genai/` | GenAI provider/model metrics (`gen-ai-analyzer`), LLM-as-judge evaluation (`ai-evaluation`, `gen-ai-model` MAL) |
| TraceQL SkyWalking | `traceql-skywalking/` | `traceQL` (SkyWalking datasource) |
| DSL Management | `dsl-management/` | Read-only DSL catalogs (`/runtime/rule/*`, `/runtime/oal/*`) + HTTP 501 for runtime-rule mutations, via curl and `swctl admin` |
| DSL Debugging | `dsl-debugging/` | SWIP-13 live debugger: status + session cycles on bundled MAL and LAL rules |
| Airflow | `airflow/` | Airflow layer OTel rules (upstream mock replay sender) |
| BanyanDB Self Observability | `banyandb-so11y/` | BanyanDB self-observability (SWIP-15: liaison + data node, endpoint + instance-relation rules) |
| DSL Debugging OAL | `dsl-debugging-oal/` | Upstream OAL live-debug flow (real samples on `service_relation_server_cpm`) |
| AI Agent Conversations | `ai-agent/` | `ai-agent-conversation` (AI Sessionizer files via `lal/ai-agent.yaml`, `asz.view` fold), `ai-agent/*` OTel rules |
| BanyanDB Trace Sampling | `banyandb-trace-sampling/` | `banyandb-trace-sampling` OTel rules (plugin-capable BanyanDB running the sampler chain) |
| BanyanDB Auth Rotation | `banyandb-auth-rotation/` | BanyanDB credential hot-reload (`secretsManagementFile`) + storage suite |
| BanyanDB CA Rotation | `banyandb-ca-rotation/` | BanyanDB TLS trust CA hot-reload + storage suite |

---

## Tier 1 — Core (no external deps beyond OAP + BanyanDB + Java test services)

| Case | Upstream Path | Cluster | Description |
|------|---------------|---------|-------------|
| Basic Java tracing | `simple/jdk/` | Standalone | Java agent + OAP + BanyanDB, traces, metrics, topology |
| Storage validation | `storage/banyandb/` | Standalone | BanyanDB CRUD, auth, topN queries |
| Alarm | `alarm/banyandb/` | Standalone | Alarm rule evaluation, searchable alarm tags |
| Trace profiling | `profiling/trace/banyandb/` | Standalone | Method-level trace profiling |
| Async profiling | `profiling/async-profiler/banyandb/` | Standalone | Async-profiler CPU/allocation profiling |
| Event | `event/banyandb/` | Standalone | Event storage and retrieval |
| Log analysis | `log/banyandb/` | Standalone | LAL log ingestion, filtering, trace correlation |
| Menu | `menu/banyandb/` | Standalone | GraphQL menu/dashboard metadata |
| Meter analysis | `meter/` | Standalone | MAL custom metrics (spring-micrometer, java-agent) |
| MQE | `mqe/` | Standalone | Metrics Query Engine expressions |
| PromQL | `promql/` | Standalone | Prometheus-compatible query language |
| Browser | `browser/` | Standalone | Browser/JS agent tracing |
| BanyanDB TLS | `storage/banyandb/tls/` | Standalone | TLS-encrypted OAP-to-BanyanDB communication |

## Tier 2 — Auth and TLS variants (Java agent, no extra infra)

| Case | Upstream Path | Cluster | Description |
|------|---------------|---------|-------------|
| gRPC auth | `simple/auth/` | Standalone | Token-based gRPC authentication |
| gRPC SSL | `simple/ssl/` | Standalone | SSL/TLS gRPC channel |
| gRPC mTLS | `simple/mtls/` | Standalone | Mutual TLS gRPC channel |

## Tier 3 — OTEL and monitoring integrations (OAP + BanyanDB + monitored system + OTEL Collector)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| OTLP traces | `otlp-traces/` | Standalone | OTEL Collector | OpenTelemetry traces, metrics, logs |
| Nginx monitoring | `nginx/` | Standalone | Nginx, Fluent Bit, OTEL Collector | Nginx metrics and error logs |
| ActiveMQ monitoring | `activemq/` | Standalone | ActiveMQ (x3), JMX Exporter, OTEL Collector | Broker/destination metrics |
| Elasticsearch monitoring | `elasticsearch/` | Standalone | Elasticsearch, ES Exporter, OTEL Collector | Cluster/node/index metrics |
| Flink monitoring | `flink/` | Standalone | Flink JobManager + TaskManagers, OTEL Collector | Job/TaskManager metrics |
| Kong monitoring | `kong/` | Standalone | Kong (x2), PostgreSQL, OTEL Collector | API Gateway metrics |
| MongoDB monitoring | `mongodb/` | Standalone | MongoDB (x2), JMX Exporter, OTEL Collector | Cluster/node metrics |
| Pulsar monitoring | `pulsar/` | Standalone | Pulsar (x4), Zookeeper, OTEL Collector | Broker/cluster metrics |
| RabbitMQ monitoring | `rabbitmq/` | Standalone | RabbitMQ, Perf Test (x8), OTEL Collector | Cluster/node metrics |
| RocketMQ monitoring | `rocketmq/` | Standalone | RocketMQ NameServer + Brokers, OTEL Collector | Broker/topic metrics |
| VM telegraf | `vm/telegraf/` | Standalone | Telegraf | VM metrics via Telegraf |
| VM node-exporter | `vm/prometheus-node-exporter/` | Standalone | Node Exporter, OTEL Collector | VM metrics via Prometheus |
| VM zabbix | `vm/zabbix/` | Standalone | Zabbix agent | VM metrics via Zabbix |

## Tier 4 — Multi-language agents (OAP + BanyanDB + language-specific agent)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| Go agent | `go/` | Standalone | Go agent | Go service tracing |
| Node.js agent | `nodejs/` | Standalone | Node.js agent | Node.js service tracing |
| PHP agent | `php/` | Standalone | PHP agent | PHP service tracing |
| Lua agent | `lua/` | Standalone | Lua agent | Lua/Nginx tracing |
| Go pprof profiling | `profiling/pprof/banyandb/` | Standalone | Go agent | Go pprof profiling |

## Tier 5 — Kafka integration (OAP + BanyanDB + Kafka)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| Kafka monitoring | `kafka/kafka-monitoring/` | Standalone | Kafka, Zookeeper, JMX Exporter, OTEL Collector | Broker/cluster metrics |
| Kafka log pipeline | `kafka/log/` | Standalone | Kafka, Zookeeper | Log ingestion via Kafka |
| Kafka meter pipeline | `kafka/meter/` | Standalone | Kafka, Zookeeper | Meter data via Kafka |
| Kafka profiling | `kafka/profile/` | Standalone | Kafka, Zookeeper | Profiling data via Kafka |
| Kafka SO11Y | `kafka/simple-so11y/` | Standalone | Kafka, Zookeeper | Self-observability via Kafka |
| Kafka exporter | `exporter/kafka/` | Standalone | Kafka, Zookeeper | Export traces/logs to Kafka |
| Virtual MQ | `virtual-mq/` | Standalone | Kafka, Zookeeper | Virtual message queue tracing |

## Tier 6 — Kubernetes-based (OAP + BanyanDB via Helm on Kind)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| eBPF access log | `profiling/ebpf/access_log/banyandb/` | K8s | Rover, Istio, bookinfo | Envoy access log profiling |
| eBPF continuous profiling | `profiling/ebpf/continuous/banyandb/` | K8s | Rover | Continuous CPU profiling |
| eBPF network profiling | `profiling/ebpf/network/banyandb/` | K8s | Rover, Nginx | Network socket profiling |
| eBPF on-CPU profiling | `profiling/ebpf/oncpu/banyandb/` | K8s | Rover | On-CPU flame graph |
| BanyanDB stages | `banyandb/stages/` | K8s | etcd, 3 BanyanDB data nodes | Hot/warm/cold data tiering |

## Tier 7 — Zipkin protocol (OAP + BanyanDB + Zipkin services)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| Zipkin tracing | `zipkin/banyandb/` | Standalone | Brave-instrumented services | Zipkin trace ingestion and query |

## Tier 8 — Baseline / AI (OAP + BanyanDB + baseline predictor)

| Case | Upstream Path | Cluster | External Deps | Description |
|------|---------------|---------|---------------|-------------|
| Baseline prediction | `baseline/banyandb/` | Standalone | baseline-predictor service | ML-based anomaly detection |

---

## Excluded — wrong storage backend

These upstream cases use Elasticsearch, MySQL, or PostgreSQL and are not applicable:

- `storage/elasticsearch/`, `storage/mysql/`, `storage/postgres/`
- `alarm/es/`, `alarm/mysql/`, `alarm/postgres/`
- `event/es/`, `event/mysql/`, `event/postgres/`
- `log/es/`, `log/mysql/`, `log/postgres/`
- `menu/es/`, `menu/mysql/`, `menu/postgres/`
- `profiling/trace/es/`, `profiling/trace/mysql/`, `profiling/trace/postgres/`
- `profiling/async-profiler/es/`, `profiling/async-profiler/mysql/`, `profiling/async-profiler/postgres/`
- `baseline/es/`, `baseline/mysql/`, `baseline/postgres/`
- `zipkin/es/`, `zipkin/mysql/`, `zipkin/postgres/`
- `ttl/es/`
- `logql/` (Elasticsearch)
- `cilium/` (Elasticsearch + K8s)
- `istio/metrics/`, `istio/als/`, `istio/ambient-als/` (Elasticsearch + K8s)

## Excluded — wrong cluster mode

- `cluster/zk/banyandb/` (Zookeeper clustering, not supported in this distro)
- `runtime-rule/*`, `dsl-debugging/{mal,lal-block,lal-statement}` (seed rules through runtime-rule hot-update, unsupported here); `inspect/*` (mounts a rule YAML at runtime)
- `gateway/` (Zookeeper-based 2-node OAP cluster)
- `so11y/` upstream variant (etcd-based clustering; the distro ships a standalone `so11y/` case instead)
- `satellite/native-protocols/` (etcd-based clustering)

## Excluded — other reasons

- `python/` (requires Kafka for agent reporting)
- `win/` (Windows-only)
- `aws/`, `apisix/`, `clickhouse/`, `mysql/`, `postgresql/`, `redis/` (no e2e.yaml or wrong storage)
- `mariadb/` (MySQL-based storage)
