# SkyWalking GraalVM Distro

**GraalVM native-image distribution of Apache SkyWalking OAP Server.**

This project produces a self-contained native binary of the SkyWalking OAP backend.
It wraps the upstream [Apache SkyWalking](https://github.com/apache/skywalking) repository
as a git submodule and applies build-time transformations to eliminate all GraalVM-incompatible
patterns — without modifying upstream source code.

## Why Native Image

- **Fast startup** — native binary boots directly to full module initialization
- **Lower memory footprint** — no JIT compiler, no class-loading overhead
- **Single binary deployment** — ~203MB self-contained executable, ideal for containers and cloud-native environments

## How It Works

SkyWalking OAP relies on runtime code generation and dynamic class loading in several subsystems.
This distro moves all of that to build time:

| Subsystem | Runtime Pattern | Build-Time Solution |
|-----------|----------------|---------------------|
| **OAL** (metrics) | Javassist generates ~1285 classes at startup | `OALClassExporter` runs the OAL engine at build time, exports `.class` files and manifests |
| **MAL** (meters) | Groovy compiles 1250+ expressions at startup | `MalToJavaTranspiler` converts Groovy AST to pure Java `MalExpression` classes |
| **LAL** (logs) | Groovy compiles 10 scripts at startup | `LalToJavaTranspiler` converts Groovy AST to pure Java `LalExpression` classes |
| **Config loading** | `Field.setAccessible()` + reflection | `ConfigInitializerGenerator` produces setter-based `YamlConfigLoaderUtils` |
| **Classpath scanning** | Guava `ClassPath.from()` at startup | Build-time manifests under `META-INF/annotation-scan/` |
| **Module wiring** | ServiceLoader SPI discovery | `FixedModuleManager` with hardcoded module/provider construction |

All replacement classes use the **same-FQCN** (fully-qualified class name) technique: a replacement
class with the identical package and name is repackaged via `maven-shade-plugin`, excluding the
original from the upstream JAR. No classpath ordering tricks needed.

## Module Selection

The distro ships with a full feature set. Module/provider selection is fixed at build time:

- **Storage**: BanyanDB
- **Cluster**: Standalone, Kubernetes
- **Configuration**: Kubernetes
- **Receivers**: All (trace, JVM, meter, log, browser, OTel, mesh, Envoy, Zipkin, Zabbix, Telegraf, AWS Firehose, Cilium, eBPF, async-profiler, pprof, CLR, Kafka fetcher)
- **Analyzers**: Trace, Log, Event
- **Query**: GraphQL, PromQL, LogQL, Zipkin, Status
- **Alarm, Telemetry, Exporter, Health Check, AI Pipeline**: All enabled

See [Distribution Policy](distro-policy.md) for the complete module table and architecture details.

## Prerequisites

- **GraalVM JDK 25** with `native-image` installed
- **Maven 3.9+**
- **Docker** (for container builds and docker-compose)

## Build Commands

```bash
# Full build (precompiler + tests + JVM distro)
JAVA_HOME=$GRAALVM_HOME make build-distro

# Precompiler only
JAVA_HOME=$GRAALVM_HOME mvn -pl build-tools/precompiler install -DskipTests

# Run tests only
JAVA_HOME=$GRAALVM_HOME mvn -pl oap-graalvm-server test

# Native image
JAVA_HOME=$GRAALVM_HOME make native-image

# Docker native image (cross-compile on macOS)
make native-image-macos

# Package into Docker image
make docker-native

# Run with Docker Compose (BanyanDB + OAP native)
docker compose -f docker/docker-compose.yml up
```

## Project Structure

```
skywalking-graalvm-distro/
├── skywalking/              # Git submodule — apache/skywalking (read-only)
├── build-tools/
│   ├── precompiler/         # OAL + MAL + LAL build-time compilation
│   └── config-generator/    # Config code generator (YamlConfigLoaderUtils)
├── oap-libs-for-graalvm/    # Per-JAR same-FQCN replacement modules (shade plugin)
├── oap-graalvm-server/      # GraalVM-ready OAP server (JVM distro)
├── oap-graalvm-native/      # Native image build (native-maven-plugin)
├── docker/                  # Dockerfile.native + docker-compose.yml
└── docs/                    # Documentation
```

## Test Suites

All build-time transpilations are validated by dual-path comparison tests:

- **MAL**: 73 test classes, 1281 assertions — covers all 71 YAML rule files
- **LAL**: 5 test classes, 19 assertions — covers all 8 YAML rule files

Each test compiles the expression via both paths (fresh Groovy compilation vs pre-compiled Java class)
and asserts identical results. Tests require actual data flow — no vacuous empty-result agreements.

## Further Reading

- [Distribution Policy](distro-policy.md) — full module table, architecture constraints, build workflow
- [Compiling from Source](compiling.md) — build JVM distro, native image, and Docker image step by step
- [Pre-Built Docker Images](docker-image.md) — pull and run the CI-built native image from GHCR
- [Configuration](configuration.md) — all available settings, environment variables, and differences from upstream
- [OAL Pre-Compilation](oal-immigration.md) — Javassist class export, annotation scan manifests
- [MAL Transpilation](mal-immigration.md) — Groovy-to-Java transpiler, combination pattern, functional interfaces
- [LAL Transpilation](lal-immigration.md) — Groovy-to-Java transpiler, SHA-256 deduplication, spec class overloads
- [Config Initialization](config-init-immigration.md) — reflection-free config loading via generated setters
