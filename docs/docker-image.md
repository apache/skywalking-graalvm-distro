# Pre-Built Docker Images

Pre-built multi-architecture Docker images are published to **GitHub Container Registry (GHCR)**
on every push to the `main` branch via CI.

## Image Repository

```
ghcr.io/apache/skywalking-graalvm-distro
```

GitHub Packages page: [https://github.com/apache/skywalking-graalvm-distro/pkgs/container/skywalking-graalvm-distro](https://github.com/apache/skywalking-graalvm-distro/pkgs/container/skywalking-graalvm-distro)

## Tags

| Tag | Description |
|-----|-------------|
| `latest` | Most recent build from the `main` branch |
| `<short-sha>` | Pinned to a specific commit (first 7 characters of the commit SHA) |

## Supported Architectures

Each tag is a multi-architecture manifest supporting:

- `linux/amd64`
- `linux/arm64`

Docker automatically selects the correct architecture for your platform.

## Pull the Image

```bash
docker pull ghcr.io/apache/skywalking-graalvm-distro:latest
```

Or pin to a specific commit:

```bash
docker pull ghcr.io/apache/skywalking-graalvm-distro:<short-sha>
```

## Run with Docker

### Standalone with BanyanDB

The simplest way is to use the included `docker-compose.yml`, replacing the locally built image
with the pre-built one:

```bash
# Clone the repo (for docker-compose.yml and config files only)
git clone https://github.com/apache/skywalking-graalvm-distro.git
cd skywalking-graalvm-distro

# Run with pre-built image
docker compose -f docker/docker-compose.yml up
```

Edit `docker/docker-compose.yml` to use the GHCR image instead of the local build:

```yaml
services:
  oap:
    image: ghcr.io/apache/skywalking-graalvm-distro:latest
    # ... rest of config unchanged
```

### Manual Docker Run

```bash
docker run -d \
  -p 12800:12800 \
  -p 11800:11800 \
  -e SW_STORAGE_BANYANDB_TARGETS=<banyandb-host>:17912 \
  -e SW_CLUSTER=standalone \
  -e SW_CONFIGURATION=none \
  ghcr.io/apache/skywalking-graalvm-distro:latest
```

## Environment Variables

All settings from [Configuration](configuration.md) are configurable via environment variables.
Common ones for Docker deployment:

| Variable | Description | Default |
|----------|-------------|---------|
| `SW_STORAGE_BANYANDB_TARGETS` | BanyanDB server address | `127.0.0.1:17912` |
| `SW_CLUSTER` | Cluster mode (`standalone` or `kubernetes`) | `standalone` |
| `SW_CONFIGURATION` | Dynamic config provider (`none` or `k8s-configmap`) | `k8s-configmap` |
| `SW_CORE_REST_PORT` | REST API port | `12800` |
| `SW_CORE_GRPC_PORT` | gRPC port | `11800` |
| `SW_TELEMETRY_PROMETHEUS_PORT` | Prometheus metrics port | `1234` |
| `SW_LOG_LEVEL` | Log4j2 log level | `INFO` |
| `JAVA_OPTS` | Additional flags passed to the native binary (e.g., `-Dmode=init`) | *(empty)* |

## Exposed Ports

| Port | Protocol | Service |
|------|----------|---------|
| 12800 | HTTP | REST API — GraphQL query, health check, HTTP data receivers |
| 11800 | gRPC | Agent data collection — traces, metrics, logs, profiling |
| 1234 | HTTP | Prometheus self-telemetry metrics |

## Image Details

- **Base image**: `debian:bookworm-slim`
- **Contents**: Native binary (`oap-server`, ~203MB) + `config/` directory
- **Entrypoint**: `./oap-server` with `JAVA_OPTS` forwarded as command-line arguments
- **No JVM**: The image does not contain a JVM — the binary is a self-contained GraalVM native image
