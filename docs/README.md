# SkyWalking GraalVM Distro

**GraalVM native-image distribution of [Apache SkyWalking](https://skywalking.apache.org/) OAP Server.**

[Apache SkyWalking](https://skywalking.apache.org/) is an open-source APM and observability platform for
distributed systems, providing metrics, tracing, logging, and profiling capabilities.
The standard SkyWalking OAP server runs on the JVM with runtime code generation and dynamic module loading.

This project produces a **GraalVM native-image build** of the same OAP server — a self-contained binary
with faster startup, lower memory footprint, and single-file deployment. It wraps the upstream
SkyWalking repository as a git submodule and moves all runtime code generation to build time,
without modifying upstream source code.

All your existing SkyWalking agents, UI, and tooling work unchanged. The observability features
are identical — the differences are in how the server is built and deployed:

- **Native binary** instead of JVM — instant startup, ~512MB memory
- **BanyanDB only** — the sole supported storage backend
- **Fixed module set** — modules selected at build time, no SPI discovery
- **Pre-compiled DSL** — OAL, MAL, LAL, and Hierarchy rules compiled at build time

Official documentation is published at [skywalking.apache.org/docs](https://skywalking.apache.org/docs/#ExperimentalGraalVMDistro).

## For Users

- [Quick Start](quick-start.md) — get running in under 5 minutes
- [Supported Features](supported-features.md) — what's included, optional modules, compatibility
- [Configuration](configuration.md) — environment variables and module settings
- [Pre-Built Docker Images](docker-image.md) — pull and run from GHCR
- [FAQ](faq.md) — common questions and troubleshooting

## For Contributors

- [Compiling from Source](compiling.md) — build JVM distro, native image, and Docker image
- [Distribution Policy](distro-policy.md) — module table, architecture constraints, build workflow

### Build-Time Internals

These docs describe how the distro moves runtime code generation to build time:

- [DSL Pre-Compilation](internals/dsl-immigration.md) — unified OAL/MAL/LAL/Hierarchy build-time compilation via ANTLR4 + Javassist v2
- [OAL Pre-Compilation](internals/oal-immigration.md) — OAL-specific details: Javassist class export, annotation scan manifests
- [Config Initialization](internals/config-init-immigration.md) — reflection-free config loading via generated setters

## Why Native Image

- **Fast startup** — native binary boots directly to full module initialization
- **Lower memory footprint** — no JIT compiler, no class-loading overhead
- **Single binary deployment** — ~203MB self-contained executable, ideal for containers

## Project Structure

```
skywalking-graalvm-distro/
├── skywalking/              # Git submodule — apache/skywalking (read-only)
├── build-tools/
│   ├── precompiler/         # OAL + MAL + LAL + Hierarchy build-time compilation
│   └── config-generator/    # Config code generator (YamlConfigLoaderUtils)
├── oap-libs-for-graalvm/    # Per-JAR same-FQCN replacement modules (shade plugin)
├── oap-graalvm-server/      # GraalVM-ready OAP server (JVM distro)
├── oap-graalvm-native/      # Native image build (native-maven-plugin)
├── docker/                  # Dockerfile.native + docker-compose.yml
└── docs/                    # Documentation
```
