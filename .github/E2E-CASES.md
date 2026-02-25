# E2E Test Cases — GraalVM Native OAP

Applicable end-to-end test cases immigrated from upstream `skywalking/test/e2e-v2/cases/`.

**Constraints:**
- OAP runs as **native binary** (not JVM)
- Storage: **BanyanDB only**
- Cluster: **standalone** or **Kubernetes** (no Zookeeper, no etcd)

Upstream cases that use Elasticsearch/MySQL/PostgreSQL storage, or require
Zookeeper/etcd clustering, are excluded.

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
- `gateway/` (Zookeeper-based 2-node OAP cluster)
- `so11y/` (etcd-based clustering)
- `satellite/native-protocols/` (etcd-based clustering)

## Excluded — other reasons

- `python/` (requires Kafka for agent reporting)
- `win/` (Windows-only)
- `aws/`, `apisix/`, `clickhouse/`, `mysql/`, `postgresql/`, `redis/` (no e2e.yaml or wrong storage)
- `mariadb/` (MySQL-based storage)
