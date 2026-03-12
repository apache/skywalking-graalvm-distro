# SkyWalking GraalVM Distro

**GraalVM native-image distribution of Apache SkyWalking OAP Server.**

A self-contained native binary of the SkyWalking OAP backend — faster startup,
lower memory, single-binary deployment. All your existing SkyWalking agents, UI,
and tooling work unchanged.

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
