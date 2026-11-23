# Compiling from Source

This guide covers building the SkyWalking GraalVM Distro from source, producing both the
JVM distribution and the GraalVM native image binary.

## Prerequisites

- **GraalVM 25.5** (JDK 25) with `native-image` installed. The Makefile picks it from sdkman
  (`sdk install java 25.5.5.0+3-graal`) and warns when the GraalVM it uses is another release
- **Maven 3.9+** (the Maven wrapper `./mvnw` is included)
- **Docker** (for macOS cross-compilation and container packaging)
- **Git** (with submodule support; not required when building from Apache source tarball)

The Makefile picks the GraalVM itself: the newest sdkman `25.5*-graal` (Oracle before `-graalce`), else a GraalVM
`JAVA_HOME`, else the newest sdkman GraalVM 25. An exported `JAVA_HOME` therefore does not win over an installed
sdkman 25.5; to build with a specific installation, name it:

```bash
make GRAAL_HOME=/path/to/graalvm-25.5 build-distro
```

`./mvnw` invoked directly uses `JAVA_HOME` as usual.

## Building from Apache Source Tarball

If you are building from the official Apache source release tarball (no Git required):

```bash
tar -xzf apache-skywalking-graalvm-distro-<version>-src.tar.gz
cd apache-skywalking-graalvm-distro-<version>-src

# Install SkyWalking submodule artifacts to local Maven repo
cd skywalking && ../mvnw flatten:flatten install -DskipTests -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dgpg.skip=true
cd ..

# Build the distro
make build-distro

# Build the native image
make native-image
```

## Building from Git Repository

### Step 1: Clone the Repository

```bash
git clone --recurse-submodules https://github.com/apache/skywalking-graalvm-distro.git
cd skywalking-graalvm-distro
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

### Step 2: Install SkyWalking Submodule (First Time Only)

The upstream SkyWalking artifacts must be installed into your local Maven repository before
the distro modules can compile against them:

```bash
make init-skywalking
```

This runs `mvn install` on the `skywalking/` submodule. The result is cached in `~/.m2/repository`
and only needs to be re-run when the submodule is updated.

### Step 3: Build the JVM Distribution

```bash
make build-distro
```

This runs the complete build pipeline:

1. Compiles and installs the repackaged `*-for-graalvm` libraries
2. Runs the build-time precompiler (OAL class export, MAL/LAL transpilation, reflection metadata)
3. Packages the JVM distribution with assembly

Output:

```
oap-graalvm-server/target/oap-graalvm-jvm-distro/oap-graalvm-jvm-distro/   # exploded
oap-graalvm-server/target/oap-graalvm-jvm-distro.tar.gz                     # tarball
```

### Running the JVM Distribution Locally

Start BanyanDB and boot the OAP server:

```bash
make boot
```

This starts BanyanDB via Docker Compose and launches the JVM distribution with
`SW_STORAGE_BANYANDB_TARGETS=localhost:17912`.

To stop:

```bash
make shutdown
make docker-down
```

### Step 4: Build the Native Image

After `make build-distro` (or `make compile`), build the native binary:

```bash
make native-image
```

This invokes `native-maven-plugin` with the `-Pnative` profile. The native image build
takes several minutes and requires significant memory (~8GB RAM recommended).

Output:

```
oap-graalvm-native/target/oap-graalvm-native-*-native-dist/oap-native/     # exploded
oap-graalvm-native/target/oap-graalvm-native-*-native-dist.tar.gz          # tarball
```

The `oap-native/` directory contains:

```
oap-native/
├── oap-server          # ~203MB native binary
├── config/             # application.yml, alarm-settings.yml, log4j2.xml (console-only), etc.
└── compiled-rules/     # the rule files the image was compiled from (not read at runtime)
```

The binary reads its configuration from the `config/` directory next to it, whatever the working directory, and from
nothing else: without that directory it fails at once.

### Cross-Compiling for Linux on macOS

If you are on macOS and need a Linux native binary (e.g., for Docker):

```bash
make native-image-macos
```

This runs the `native-image` step inside a `ghcr.io/graalvm/native-image-community:25i5`
container (GraalVM Community 25.5; `make GRAALVM_IMAGE=<image>` picks another), producing a Linux binary while reusing your host's Maven cache and compiled classes.

### Step 5: Package as Docker Image

After building the native image:

```bash
make docker-native
```

This builds a Docker image `skywalking-oap-native:latest` based on `debian:bookworm-slim`
containing only the native binary and config files.

### Running with Docker Compose

```bash
docker compose -f docker/docker-compose.yml up
```

This starts BanyanDB and the OAP native image together. Ports exposed:

| Port | Service |
|------|---------|
| 12800 | REST API (GraphQL, health check) |
| 11800 | gRPC (agent data collection) |
| 17912 | BanyanDB |

## Other Make Targets

| Target | Description |
|--------|-------------|
| `make compile` | Compile and install to local repo (no tests) |
| `make test` | Run all tests (includes MAL/LAL comparison tests) |
| `make javadoc` | Validate javadoc correctness |
| `make dist` | Build JVM distro and print output path |
| `make native-dist` | Build native image and print output path |
| `make clean` | Clean all build artifacts |
| `make docker-up` | Start BanyanDB only (for local dev) |
| `make docker-down` | Stop BanyanDB |
| `make boot` | Build distro + start BanyanDB + boot OAP |
| `make shutdown` | Stop a running OAP server |
| `make verify-rules` | Compile the rules and run the tests that check them, without the native image |

## Compiling Your Own Rules

Every target that compiles takes `RULES_OVERLAY=<absolute path>`, a directory of your own rule files that is compiled
into the image with the bundled ones, and `M2_REPO=<dir>` to build with a Maven repository other than `~/.m2`. Rules
can't be selected or added at runtime. See [Compiling Your Own Rules](rules-overlay.md).

## CI Pipeline

The GitHub Actions CI (`.github/workflows/ci.yml`) automates the full pipeline on every push to `main`:

1. **License header check** — Apache SkyWalking Eyes
2. **Build & Test** — compile, javadoc, test, build-distro
3. **Native image** — builds on both `amd64` and `arm64` runners
4. **Docker image** — pushes multi-arch manifest to GHCR
5. **E2E tests** — runs end-to-end tests against the native image

See [Pre-Built Docker Images](docker-image.md) for using the CI-built images directly.
