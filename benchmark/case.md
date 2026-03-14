# SkyWalking GraalVM Distro Benchmark Case

## Objective

Verify the blog post claims on startup time and memory footprint reduction.
Additionally, compare CPU and memory under sustained traffic load.

## Images

| Component | Image |
|-----------|-------|
| BanyanDB | `ghcr.io/apache/skywalking-banyandb:e1ba421bd624727760c7a69c84c6fe55878fb526` |
| OAP (JVM) | `ghcr.io/apache/skywalking/oap:64a1795d8a582f2216f47bfe572b3ab649733c01-java21` |
| OAP (GraalVM) | `ghcr.io/apache/skywalking-graalvm-distro:0.1.0-rc1` |
| UI | `ghcr.io/apache/skywalking/ui:latest` |

## Benchmark Structure

```
benchmark/
├── case.md                              # this file
├── run.sh                               # main entry point
├── env                                  # image repos and versions
├── envs-setup/
│   ├── istio-cluster_oap-banyandb/      # JVM OAP (2 replicas)
│   │   ├── kind.yaml
│   │   ├── setup.sh
│   │   ├── traffic-gen.yaml             # 12 RPS
│   │   └── values.yaml
│   └── istio-cluster_graalvm-banyandb/  # GraalVM OAP (2 replicas)
│       ├── kind.yaml
│       ├── setup.sh
│       ├── traffic-gen.yaml             # 12 RPS
│       └── values.yaml
├── cases/
│   └── graalvm-resource-usage/          # CPU & memory comparison
│       └── run.sh
├── reports/                             # generated at runtime
├── docker-compose-jvm.yml              # simple local boot test
├── docker-compose-graalvm.yml          # simple local boot test
└── benchmark.sh                        # simple local boot/memory test
```

## Environments

Both environments deploy the same stack on a Kind cluster:
- **Istio** 1.25.2 with ALS enabled
- **BanyanDB** standalone storage
- **Bookinfo** sample app as workload
- **Traffic generator** at ~12 RPS against productpage
- **OAP** 2 replicas (cluster mode)

| Environment | OAP Image | Replicas |
|-------------|-----------|----------|
| `istio-cluster_oap-banyandb` | JVM (`ghcr.io/apache/skywalking/oap:64a1795d...`) | 2 |
| `istio-cluster_graalvm-banyandb` | GraalVM (`ghcr.io/apache/skywalking-graalvm-distro:0.1.0-rc1`) | 2 |

## Test Cases

### Case 1: Simple Boot Test (Docker Compose)

Local docker compose test — no K8s, no traffic. Measures pure OAP startup and idle memory.

**Cold boot**: Fresh BanyanDB (tables created on first connect).
**Warm boot**: Restart OAP only (tables already exist).

```bash
cd benchmark
./benchmark.sh 5 60    # 5 iterations, 60s idle wait
```

Uses `docker-compose-jvm.yml` and `docker-compose-graalvm.yml`.

### Case 2: Resource Usage Comparison (Kind + Istio)

Collects CPU (millicores) and memory (MiB) from OAP pods via `kubectl top`
under sustained 12 RPS traffic. Includes CPM validation.

```bash
# JVM
./benchmark/run.sh run istio-cluster_oap-banyandb graalvm-resource-usage

# GraalVM
./benchmark/run.sh run istio-cluster_graalvm-banyandb graalvm-resource-usage
```

**Configuration** (via env vars):
- `SAMPLE_COUNT=30` — number of samples (default)
- `SAMPLE_INTERVAL=10` — seconds between samples (default)
- `WARMUP_SECONDS=60` — warmup before sampling (default)

**Output**: `resource-usage.csv`, `resource-analysis.txt`, `environment.txt`, `metrics-round-*.yaml`, `metrics-final.yaml`

**Service metrics** (collected every 30s via swctl, all discovered services):
- `service_cpm` — calls per minute
- `service_resp_time` — response time (ms)
- `service_sla` — successful rate (basis points, 10000 = 100%)
- `service_apdex` — application performance index
- `service_percentile` — response time percentiles

**CPM Validation**: After sampling, checks that the entry service
(`productpage.default`) CPM is close to RPS × 60 = 720 (±30% tolerance).
A mismatch may indicate dropped requests or processing delays under load.

## How to Run

### Prerequisites
- Docker (4+ GB memory recommended)
- kind >= 0.30.0
- kubectl (within ±1 minor of K8s 1.34)
- Helm >= 3.12.0
- istioctl 1.25.2 (auto-downloaded if missing)
- swctl (for metrics collection)

### Full benchmark (recommended)

Run both environments with the resource-usage case:

```bash
# JVM OAP
./benchmark/run.sh run istio-cluster_oap-banyandb graalvm-resource-usage

# GraalVM OAP (run after JVM completes — shares Kind cluster name)
./benchmark/run.sh run istio-cluster_graalvm-banyandb graalvm-resource-usage
```

Each run creates a Kind cluster, deploys the full stack, runs the benchmark,
and tears down automatically.

### Quick local boot test

```bash
cd benchmark
./benchmark.sh 5 60
```

No K8s required — just Docker.

## Boot Test Results (Case 1)

Tested on 2026-03-14 with 3 iterations, 30s idle wait.

### Test Environment

| Item | Value |
|------|-------|
| Host | macOS 26.3.1, Apple M3 Max, 128 GB RAM, arm64 |
| Docker | Docker Desktop 28.4.0, 10 CPUs / 62.7 GB allocated |
| BanyanDB | `ghcr.io/apache/skywalking-banyandb:e1ba421bd624727760c7a69c84c6fe55878fb526` |
| OAP (JVM) | `ghcr.io/apache/skywalking/oap:64a1795d8a582f2216f47bfe572b3ab649733c01-java21` |
| OAP (GraalVM) | `ghcr.io/apache/skywalking-graalvm-distro:0.1.0-rc1` |

Boot time is measured from OAP's first application log timestamp to the
`listening on 11800` log line (gRPC server ready).

### Startup Time (ms)

| Run | JVM Cold | JVM Warm | GraalVM Cold | GraalVM Warm |
|-----|----------|----------|--------------|--------------|
| 1 | 635 | 634 | 5 | 6 |
| 2 | 709 | 630 | 5 | 4 |
| 3 | 630 | 629 | 5 | 5 |
| **Median** | **635** | **630** | **5** | **5** |

### Idle Memory (RSS, 5 samples per run at 10s intervals)

| Variant | Cold Boot Range | Warm Boot Range |
|---------|----------------|-----------------|
| JVM | 1.06 – 1.35 GiB | 1.22 – 1.52 GiB |
| GraalVM | 41.0 – 41.6 MiB | 41.0 – 42.0 MiB |

### Summary

| Metric | JVM OAP | GraalVM OAP | Delta |
|--------|---------|-------------|-------|
| Cold boot startup (median) | 635 ms | 5 ms | ~127x faster |
| Warm boot startup (median) | 630 ms | 5 ms | ~126x faster |
| Idle RSS | ~1.2 GiB | ~41 MiB | ~97% reduction |

## Resource Usage Under Load Results (Case 2)

Tested on 2026-03-14 on Kind + Istio 1.25.2 + Bookinfo at ~12 RPS.
30 samples at 10s intervals after 60s warmup. 2 OAP replicas (cluster mode).

### Test Environment

| Item | Value |
|------|-------|
| Host | macOS 26.3.1, Apple M3 Max, 128 GB RAM, arm64 |
| Docker | Docker Desktop 28.4.0, 10 CPUs / 62.7 GB allocated |
| K8s | Kind v0.31.0, Kubernetes v1.34.3 |
| Istio | 1.25.2 with ALS (k8s-mesh analyzer) |
| Workload | Bookinfo sample app, ~12 RPS via traffic generator |
| Storage | BanyanDB standalone |
| OAP replicas | 2 (cluster mode) |

### Per-Pod Summary

**JVM OAP:**

| Pod | CPU min | CPU max | CPU avg | CPU median | Mem min | Mem max | Mem avg | Mem median |
|-----|---------|---------|---------|------------|---------|---------|---------|------------|
| oap-8w4zr | 98m | 175m | 119m | 117m | 2088 MiB | 2118 MiB | 2106 MiB | 2102 MiB |
| oap-bvbrk | 80m | 108m | 95m | 95m | 2053 MiB | 2068 MiB | 2059 MiB | 2056 MiB |

**GraalVM OAP:**

| Pod | CPU min | CPU max | CPU avg | CPU median | Mem min | Mem max | Mem avg | Mem median |
|-----|---------|---------|---------|------------|---------|---------|---------|------------|
| oap-4v2k2 | 59m | 76m | 65m | 66m | 610 MiB | 676 MiB | 644 MiB | 648 MiB |
| oap-f78sv | 65m | 75m | 70m | 70m | 556 MiB | 643 MiB | 604 MiB | 597 MiB |

### Aggregate Summary

| Metric | JVM OAP | GraalVM OAP | Delta |
|--------|---------|-------------|-------|
| CPU median (millicores) | 101 | 68 | **-33%** |
| CPU avg (millicores) | 107 | 67 | **-37%** |
| Memory median (MiB) | 2068 | 629 | **-70%** |
| Memory avg (MiB) | 2082 | 624 | **-70%** |

### CPM Validation

| | Entry Service | CPM | Status |
|---|---|---|---|
| JVM | productpage.default | 365 | parity |
| GraalVM | productpage.default | 362 | parity |

Both variants report nearly identical CPM for the entry service, confirming
equivalent traffic processing capability. The value (~362) is lower than
the raw RPS × 60 = 720 because the mesh-layer CPM counts differ from
HTTP-level request counts.

### Service Metrics Collected

Metrics collected every 30s via swctl for all discovered services:
- `service_cpm` — calls per minute
- `service_resp_time` — response time (ms)
- `service_sla` — successful rate (10000 = 100%)
- `service_apdex` — application performance index
- `service_percentile` — response time percentiles

Final per-service snapshots are in `metrics-final.yaml`.
