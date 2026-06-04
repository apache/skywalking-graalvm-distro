# Changes

## 0.4.0

### Upstream Sync

- Sync SkyWalking submodule to upstream `89624809f0` (11.0.0-SNAPSHOT).
- Wire the admin-server family on the admin host (HTTP `:17128`, admin-internal gRPC `:17129`): `admin-server`, `status` (relocated from the 10.x `status-query` plugin — `/status/*`, `/debugging/*`), `inspect` (SWIP-14 metric catalog, `/inspect/*`), and `ui-management` (dashboard-template REST for the Horizon UI, `/ui-management/*`). The `status-query` plugin is removed.
- Enable iOS (SWIP-11) and mini-program (SWIP-12) OTel metrics rules and Envoy AI Gateway MCP rules; add the mini-program log-MAL rules and the `ai_route_type` searchable log tag.
- Drop the bundled UI templates: upstream 11.0.0 removed `ui-initialized-templates/` and `UITemplateInitializer`; templates are now managed via `ui-management`.

### GraalVM Native Image Compatibility

- DSL Live Debugger (SWIP-13, `/dsl-debugging/*`) and runtime rule hot-update (`/runtime/*`) are unsupported in the native image — both need runtime Javassist codegen — so a stub module answers those prefixes with HTTP 501.
- `YamlConfigLoaderUtils` and `ConfigInitializerGenerator` now emit a no-op dispatch branch for empty `ModuleConfig` types (`InspectModuleConfig`, `UIManagementModuleConfig`), so module boot no longer throws on the relocated empty configs.
- Port the new upstream runtime-rule DSL overloads into the same-FQCN replacements: meter `DSL.parse(..., ClassPool, ClassLoader)`, `FilterExpression(..., ClassPool, ClassLoader)`, and log `DSL.of(..., ClassPool, ClassLoader)`.
- The precompiler and the MAL comparison tests now compose expressions via the real upstream `MetricConvert.formatExp` (ANTLR `injectExpPrefix`) instead of a hand-rolled replica, fixing pre-compiled class lookup for chained expressions (`.sum` / `.rate` / `.downsampling`).
- Register protobuf descriptor **editions** classes (`com.google.protobuf.DescriptorProtos$*`, incl. `FeatureSet`) and **protoc-gen-validate** classes (`io.envoyproxy.pgv.validate.Validate$*`) for native-image reflection. protobuf-java 4.33 (pulled by the sync) reflects on these when parsing the BanyanDB measure descriptors (editions features + field-validation options); without the metadata, BanyanDB metrics queries failed at runtime with `Generated message class ... missing method` (e.g. `FeatureSet.getFieldPresence`).
- Register `UnsupportedAdminFeatureHandler` for native-image reflection in `reachability-metadata.json`. Armeria builds its annotated routes by reflection, but this distro-only 501 stub is not on the build-time precompiler's classpath (it depends on the precompiler output), so its routes were never registered — the DSL live debugger (`/dsl-debugging/*`) and runtime-rule (`/runtime/*`) prefixes returned 404 instead of the intended 501. Caught by the new `live-debugging` e2e.

### Documentation

- Document the admin-server family, the status relocation, and the unsupported DSL-debugging / runtime-rule features (HTTP 501) in `distro-policy.md` and `supported-features.md`; add the `0.4.0` → `89624809f0` row to `version-mapping.md`.

### Testing

- Add MAL comparison tests for the mini-program log-MAL rules and LAL pre-compilation tests for the iOS MetricKit and mini-program rules; track the new iOS / mini-program / Envoy AI Gateway MCP YAML in the precompiled-YAML staleness baseline.

### E2E Tests

- Bump pinned dependency images for the 11.0.0 sync: BanyanDB `84b919e`, Kubernetes `da0e267`, e2e java-test-service `7754e3e`.
- Bump `skywalking-infra-e2e` to upstream's pin (`0d917694`) — the synced e2e expected-output templates use the `containsOnce` verify function, which the prior pin predated.
- Remove the `menu` e2e case (CI matrix + wrapper): upstream dropped the bundled UI in 11.0.0 (#13877), deleting `test/e2e-v2/cases/menu/`, so the distro wrapper referenced a non-existent reuse file.
- Add the `live-debugging` e2e case: boots the native OAP and asserts the admin-server (`:17128`) returns the structured HTTP 501 for the DSL live debugger (`/dsl-debugging/*`) and runtime-rule (`/runtime/*`) endpoints. The first e2e to exercise the admin port — it caught the missing reflection registration noted above (the stub had been returning 404).
- Adopt the upstream **swctl admin** migration (#13889): bump the pinned `skywalking-cli` (`SW_CTL_COMMIT`) to the admin-capable commit (`b447211a`) and drive the `live-debugging` admin checks through `swctl admin dsl-debug` / `runtime-rule` / `oal` alongside the existing raw-curl contract checks — verifying that the official CLI surfaces this distro's structured HTTP 501 (`feature_not_available_in_graalvm_native`) for both the DSL live debugger and runtime-rule roots, rather than crashing or reporting a confusing 404.

## 0.3.0

### Upstream Sync

- Sync SkyWalking submodule to upstream v10.4.0 release tag.
- Add `gen-ai-analyzer` module: GenAI provider/model metrics from virtual-gen-ai.oal.
- Add Envoy AI Gateway MAL/LAL rules and config.
- Add TraceQL config properties: `lookback`, `zipkinTracesListResultTags`, `skywalkingTracesListResultTags`.

### GraalVM Native Image Compatibility

- Add `library-server-for-graalvm`: replace `DynamicSslContext` to use `SslProvider.JDK` instead of `SslProvider.OPENSSL`, enabling gRPC TLS in native images without `netty_tcnative`.

### Documentation

- Document TLS/SSL limitation: native image lacks `netty_tcnative`, recommend service mesh for mTLS.

### E2E Tests

- Add SSL e2e test case (gRPC TLS with JDK SSL provider in native image).
- Add mTLS e2e test case (mutual TLS with client certificates).
- Add RabbitMQ, RocketMQ, ActiveMQ, Pulsar, Kafka, Redis, MongoDB, Flink monitoring e2e test cases (OTEL metrics collection).
- Add AWS DynamoDB, S3, EKS, API Gateway e2e test cases (mock sender metrics).
- Add Auth e2e test case (token-based agent-to-OAP authentication).
- Add OTLP Traces e2e test case (OpenTelemetry trace ingestion via Zipkin API).
- Add Virtual MQ e2e test case (Kafka-instrumented virtual MQ layer metrics).
- Add Kafka Exporter e2e test case (trace and log export to Kafka).
- Add Virtual GenAI e2e test case (GenAI provider/model metrics via Spring AI + Java agent).
- Add Envoy AI Gateway e2e test case (ENVOY_AI_GATEWAY layer metrics/logs via OTLP).
- Add TraceQL SkyWalking e2e test case (Tempo API with SkyWalking native trace datasource).
- Add Envoy AI Gateway MAL comparison tests (34 tests for gateway-service and gateway-instance rules).
- Add Self-Observability e2e test case (OAP Prometheus telemetry via OTEL collector).
- Add MQE e2e test case (Metrics Query Engine expression evaluation with baseline).

## 0.2.1

### Build

- Fix `version.properties` generation for source tarball builds: move antrun `copy-version-properties` to a Maven profile that only activates when `.git` exists, so pre-generated `version.properties` from `release.sh` is used in source tarball builds.

### Release Tooling

- Rewrite `release/pre-release.sh` to create a release branch (`release/v<version>`) instead of committing directly to main.
- Add `changes/changes.md` verification to `release/pre-release.sh` (requires release notes section before proceeding).

## 0.2.0

### Highlights

Upgrade to the latest Apache SkyWalking OAP server, with documentation restructure and CI/CD improvements.

### Upstream Sync

- Sync SkyWalking submodule to upstream commit `64a1795d8a`.

### Documentation

- Add user-facing docs: Quick Start, Supported Features, FAQ.
- Move internal build-time docs to `docs/internals/`.
- Update `docs/README.md` with "For Users" / "For Contributors" sections and official doc site link.
- Add Docker Hub README (`docker/DOCKERHUB_README.md`).
- Add release guide (`docs/release-guide.md`).
- Update root `README.md` with project intro, quick start, and image registry table.
- Add "Building from Apache Source Tarball" section to `docs/compiling.md`.

### CI/CD

- Push Docker images to Docker Hub (release only) in addition to GHCR.
- Docker Hub only receives `latest` and version tags — no commit SHA tags.
- Add `.asf.yaml` branch protection.
- PR-only `cancel-in-progress` to avoid cancelling release builds.

### Release Tooling

- `release/release.sh`: auto-create SVN `graalvm-distro` directory if it doesn't exist.
- `release/release.sh`: fix SHA-512 checksum files to contain only hash + filename (no local paths).
- `release/release.sh`: re-upload darwin SHA-512 to GitHub Release after GPG signing.
- `release/release.sh`: link vote email to `compiling.md` instead of `quick-start.md`.
- Generate vote email template with GPG signer info and submodule commit IDs.

### New Module

- Add TraceQL module (Tempo-compatible trace query API) with Zipkin and SkyWalking datasource support.

### Build

- Fix Armeria handler scan to detect inherited `@Get`/`@Path` annotations (precompiler).

### Testing

- Replacement class staleness detector: add auto-discovery coverage check for untracked same-FQCN replacements in `oap-libs-for-graalvm/`.

### E2E Tests

- Add PromQL e2e test case (Prometheus-compatible query API).
- Add LogQL e2e test case (Loki-compatible log query API).
- Add TraceQL e2e test case (Tempo-compatible trace query API with Zipkin datasource).
- Update BanyanDB to `e1ba421` (fixes Zipkin `minDuration` trace query).
- Bump Istio to 1.28.0.
- Add Baseline e2e test case.

## 0.1.1

### Release Tooling

- `release/full-release.sh`: end-to-end release script.

## 0.1.0

### Highlights

Apache SkyWalking GraalVM Distro is a GraalVM native image distribution of the Apache SkyWalking OAP server.
It compiles the full-featured OAP server into a single native binary (~200MB), delivering instant startup
and reduced memory footprint compared to the standard JVM distribution.

This is the initial release, built on top of Apache SkyWalking OAP server.

### Build-Time Compilation

- Build-time OAL engine: pre-compile ~1285 metrics/builder/dispatcher classes via Javassist at Maven compile time.
- Build-time MAL compiler: pre-compile ~1250 MAL expressions from 71 YAML rule files into `MalExpression` classes.
- Build-time LAL compiler: pre-compile ~10 LAL scripts from 8 YAML files into `LalExpression` classes.
- Build-time Hierarchy compiler: pre-compile ~4 hierarchy matching rules into `BiFunction` classes.
- Build-time MeterSystem: pre-generate ~1188 meter function subclasses via Javassist.
- Auto-generate `reflect-config.json` by scanning HTTP handlers, GraphQL resolvers/types, config POJOs, and DSL manifests.

### GraalVM Native Image Compatibility

- Replace Groovy runtime with pure Java: MAL DSL, LAL DSL, and Hierarchy rules all use ANTLR4 + Javassist v2 engines.
- Replace Guava `ClassPath.from()` classpath scanning with build-time manifests for annotations, dispatchers, and source receivers.
- Replace `Field.setAccessible()` reflection in config loading with Lombok `@Setter`-based property copying.
- Replace `ServiceLoader` SPI discovery with direct provider wiring in `ModuleDefine`.
- Lazy `HttpClient` initialization in `HttpAlarmCallback` (static final field breaks native image).
- Direct JDK 25 virtual thread API calls instead of reflection in `VirtualThreads`.

### Same-FQCN Replacement Classes

- `OALEngineLoaderService` — load OAL classes from manifests.
- `AnnotationScan` — read annotation manifests instead of classpath scan.
- `SourceReceiverImpl` — read dispatcher manifests instead of classpath scan.
- `MeterSystem` — load pre-generated MeterFunction classes from manifest.
- `CoreModuleConfig` — added `@Setter` at class level.
- `HierarchyDefinitionService` / `HierarchyService` — Java-backed closures instead of GroovyShell.
- `HttpAlarmCallback` — lazy HttpClient initialization.
- MAL `DSL` / `FilterExpression` — load pre-compiled expressions from per-file manifests.
- LAL `DSL` — load pre-compiled expressions from manifest.
- `ModuleDefine` — direct provider wiring without ServiceLoader.
- `VirtualThreads` — direct JDK 25 API calls.
- `YamlConfigLoaderUtils` — Lombok setters instead of reflection.
- Config-only `@Setter` additions: `AnalyzerModuleConfig`, `LogAnalyzerModuleConfig`, `EnvoyMetricReceiverConfig`, `OtelMetricReceiverConfig`, `EBPFReceiverModuleConfig`, `AWSFirehoseReceiverModuleConfig`, `CiliumFetcherConfig`, `StatusQueryConfig`, `HealthCheckerConfig`.
- Config loaders: `Rules`, `LALConfigs`, `MeterConfigs` — load from JSON manifests instead of filesystem YAML.

### Distribution and Packaging

- JVM distribution: repackaged OAP server with all replacement classes via `maven-shade-plugin`.
- Native distribution: single binary (~200MB) with config files, LICENSE, NOTICE, and third-party licenses.
- Docker image: `ghcr.io/apache/skywalking-graalvm-distro` based on `debian:bookworm-slim`.
- Multi-arch Docker images: `linux/amd64` and `linux/arm64`.
- macOS native binary: build locally via `make native-image` on macOS.

### Testing

- 73 MAL comparison tests: dual-path verification (fresh v2 compilation vs pre-compiled classes).
- LAL pre-compilation tests: verify all 8 LAL YAML files load from manifest.
- Hierarchy comparison tests: verify pre-compiled rules match fresh compilation.
- Replacement class staleness detector: SHA-256 tracking of upstream source files.
- YAML staleness detector: SHA-256 tracking of ~49 YAML rule files.

### E2E Tests

- Baseline test: BanyanDB storage with alarm webhook verification.
- Simple Java agent test: trace collection with native OAP.
- Istio ALS test: Envoy access log service integration.
- Event, menu, alarm, log, meter, trace-profiling, telegraf, zabbix, and zipkin test cases.

### Release Tooling

- `release/pre-release.sh`: bump Maven version from SNAPSHOT to release, tag, and bump to next SNAPSHOT.
- `release/release.sh`: create source tarball, build macOS native binary locally, download Linux binaries from GitHub Release, GPG sign all artifacts.

### Benchmark

- Local boot test: cold/warm startup time and idle memory comparison (JVM vs GraalVM).
- Kubernetes resource usage test: CPU and memory under sustained ~12 RPS traffic on Kind + Istio + Bookinfo.
- CPM validation: verify entry service call rate matches expected traffic.

### CI/CD

- Unified CI/release workflow: push to main, tag push, PR, and manual `workflow_dispatch` with optional commit SHA and version.
- Dual Docker registry: push to both GHCR and Docker Hub (Docker Hub on release only).
- Multi-arch Docker manifest: `linux/amd64` and `linux/arm64` via push-by-digest and `imagetools create`.
- GitHub Release page: auto-upload tarballs with SHA-512 checksums and changelog from `changes/`.
- 12 E2E test cases on CI (non-release builds).
