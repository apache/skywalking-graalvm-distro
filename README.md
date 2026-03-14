# SkyWalking GraalVM Distro (Experimental)
<img src="http://skywalking.apache.org/assets/logo.svg" alt="Sky Walking logo" height="90px" align="right" />

[Apache SkyWalking](https://skywalking.apache.org/) is an open-source APM and observability platform for
distributed systems, providing metrics, tracing, logging, and profiling capabilities.

**SkyWalking GraalVM Distro** is a distribution of the same Apache SkyWalking OAP server, compiled as a
GraalVM native image on JDK 25. It moves all dynamic code generation (OAL, MAL, LAL, Hierarchy via
ANTLR4 + Javassist) and classpath scanning from runtime to build time, producing a ~203MB self-contained
native binary with the full OAP feature set. No upstream source modifications required.

### Key Differences from Upstream

- **Native binary** instead of JVM — instant startup, ~512MB memory footprint
- **BanyanDB only** — the sole supported storage backend
- **Fixed module set** — modules selected at build time, no SPI discovery
- **Pre-compiled DSL** — all DSL rules compiled at build time

All existing SkyWalking agents, UI, and tooling work unchanged.

### Quick Start

```bash
docker run -d \
  -p 12800:12800 \
  -p 11800:11800 \
  -e SW_STORAGE_BANYANDB_TARGETS=<banyandb-host>:17912 \
  apache/skywalking-graalvm-distro:latest
```

### Docker Images

| Registry | Image |
|----------|-------|
| Docker Hub | `apache/skywalking-graalvm-distro` |
| GHCR | `ghcr.io/apache/skywalking-graalvm-distro` |

Available for `linux/amd64` and `linux/arm64`. macOS arm64 (Apple Silicon) native binary is available on the [GitHub Release](https://github.com/apache/skywalking-graalvm-distro/releases) page.

## Documentation

Full documentation is available at [skywalking.apache.org/docs](https://skywalking.apache.org/docs/#ExperimentalGraalVMDistro).

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
