# Changes

## 1.0.0

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

### CI/CD

- GitHub Actions CI: build, test, license check, and E2E tests.
- Release workflow: manual trigger with commit SHA, multi-arch Linux + macOS builds, Docker manifest with version and commit tags, GitHub Release page with checksums.
