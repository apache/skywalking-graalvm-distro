# Supported Features

This distro packages the Apache SkyWalking OAP server as a GraalVM native image with a
**fixed module set**. All modules and providers are selected at build time — there is no
runtime SPI discovery or dynamic module loading.

## What's Included

### Storage

| Provider | Status |
|----------|--------|
| **BanyanDB** | Included (default) |
| Elasticsearch | Not included |
| H2 / MySQL / PostgreSQL / OpenSearch | Not included |

### Cluster

| Provider | Status |
|----------|--------|
| **Standalone** | Included (default) |
| **Kubernetes** | Included |
| ZooKeeper / Consul / Etcd / Nacos | Not included |

### Configuration

| Provider | Status |
|----------|--------|
| **none** | Included |
| **k8s-configmap** | Included (default) |
| Apollo / Consul / Etcd / Nacos / ZooKeeper / gRPC | Not included |

### Data Receivers

All receivers are included:

| Receiver | Protocol | Notes |
|----------|----------|-------|
| Trace (SkyWalking) | gRPC | Java, .NET, Go, Python, Node.js, PHP, Rust, Ruby agents |
| JVM Metrics | gRPC | Java agent JVM metrics |
| CLR Metrics | gRPC | .NET CLR metrics |
| Meter (SkyWalking) | gRPC | Custom meter protocol |
| Log | gRPC / HTTP | SkyWalking log protocol |
| Browser | gRPC | Browser JS agent |
| Event | gRPC | K8s events, custom events |
| Profile | gRPC | On-demand profiling |
| Async Profiler | gRPC | Java async-profiler |
| pprof | gRPC | Go pprof profiling |
| OpenTelemetry | gRPC (OTLP) | Metrics + Logs via OTLP |
| Envoy / Istio ALS | gRPC | Access log service, metrics |
| Zipkin | HTTP | Zipkin v2 spans (disabled by default) |
| Zabbix | TCP | Zabbix agent protocol (disabled by default) |
| Telegraf | gRPC | Telegraf metrics |
| AWS Firehose | HTTP | CloudWatch metrics via Firehose |
| Kafka Fetcher | Kafka | Traces, metrics, logs from Kafka (disabled by default) |
| Cilium Fetcher | gRPC | Hubble flow data (disabled by default) |
| eBPF | gRPC | eBPF profiling data (Rover) |

### Query APIs

| API | Port | Notes |
|-----|------|-------|
| **GraphQL** | 12800 | Primary query API for SkyWalking UI |
| **PromQL** | 9090 | Prometheus-compatible query |
| **LogQL** | 3100 | Loki-compatible log query |
| **Zipkin** | 9412 | Zipkin v2 query API (disabled by default) |
| **Status** | 12800 | Debugging and status queries |

### Analyzers

| Analyzer | Included |
|----------|----------|
| Trace Analyzer | Yes |
| Log Analyzer (LAL) | Yes |
| Event Analyzer | Yes |

### Other Modules

| Module | Status |
|--------|--------|
| Alarm | Included (webhook, gRPC hooks) |
| Telemetry | Prometheus (self-monitoring metrics on port 1234) |
| Exporter | Included (disabled by default) |
| Health Checker | Included |
| AI Pipeline | Included (baseline prediction) |

## Optional Modules

These modules are disabled by default. Enable them with environment variables:

| Module | Enable With |
|--------|-------------|
| Zipkin Receiver | `SW_RECEIVER_ZIPKIN=default` |
| Zipkin Query | `SW_QUERY_ZIPKIN=default` |
| Zabbix Receiver | `SW_RECEIVER_ZABBIX=default` |
| Kafka Fetcher | `SW_KAFKA_FETCHER=default` |
| Cilium Fetcher | `SW_CILIUM_FETCHER=default` |
| Exporter | `SW_EXPORTER=default` |

## Differences from Upstream SkyWalking

| Aspect | Upstream | This Distro |
|--------|----------|-------------|
| Runtime | JVM (JDK 11+) | GraalVM native image |
| Startup | Compiles DSL rules at boot (~30s) | Pre-compiled at build time (instant) |
| Binary | ~200MB+ of JARs + JVM | ~203MB self-contained binary |
| Storage | ES, BanyanDB, H2, MySQL, PG, ... | BanyanDB only |
| Cluster | ZK, K8s, Consul, Etcd, Nacos | Standalone, K8s |
| Config | All dynamic config providers | K8s ConfigMap or none |
| Module loading | SPI discovery at runtime | Fixed at build time |

## Compatibility

- **SkyWalking agents**: All official SkyWalking agents are compatible (Java, .NET, Go, Python, Node.js, PHP, Rust, Ruby, C++, Satellite)
- **SkyWalking UI**: Fully compatible — use the standard SkyWalking Booster UI
- **OpenTelemetry**: OTLP metrics and logs via gRPC
- **Zipkin**: Zipkin v2 API (receiver + query) when enabled
- **Prometheus**: PromQL query API for Grafana integration
- **BanyanDB**: Requires BanyanDB as the storage backend

## Architecture

```
                    ┌──────────────────────────────────┐
                    │   SkyWalking GraalVM Native OAP  │
Agents ──gRPC──────►│                                  │
OTLP ──gRPC────────►│  Receivers → Analyzers → Storage │──► BanyanDB
Zipkin ──HTTP──────►│                                  │
                    │  Query APIs (GraphQL/PromQL/...)  │◄── SkyWalking UI
                    │  Alarm → Webhooks                │
                    └──────────────────────────────────┘
```
