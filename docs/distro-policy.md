# SkyWalking GraalVM Distro - Distribution Policy

## Goal
Build and package Apache SkyWalking OAP server as a GraalVM native image on JDK 25.

## Architecture Constraints
- **Submodule**: `skywalking/` is a git submodule of `apache/skywalking.git`. All SkyWalking source changes go through upstream PRs. **Minimize upstream changes.**
- **This repo**: Maven + Makefile to orchestrate building on top of the submodule. Pre-compilation, GraalVM config, native-image wiring, and the fixed module manager live here.
- **JDK 25**: Already compiles and runs. Not an issue.

## Module Selection (Fixed at Build Time)

| Category | Module | Provider |
|----------|--------|----------|
| **Core** | CoreModule | default |
| **Storage** | StorageModule | BanyanDB |
| **Cluster** | ClusterModule | Standalone, Kubernetes |
| **Configuration** | ConfigurationModule | Kubernetes |
| **Receivers** | SharingServerModule, TraceModule, JVMModule, MeterReceiverModule, LogModule, RegisterModule, ProfileModule, BrowserModule, EventModule, OtelMetricReceiverModule, MeshReceiverModule, EnvoyMetricReceiverModule, ZipkinReceiverModule, ZabbixReceiverModule, TelegrafReceiverModule, AWSFirehoseReceiverModule, CiliumFetcherModule, EBPFReceiverModule, AsyncProfilerModule, PprofModule, CLRModule, ConfigurationDiscoveryModule, KafkaFetcherModule | default providers |
| **Analyzers** | AnalyzerModule, LogAnalyzerModule, EventAnalyzerModule | default providers |
| **Query** | QueryModule (GraphQL), PromQLModule, LogQLModule, TraceQLModule, ZipkinQueryModule, StatusQueryModule | default providers |
| **Alarm** | AlarmModule | default |
| **Telemetry** | TelemetryModule | Prometheus |
| **Other** | ExporterModule, HealthCheckerModule, AIPipelineModule | default providers |

**Full feature set.** Work around issues as they arise.

---

## Core Strategy

1. **Build-Time Class Export**: All runtime code generation (OAL/MAL/LAL/Hierarchy via ANTLR4 + Javassist) runs at build time. Export `.class` files and package into native-image classpath. Classpath scanning also runs here as a verification gate.

2. **Fixed Module Wiring**: Module/provider selection is hardcoded in this distro (no SPI discovery). Simplified config file for selected providers only.

3. **Separation**: SkyWalking upstream changes tracked separately, go through upstream PRs.

---

## DSL Runtime Class Generation (Unified V2 Engines)

Since upstream PR #13723, all four SkyWalking DSL compilers (OAL, MAL, LAL, Hierarchy) use a unified pipeline: ANTLR4 parse → immutable AST → Javassist bytecode. This eliminated the need for the complex, 10,000-line custom Groovy-to-Java transpiler previously maintained in this distro.

### Approach (Simplified)
Run all four v2 compilers at build time via their native exposed APIs (`setClassOutputDir()`, `setClassNameHint()`, `setGeneratedFilePath()`). Capture generated `.class` files into the precompiler output JAR. At runtime, same-FQCN replacement loaders find these classes by name instead of compiling them.

**Details**: [dsl-immigration.md](internals/dsl-immigration.md) | [oal-immigration.md](internals/oal-immigration.md)

### OAL
- `OALEngineV2.start()` processes all 9 OAL defines at build time.
- Exports ~620 metrics classes, ~620 builder classes, ~45 dispatchers.
- 3 manifest files: `oal-metrics-classes.txt`, `oal-dispatcher-classes.txt`, `oal-disabled-sources.txt`.
- Same-FQCN `OALEngineLoaderService` loads pre-compiled classes from manifests.

### MAL
- `MALClassGenerator` compiles ~1250 MAL expressions from 71 YAML rule files at build time.
- Uses `setClassNameHint(yamlSource + metricName)` for deterministic naming.
- Same-FQCN v2 `DSL.java` loads pre-compiled `MalExpression` classes by computed name.
- `MeterSystem.create()` also runs at build time → exports ~1188 Javassist meter classes.

### LAL
- `LALClassGenerator` compiles 10 LAL scripts (8 YAML files) at build time.
- Uses `setClassNameHint(yamlSource + ruleName)` for deterministic naming.
- Same-FQCN v2 `DSL.java` loads pre-compiled `LalExpression` classes by computed name.

### Hierarchy
- `HierarchyRuleClassGenerator` compiles 4 hierarchy matching rules at build time.
- Same-FQCN `CompiledHierarchyRuleProvider` loads pre-compiled `BiFunction` classes by rule name.

### Upstream Changes & Simplification
- **Groovy Removed**: Groovy was completely removed from upstream production code (PR #13723).
- **Transpiler Deleted**: The legacy Groovy-to-Java transpiler (~9,800 lines of complex AST walking logic) has been deleted from this distro.
- **Native Alignment**: We now use the exact same compilation pipeline as upstream, just executed at build-time. This ensures 100% behavioral parity and zero maintenance lag for new DSL features.

---

## Classpath Scanning (Guava ClassPath)

### What Happens
`ClassPath.from()` used in `SourceReceiverImpl.scan()`, `AnnotationScan`, `MeterSystem`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder`.

### What Was Solved
- `AnnotationScan` and `SourceReceiverImpl` replaced with same-FQCN classes that read from build-time manifests. 6 annotation/interface manifests under `META-INF/annotation-scan/`: `ScopeDeclaration`, `Stream`, `Disable`, `MultipleDisable`, `SourceDispatcher`, `ISourceDecorator`.
- `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder` — these only run inside the OAL engine at build time, not at runtime. Automatically solved.
- `MeterSystem` replaced with same-FQCN class that reads from `META-INF/annotation-scan/MeterFunction.txt` manifest (16 meter function classes). Solved as part of MAL immigration.

---

## Module System & Configuration

### Current Behavior
`ModuleManager` uses `ServiceLoader` (SPI). `application.yml` selects providers. Config loaded via reflection (`Field.setAccessible` + `field.set` in `YamlConfigLoaderUtils.copyProperties`).

### Approach (this repo)
1. **New module manager**: Directly constructs chosen `ModuleDefine`/`ModuleProvider` — no SPI
2. **Simplified config file**: Only knobs for selected providers
3. **Config loading**: **No reflection.** Build-time tool scans all `ModuleConfig` subclass fields → generates same-FQCN replacement of `YamlConfigLoaderUtils` that uses Lombok setters and VarHandle to set config fields directly. Eliminates `Field.setAccessible`/`field.set` and the need for `reflect-config.json` for config classes.

**Details**: [config-init-immigration.md](internals/config-init-immigration.md)

### What Was Built
- `FixedModuleManager` — direct module/provider construction via `ModuleDefine.prepare()` overload, no SPI
- `GraalVMOAPServerStartUp` — entry point with `configuration.has()` guards for 6 optional modules
- `application.yml` — simplified config for selected providers
- `ConfigInitializerGenerator` — build-time tool that scans config classes and generates `YamlConfigLoaderUtils` replacement
- `YamlConfigLoaderUtils` — same-FQCN replacement using type-dispatch + setter/VarHandle instead of reflection
- `ModuleDefine` — same-FQCN replacement (`library-module-for-graalvm`) adding `prepare(ModuleManager, ModuleProvider, ...)` overload for direct provider wiring without ServiceLoader

---

## Same-FQCN Packaging (Repackaged Modules)

### Problem

Same-FQCN replacement classes need to shadow upstream originals. Classpath ordering tricks confuse developers and AI tools.

### Solution: Per-JAR Repackaged Modules (`oap-libs-for-graalvm`)

Each upstream JAR that has replacement classes gets a corresponding `*-for-graalvm` module under `oap-libs-for-graalvm/`. The module uses `maven-shade-plugin` to:
1. Include only the upstream JAR in the shade
2. Exclude the specific `.class` files being replaced
3. Produce a JAR containing: all upstream classes MINUS replaced ones PLUS our replacements

`oap-graalvm-server` depends on `*-for-graalvm` JARs instead of originals. Original upstream JARs are forced to `provided` scope via `<dependencyManagement>` to prevent transitive leakage.

### Same-FQCN Replacement Classes

**Non-trivial replacements (load pre-compiled assets from manifests):**

| Module | Replacement Classes | Purpose |
|---|---|---|
| `library-module-for-graalvm` | `ModuleDefine` | Add `prepare()` overload for direct provider wiring (bypasses ServiceLoader) |
| `server-core-for-graalvm` | `OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`, `MeterSystem`, `CoreModuleConfig`, `HierarchyDefinitionService`, `CompiledHierarchyRuleProvider` | Load from manifests instead of Javassist/ClassPath; config with @Setter; pre-compiled hierarchy rules |
| `library-util-for-graalvm` | `YamlConfigLoaderUtils` | Set config fields via setter instead of reflection |
| `meter-analyzer-for-graalvm` | `DSL` (v2), `Rules` | Load pre-compiled v2 `MalExpression` classes; load rule data from JSON config-data manifests |
| `log-analyzer-for-graalvm` | `DSL` (v2), `LogAnalyzerModuleConfig`, `LALConfigs` | Load pre-compiled v2 `LalExpression` classes; config with @Setter; load LAL config data from JSON config-data manifests |
| `agent-analyzer-for-graalvm` | `AnalyzerModuleConfig`, `MeterConfigs` | Config with @Setter; load meter config data from JSON config-data manifests |

**Config-only replacements (add `@Setter` for reflection-free config):**

| Module | Replacement Class |
|---|---|
| `envoy-metrics-receiver-for-graalvm` | `EnvoyMetricReceiverConfig` |
| `otel-receiver-for-graalvm` | `OtelMetricReceiverConfig` |
| `ebpf-receiver-for-graalvm` | `EBPFReceiverModuleConfig` |
| `aws-firehose-receiver-for-graalvm` | `AWSFirehoseReceiverModuleConfig` |
| `cilium-fetcher-for-graalvm` | `CiliumFetcherConfig` |
| `status-query-for-graalvm` | `StatusQueryConfig` |
| `health-checker-for-graalvm` | `HealthCheckerConfig` |

### No Classpath Ordering Required

No duplicate FQCNs on the classpath. The startup script (`oapService.sh`) uses a simple flat classpath. The `oap-graalvm-native` uber JAR also has no FQCN conflicts.

### Adding New Replacements

To add a new same-FQCN replacement:
1. Create a new `*-for-graalvm` module under `oap-libs-for-graalvm/` (or add to existing one)
2. Add the replacement `.java` file with the same FQCN
3. Configure shade plugin to exclude the original `.class` from the upstream JAR
4. Add the `-for-graalvm` artifact to root `pom.xml` `<dependencyManagement>`
5. In `oap-graalvm-server/pom.xml`: add the original JAR to `<dependencyManagement>` as `provided`, add `-for-graalvm` to `<dependencies>`
6. Add the original JAR to `distribution.xml` `<excludes>`

---

## Additional GraalVM Risks

| Risk | Status | Mitigation |
|------|--------|------------|
| **Reflection** (annotations, OAL enricher, HTTP handlers, GraphQL types) | SOLVED | Auto-generated by precompiler from manifests; `log4j2-reflect-config.json` for Log4j2 plugins |
| **gRPC / Netty / Armeria** | SOLVED | GraalVM reachability metadata repo handles these automatically |
| **Resource loading** (`ResourceUtils`, config files) | SOLVED | `resource-config.json` via tracing agent |
| **Log4j2** | SOLVED | Console-only `log4j2.xml` avoids RollingFile reflection chain; Log4j2 plugin classes in `log4j2-reflect-config.json` |
| **Kafka client** (for Kafka fetcher) | Untested | Known GraalVM support, may need config |
| **Kubernetes client 6.7.1** (for cluster + config) | Untested | Has GraalVM support, may need config at runtime |

---

## Distro Resource Files

Upstream `server-starter/src/main/resources/` contains 236 files. They fall into
two categories: files included directly in the distro `config/` directory (loaded
at runtime via file I/O), and files consumed by the precompiler at build time
(not needed at runtime — their logic is baked into pre-compiled `.class` files).

### Directly Included in Distro (`config/`)

These files are loaded at runtime via `ResourceUtils.read()`, `Files.walk()`, or
YAML parsing. No reflection involved — safe for GraalVM native image as-is.

| File / Directory | Count | Loaded By | Purpose |
|---|---|---|---|
| `application.yml` | 1 | Custom (distro's own, not upstream) | Module/provider config |
| `bydb.yml` | 1 | `BanyanDBConfigLoader` | BanyanDB storage base config |
| `bydb-topn.yml` | 1 | `BanyanDBConfigLoader` | BanyanDB TopN aggregation config |
| `log4j2.xml` | 1 | Log4j2 framework | Logging configuration |
| `alarm-settings.yml` | 1 | `AlarmModuleProvider` via `ResourceUtils.read()` | Alarm rules |
| `component-libraries.yml` | 1 | `ComponentLibraryCatalogService` via `ResourceUtils.read()` | Component ID mapping |
| `endpoint-name-grouping.yml` | 1 | `EndpointNameGroupingRuleWatcher` via `ResourceUtils.read()` | Endpoint grouping rules |
| `gateways.yml` | 1 | `UninstrumentedGatewaysConfig` via `ResourceUtils.read()` | Gateway definitions |
| `hierarchy-definition.yml` | 1 | `HierarchyDefinitionService` via `ResourceUtils.read()` | Layer hierarchy |
| `metadata-service-mapping.yaml` | 1 | `ResourceUtils.read()` | Metadata service mapping |
| `service-apdex-threshold.yml` | 1 | `ApdexThresholdConfig` via `ResourceUtils.read()` | APDEX thresholds |
| `trace-sampling-policy-settings.yml` | 1 | `TraceSamplingPolicyWatcher` via `ResourceUtils.read()` | Trace sampling |
| `ui-initialized-templates/**` | 131 | `UITemplateInitializer` via `Files.walk()` | UI dashboard JSON templates |
| `cilium-rules/**` | 2 | `CiliumFetcherProvider` via `ResourceUtils.getPathFiles()` | Cilium flow rules |
| `openapi-definitions/**` | 1 | `EndpointNameGrouping` via `ResourceUtils.getPathFiles()` | OpenAPI grouping definitions |

**Total: 146 files** included in the distro `config/` directory.

### Pre-compiled at Build Time (NOT in distro)

These files are consumed by `build-tools/precompiler` during the build. Their
expressions, scripts, and metric definitions are compiled into `.class` files
packaged in JARs. The YAML source files are not needed at runtime.

| Category | Count | Pre-compiled Into | Tool |
|---|---|---|---|
| `oal/*.oal` | 9 | ~620 metrics + ~620 builders + ~45 dispatchers (Javassist) | OAL v2 engine |
| `meter-analyzer-config/*.yaml` | 11 | ~147 `MalExpression` classes (ANTLR4+Javassist) + meter classes | MAL v2 compiler |
| `otel-rules/**/*.yaml` | 55 | ~1039 `MalExpression` classes + meter classes | MAL v2 compiler |
| `log-mal-rules/*.yaml` | 2 | ~2 `MalExpression` classes | MAL v2 compiler |
| `envoy-metrics-rules/*.yaml` | 2 | ~26 `MalExpression` classes + meter classes | MAL v2 compiler |
| `telegraf-rules/*.yaml` | 1 | ~20 `MalExpression` classes + meter classes | MAL v2 compiler |
| `zabbix-rules/*.yaml` | 1 | ~15 `MalExpression` classes + meter classes | MAL v2 compiler |
| `lal/*.yaml` | 8 | ~10 `LalExpression` classes (ANTLR4+Javassist) | LAL v2 compiler |
| `hierarchy-definition.yml` | 1 | ~4 `BiFunction` hierarchy rule classes | Hierarchy v2 compiler |

**Total: 90 files** consumed at build time, producing ~1285 OAL classes, ~1250 MAL expression classes, ~1188 meter classes, ~10 LAL expression classes, and ~4 hierarchy rule classes.

Additionally, the precompiler serializes parsed config POJOs as JSON manifests in
`META-INF/config-data/` (7 JSON files for meter-analyzer-config, otel-rules,
envoy-metrics-rules, log-mal-rules, telegraf-rules, zabbix-rules, and lal). These
provide the runtime "wiring" data (metric prefixes, rule names, expression lookup
keys) that replacement loader classes use instead of filesystem YAML access.

### Not Included (upstream-only)

| File | Reason |
|---|---|
| `application.yml` (upstream) | Replaced by distro's own simplified `application.yml` |

---

## Build Workflow

### Build System
- Maven + Makefile orchestrates building on top of the skywalking submodule
- GraalVM JDK 25 in CI (`.github/workflows/ci.yml`)
- JVM-mode starter with fixed module wiring (`FixedModuleManager` + `GraalVMOAPServerStartUp`)
- Simplified config file for selected modules (`application.yml`)

### Build-Time Pre-Compilation

All four DSL compilers (OAL/MAL/LAL/Hierarchy) use ANTLR4 + Javassist v2 engines. The unified precompiler (`build-tools/precompiler`) runs them all at build time, capturing generated `.class` files into the output JAR.

**OAL**: OAL v2 engine exports `.class` files (9 defines, ~620 metrics, ~620 builders, ~45 dispatchers). 7 annotation/interface manifests. Same-FQCN `OALEngineLoaderService` loads from manifests.

**MAL**: MAL v2 compiler processes 71 YAML files → ~1250 `MalExpression` classes + ~1188 Javassist meter classes. Deterministic class naming via `setClassNameHint()`. Same-FQCN v2 `DSL.java` loads pre-compiled classes by computed name.

**LAL**: LAL v2 compiler processes 8 YAML files → ~10 `LalExpression` classes. Deterministic class naming. Same-FQCN v2 `DSL.java` loads pre-compiled classes.

**Hierarchy**: Hierarchy v2 compiler processes 4 rules → ~4 `BiFunction` classes. Same-FQCN `CompiledHierarchyRuleProvider` loads pre-compiled rules.

**Config initialization**: `ConfigInitializerGenerator` generates same-FQCN `YamlConfigLoaderUtils` using Lombok setters — zero `Field.setAccessible` at runtime.

**Config data serialization**: Precompiler serializes parsed config POJOs to `META-INF/config-data/*.json` (7 JSON files). 3 same-FQCN replacement loaders (`MeterConfigs`, `Rules`, `LALConfigs`) deserialize from JSON instead of filesystem YAML.

**Module system**: `ModuleDefine` replacement with direct `prepare()` overload (bypasses ServiceLoader). `GraalVMOAPServerStartUp` with `configuration.has()` guards for 6 optional modules.

**Distro resource packaging**: 146 runtime files → distro `config/`, 90 pre-compiled files → JARs. Assembly descriptor (`distribution.xml`) packages runtime config files from upstream.

### Native Image Build

- `native-maven-plugin` (GraalVM buildtools 0.10.4) in `oap-graalvm-native` with `-Pnative` profile
- `reflect-config.json` auto-generated by precompiler from manifests (OAL, MAL, LAL, meter, HTTP handlers, GraphQL types)
- `log4j2-reflect-config.json` for Log4j2 plugin classes; console-only `log4j2.xml` with `SW_LOG_LEVEL` env var
- gRPC/Netty/Protobuf/Armeria via GraalVM reachability metadata repository
- Auto-scanned reflection metadata: Armeria HTTP handlers (~19), GraphQL resolvers (~32), GraphQL types (~182), config POJOs (8)
- Native binary: ~203MB, boots to full module init with all HTTP endpoints functional

### Native Distro Packaging

- Assembly descriptor (`native-distribution.xml`) packages native binary + config files
- `Dockerfile.native` packages native distro into `debian:bookworm-slim`
- `docker-compose.yml` with BanyanDB + OAP native services
- CI pipeline: multi-arch native build (amd64 + arm64) with Docker manifest push to GHCR

### Remaining Verification
- Verify all receiver plugins work (gRPC + HTTP endpoints)
- Verify all query APIs work (GraphQL, PromQL, LogQL, TraceQL, Zipkin)
- Verify cluster mode (K8s)
- Verify alarm module
- Performance benchmarking vs JVM

---

## Upstream Changes Tracker

Upstream SkyWalking PR #13723 replaced Groovy with ANTLR4 + Javassist v2 engines for MAL/LAL/Hierarchy. Groovy is completely removed from all production dependencies. All four DSL compilers now share the same pipeline and expose build-time compilation APIs.

No further upstream changes needed. All GraalVM incompatibilities are resolved in this distro via same-FQCN replacement and build-time pre-compilation:
- OAL: build-time class export works via existing debug API (`setGeneratedFilePath()`)
- MAL: build-time compilation via `MALClassGenerator.setClassOutputDir()`
- LAL: build-time compilation via `LALClassGenerator.setClassOutputDir()`
- Hierarchy: build-time compilation via `HierarchyRuleClassGenerator.setClassOutputDir()`
