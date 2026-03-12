# Quick Start

Get the SkyWalking GraalVM native OAP server running in under 5 minutes.

## Option 1: Docker Compose (Recommended)

Pull and start BanyanDB + OAP native server:

```bash
git clone https://github.com/apache/skywalking-graalvm-distro.git
cd skywalking-graalvm-distro

# Start BanyanDB + OAP native image
docker compose -f docker/docker-compose.yml up
```

The OAP server is ready when you see the module init table in logs. Verify:

```bash
curl http://localhost:12800/healthcheck
```

### Connect Your Application

Point your SkyWalking agent to the OAP server:

```bash
# Java agent example
java -javaagent:skywalking-agent.jar \
  -Dskywalking.collector.backend_service=localhost:11800 \
  -jar your-app.jar
```

Or send OpenTelemetry data:

```bash
# OTLP gRPC endpoint
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:11800
```

### Ports

| Port | Service |
|------|---------|
| 12800 | REST API (GraphQL query, health check, HTTP receivers) |
| 11800 | gRPC (agent data collection, OTLP) |
| 17912 | BanyanDB |

## Option 2: Docker Run

If you already have BanyanDB running:

```bash
docker run -d \
  -p 12800:12800 \
  -p 11800:11800 \
  -e SW_STORAGE_BANYANDB_TARGETS=<banyandb-host>:17912 \
  -e SW_CLUSTER=standalone \
  -e SW_CONFIGURATION=none \
  ghcr.io/apache/skywalking-graalvm-distro:latest
```

## Option 3: Build from Source

See [Compiling from Source](compiling.md) for full build instructions.

```bash
# Quick build + run
git clone --recurse-submodules https://github.com/apache/skywalking-graalvm-distro.git
cd skywalking-graalvm-distro
make init-skywalking    # first time only
make build-distro
make native-image
make docker-native
docker compose -f docker/docker-compose.yml up
```

## What's Next

- [Configuration](configuration.md) — all environment variables and module settings
- [Pre-Built Docker Images](docker-image.md) — image tags, architectures, GHCR details
- [Supported Features](supported-features.md) — what's included in this distro
