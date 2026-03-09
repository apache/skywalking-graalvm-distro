# SkyWalking GraalVM Distro (Experimental)
<img src="http://skywalking.apache.org/assets/logo.svg" alt="Sky Walking logo" height="90px" align="right" />

SkyWalking GraalVM Distro is a re-distribution of the official Apache SkyWalking OAP server, targeting GraalVM native image on JDK 25.

This distro moves all dynamic code generation (Javassist, classpath scanning) from runtime to build time, producing a ~203MB native binary with full OAP feature set. No upstream source modifications required.

## Quick Start

```bash
git clone --recurse-submodules https://github.com/apache/skywalking-graalvm-distro.git
cd skywalking-graalvm-distro

# First time: install upstream SkyWalking to Maven cache
JAVA_HOME=/path/to/graalvm-jdk-25 make init-skywalking

# Build distro (precompiler + tests + server)
JAVA_HOME=/path/to/graalvm-jdk-25 make build-distro

# Build native image
JAVA_HOME=/path/to/graalvm-jdk-25 make native-image

# Run with Docker Compose (BanyanDB + OAP native)
docker compose -f docker/docker-compose.yml up
```

## Documentation

Full documentation is available in [docs/](docs/) and published on the project website.

## License

Apache 2.0
