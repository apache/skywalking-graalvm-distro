# FAQ

## General

### How is this different from upstream Apache SkyWalking?

This is a **distribution** of the same Apache SkyWalking OAP server, compiled as a GraalVM
native image. The observability features are identical. The differences are:

- **Native binary** instead of JVM — faster startup, lower memory
- **BanyanDB only** — no Elasticsearch/H2/MySQL/PostgreSQL support
- **Fixed module set** — modules are selected at build time, not via SPI
- **No runtime code generation** — all DSL compilation happens at build time

Your existing SkyWalking agents, UI, and tooling work unchanged.

### Can I use Elasticsearch as storage?

No. This distro only supports BanyanDB. The Elasticsearch storage plugin requires
dynamic class loading patterns that are incompatible with GraalVM native image.

If you need Elasticsearch, use the standard upstream SkyWalking distribution.

### Can I use the standard SkyWalking UI?

Yes. The SkyWalking Booster UI connects to the OAP server via the same GraphQL API
on port 12800. No changes needed.

### What agents are compatible?

All official SkyWalking agents work with this distro — they communicate via the same
gRPC/HTTP protocols. OpenTelemetry agents sending OTLP data also work.

### Can I enable TLS/SSL for gRPC?

No. The native image does not support TLS-encrypted gRPC (`SW_CORE_GRPC_SSL_ENABLED`,
`SW_RECEIVER_GRPC_SSL_ENABLED`, mTLS). The underlying Netty TLS implementation requires
`netty_tcnative` native libraries that are not bundled in the native image.

**Recommended alternative**: Deploy with a service mesh such as **Istio** or **Linkerd**.
The mesh handles mTLS transparently at the infrastructure layer — all agent-to-OAP traffic
is encrypted without any application-level TLS configuration. This also provides automatic
certificate rotation and policy enforcement.

If you require application-level TLS without a service mesh, use the standard upstream
SkyWalking JVM distribution.

---

## Deployment

### What are the system requirements?

- **CPU**: amd64 or arm64
- **Memory**: ~512MB minimum, 1GB+ recommended for production
- **Storage**: BanyanDB instance (can run on same host or separately)
- **OS**: Linux (native binary). Docker images based on `debian:bookworm-slim`

### How do I run in Kubernetes?

1. Deploy BanyanDB (see [BanyanDB docs](https://skywalking.apache.org/docs/skywalking-banyandb/latest/readme/))
2. Deploy the OAP native image with:
   ```yaml
   env:
     - name: SW_STORAGE_BANYANDB_TARGETS
       value: "banyandb:17912"
     - name: SW_CLUSTER
       value: "kubernetes"
     - name: SW_CLUSTER_K8S_NAMESPACE
       value: "skywalking"
   ```
3. Configure health checks on port 12800 (`/healthcheck`)

### How do I enable Zipkin support?

Set two environment variables:

```bash
SW_RECEIVER_ZIPKIN=default   # Zipkin span receiver on port 9411
SW_QUERY_ZIPKIN=default      # Zipkin query API on port 9412
```

### How do I configure alarms?

Mount a custom `alarm-settings.yml` into the container at `/skywalking/config/alarm-settings.yml`.
The alarm configuration format is the same as upstream SkyWalking.
See [Alarm documentation](https://skywalking.apache.org/docs/main/next/en/setup/backend/backend-alarm/).

---

## Troubleshooting

### The native binary fails to start

Check for these common issues:

1. **BanyanDB not reachable**: Ensure `SW_STORAGE_BANYANDB_TARGETS` points to a running BanyanDB.
   The OAP server will retry but may fail if BanyanDB is not available during schema installation.

2. **Port conflicts**: The default ports (12800, 11800) may be in use. Check with `netstat` or `ss`.

3. **Insufficient memory**: The native binary needs at least ~400MB of heap. If running in a
   container with tight limits, increase the memory allocation.

### Logs show "Unknown config type" warnings

This means an environment variable is set for a module that isn't included in the distro.
For example, setting `SW_STORAGE=elasticsearch` will produce a warning because
Elasticsearch isn't available. Check [Supported Features](supported-features.md) for
the list of included modules.

### How do I change the log level?

Set the `SW_LOG_LEVEL` environment variable:

```bash
SW_LOG_LEVEL=DEBUG   # Options: TRACE, DEBUG, INFO, WARN, ERROR
```

The native image uses console-only logging (no file rotation).

---

## Building

### Do I need GraalVM to use this distro?

No. Pre-built Docker images are available at `ghcr.io/apache/skywalking-graalvm-distro`.
GraalVM is only needed if you want to build from source.

### What GraalVM version is required for building?

GraalVM JDK 25 with `native-image` installed. The Makefile auto-detects it via sdkman
or `JAVA_HOME`.

### The native image build runs out of memory

The native image build requires ~8GB RAM. Set Maven memory limits:

```bash
export MAVEN_OPTS="-Xmx4g"
make native-image
```

### How do I cross-compile for Linux on macOS?

```bash
make native-image-macos
```

This runs the native-image build inside a Docker container, producing a Linux binary
while reusing your host Maven cache.
