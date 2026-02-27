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
| **Query** | QueryModule (GraphQL), PromQLModule, LogQLModule, ZipkinQueryModule, StatusQueryModule | default providers |
| **Alarm** | AlarmModule | default |
| **Telemetry** | TelemetryModule | Prometheus |
| **Other** | ExporterModule, HealthCheckerModule, AIPipelineModule | default providers |

**Full feature set.** Work around issues as they arise.

---

## Core Strategy

1. **Build-Time Class Export**: All runtime code generation (OAL via Javassist, MAL/LAL via Groovy) runs at build time. Export `.class` files and package into native-image classpath. Classpath scanning also runs here as a verification gate.

2. **Fixed Module Wiring**: Module/provider selection is hardcoded in this distro (no SPI discovery). Simplified config file for selected providers only.

3. **Separation**: SkyWalking upstream changes tracked separately, go through upstream PRs.

---

## OAL Runtime Class Generation (Javassist)

### What Happens
OAL V2 generates metrics/builder/dispatcher classes at startup via Javassist (`ClassPool.makeClass()` → `CtClass.toClass()`). Already has `writeGeneratedFile()` for debug export.

### Approach (this repo)
All `.oal` scripts are known. Run OAL engine at build time, export `.class` files, load them directly at runtime from manifests.

**Details**: [oal-immigration.md](oal-immigration.md)

### What Was Built
- `OALClassExporter` processes all 9 OAL defines, exports ~620 metrics classes, ~620 builder classes, ~45 dispatchers
- 3 manifest files: `oal-metrics-classes.txt`, `oal-dispatcher-classes.txt`, `oal-disabled-sources.txt`
- Same-FQCN replacement `OALEngineLoaderService` loads pre-compiled classes from manifests instead of running Javassist

### Upstream Changes Needed
- None. Build-time class export works via existing debug API (`setOpenEngineDebug(true)` + `setGeneratedFilePath()`)

---

## MAL and LAL (Groovy + Javassist)

### What Happens
- MAL uses `GroovyShell` + `DelegatingScript` for meter rule expressions (~1250 rules across 71 YAML files). Also, `MeterSystem.create()` uses Javassist to dynamically generate one meter subclass per metric rule.
- LAL uses `GroovyShell` + `@CompileStatic` + `LALPrecompiledExtension` for log analysis scripts (10 rules).

### Approach (this repo)
Run full MAL/LAL initialization at build time via `build-tools/precompiler` (unified tool). Export Javassist-generated `.class` files. Transpile all Groovy expressions to pure Java at build time — zero Groovy at runtime.

**Details**: [mal-immigration.md](mal-immigration.md) | [lal-immigration.md](lal-immigration.md)

### What Was Built
- **Unified precompiler** (`build-tools/precompiler`): Replaced separate `oal-exporter` and `mal-compiler` modules. Compiles all 71 MAL YAML rule files (meter-analyzer-config, otel-rules, log-mal-rules, envoy-metrics-rules, telegraf-rules, zabbix-rules) producing 1209 meter classes.
- **MAL-to-Java transpiler**: 1250+ MAL expressions transpiled from Groovy AST to pure Java `MalExpression` implementations. 29 filter expressions transpiled to `MalFilter` implementations. Zero Groovy at runtime.
- **LAL-to-Java transpiler**: 10 LAL scripts (6 unique) transpiled to pure Java `LalExpression` implementations. Spec classes enhanced with `Consumer` overloads for transpiled code.
- **Groovy stubs module**: Minimal `groovy.lang.*` types (Binding, Closure, etc.) for class loading. No `org.codehaus.groovy.*` — prevents GraalVM `GroovyIndyInterfaceFeature` from activating.
- **Manifests**: `META-INF/mal-expressions.txt` (transpiled Java classes), `META-INF/mal-groovy-expression-hashes.txt` (SHA-256 for combination pattern resolution), `META-INF/mal-meter-classes.txt` (Javassist-generated classes), `META-INF/lal-expressions.txt` (transpiled LAL classes), `META-INF/annotation-scan/MeterFunction.txt` (16 function classes).
- **Combination pattern**: Multiple YAML files from different data sources (otel, telegraf, zabbix) may define metrics with the same name. Deterministic suffixes (`_1`, `_2`) with expression hash tracking enable unambiguous resolution.
- **Same-FQCN replacements**: `DSL.java` (MAL), `DSL.java` (LAL), `FilterExpression.java`, `MeterSystem.java`, `Expression.java`, `SampleFamily.java` — all use pure Java, no Groovy.
- **Comparison test suite**: 73 MAL test classes (1281 assertions) + 5 LAL test classes (19 assertions) covering all 79 YAML files. Tests require data flow through full pipeline (no vacuous agreements). Dual-path: fresh Groovy compilation (Path A) vs transpiled Java (Path B).

### Groovy Elimination
- MAL: `MalExpression` interface replaces `DelegatingScript`. `SampleFamily` uses Java functional interfaces (`TagFunction`, `SampleFilter`, `ForEachFunction`, `DecorateFunction`, `PropertiesExtractor`) instead of `groovy.lang.Closure`.
- LAL: `LalExpression` interface replaces `DelegatingScript`. Spec classes have `Consumer` overloads.
- No `groovy.lang.Closure` in any production source code. Groovy is test-only dependency.

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

**Details**: [config-init-immigration.md](config-init-immigration.md)

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

### 23 Same-FQCN Replacement Classes Across 13 Modules

**Non-trivial replacements (load pre-compiled assets from manifests):**

| Module | Replacement Classes | Purpose |
|---|---|---|
| `library-module-for-graalvm` | `ModuleDefine` | Add `prepare()` overload for direct provider wiring (bypasses ServiceLoader) |
| `server-core-for-graalvm` | `OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`, `MeterSystem`, `CoreModuleConfig`, `HierarchyDefinitionService` | Load from manifests instead of Javassist/ClassPath; config with @Setter; Java-backed closures instead of GroovyShell |
| `library-util-for-graalvm` | `YamlConfigLoaderUtils` | Set config fields via setter instead of reflection |
| `meter-analyzer-for-graalvm` | `DSL`, `FilterExpression`, `Rules` | Load pre-compiled MAL Groovy scripts from manifest; load rule data from JSON config-data manifests |
| `log-analyzer-for-graalvm` | `DSL`, `LogAnalyzerModuleConfig`, `LALConfigs` | Load pre-compiled LAL scripts; config with @Setter; load LAL config data from JSON config-data manifests |
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
| `oal/*.oal` | 9 | ~620 metrics classes + ~620 builders + ~45 dispatchers (Javassist) | `OALClassExporter` |
| `meter-analyzer-config/*.yaml` | 11 | 147 Groovy scripts + Javassist meter classes | `MALPrecompiler` |
| `otel-rules/**/*.yaml` | 55 | 1044 Groovy scripts + Javassist meter classes | `MALPrecompiler` |
| `log-mal-rules/*.yaml` | 2 | 2 Groovy scripts | `MALPrecompiler` |
| `envoy-metrics-rules/*.yaml` | 2 | 26 Groovy scripts + Javassist meter classes | `MALPrecompiler` |
| `telegraf-rules/*.yaml` | 1 | 20 Groovy scripts + Javassist meter classes | `MALPrecompiler` |
| `zabbix-rules/*.yaml` | 1 | 15 Groovy scripts + Javassist meter classes | `MALPrecompiler` |
| `lal/*.yaml` | 8 | 6 unique `@CompileStatic` Groovy classes | `LALPrecompiler` |

**Total: 89 files** consumed at build time, producing ~1285 pre-compiled classes
and ~1254 Groovy scripts stored in JARs.

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

**OAL**: OAL engine exports `.class` files (9 defines, ~620 metrics, ~620 builders, ~45 dispatchers). 7 annotation/interface manifests. 3 same-FQCN replacement classes (`OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`).

**MAL**: Unified precompiler (`build-tools/precompiler`) processes 71 YAML files → 1250 expressions transpiled to pure Java `MalExpression` + 1209 Javassist meter classes. Combination pattern with deterministic suffixes + expression hash tracking. Same-FQCN replacements: `DSL.java`, `FilterExpression.java`, `MeterSystem.java`, `Expression.java`, `SampleFamily.java`. 73 comparison test classes, 1281 assertions (100% YAML coverage).

**LAL**: 8 YAML files → 10 rules → 6 unique transpiled Java `LalExpression` classes. Same-FQCN `DSL.java` loads via SHA-256 hash lookup. 5 comparison test classes, 19 assertions (100% branch coverage).

**Config initialization**: `ConfigInitializerGenerator` generates same-FQCN `YamlConfigLoaderUtils` using Lombok setters — zero `Field.setAccessible` at runtime.

**Config data serialization**: Precompiler serializes parsed config POJOs to `META-INF/config-data/*.json` (7 JSON files). 3 same-FQCN replacement loaders (`MeterConfigs`, `Rules`, `LALConfigs`) deserialize from JSON instead of filesystem YAML.

**Module system**: `ModuleDefine` replacement with direct `prepare()` overload (bypasses ServiceLoader). `GraalVMOAPServerStartUp` with `configuration.has()` guards for 6 optional modules.

**Distro resource packaging**: 146 runtime files → distro `config/`, 89 pre-compiled files → JARs. Assembly descriptor (`distribution.xml`) packages runtime config files from upstream.

### Groovy Elimination

- MAL-to-Java transpiler: 1250+ expressions → pure Java `MalExpression` (no Groovy MOP/ExpandoMetaClass)
- LAL-to-Java transpiler: 10 scripts → pure Java `LalExpression` (no DelegatingScript)
- `SampleFamily` Closure parameters → Java functional interfaces (zero `groovy.lang.Closure` in production)
- Groovy stubs module for class loading (no `org.codehaus.groovy.*`)
- HierarchyDefinitionService: same-FQCN replacement with Java-backed closures
- Real Groovy (`groovy-5.0.3.jar`) is test-only; `groovy-stubs-1.0.0-SNAPSHOT.jar` on runtime classpath
- 1303 tests require actual data flow (no vacuous empty-result agreements)

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
- Verify all query APIs work (GraphQL, PromQL, LogQL, Zipkin)
- Verify cluster mode (K8s)
- Verify alarm module
- Performance benchmarking vs JVM

---

## Upstream Changes Tracker

No upstream changes needed. All GraalVM incompatibilities are resolved in this distro via same-FQCN replacement and build-time pre-compilation:
- OAL: build-time class export works via existing debug API
- MAL: transpiled to pure Java, bypasses Groovy entirely
- LAL: transpiled to pure Java, bypasses Groovy entirely
- Dynamic Groovy MOP: transpiled to pure Java, no ExpandoMetaClass/MOP at runtime
