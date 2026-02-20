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

## Challenge 1: OAL Runtime Class Generation (Javassist) — SOLVED

### What Happens
OAL V2 generates metrics/builder/dispatcher classes at startup via Javassist (`ClassPool.makeClass()` → `CtClass.toClass()`). Already has `writeGeneratedFile()` for debug export.

### Approach (this repo)
All `.oal` scripts are known. Run OAL engine at build time, export `.class` files, load them directly at runtime from manifests.

**Details**: [OAL-IMMIGRATION.md](OAL-IMMIGRATION.md)

### What Was Built
- `OALClassExporter` processes all 9 OAL defines, exports ~620 metrics classes, ~620 builder classes, ~45 dispatchers
- 3 manifest files: `oal-metrics-classes.txt`, `oal-dispatcher-classes.txt`, `oal-disabled-sources.txt`
- Same-FQCN replacement `OALEngineLoaderService` loads pre-compiled classes from manifests instead of running Javassist

### Upstream Changes Needed
- None. Build-time class export works via existing debug API (`setOpenEngineDebug(true)` + `setGeneratedFilePath()`)

---

## Challenge 2: MAL and LAL (Groovy + Javassist) — SOLVED

### What Happens
- MAL uses `GroovyShell` + `DelegatingScript` for meter rule expressions (~1250 rules across 71 YAML files). Also, `MeterSystem.create()` uses Javassist to dynamically generate one meter subclass per metric rule.
- LAL uses `GroovyShell` + `@CompileStatic` + `LALPrecompiledExtension` for log analysis scripts (10 rules).

### Approach (this repo)
Run full MAL/LAL initialization at build time via `build-tools/precompiler` (unified tool). Export Javassist-generated `.class` files + compiled Groovy script bytecode. At runtime, load from manifests.

**Details**: [MAL-IMMIGRATION.md](MAL-IMMIGRATION.md) | [LAL-IMMIGRATION.md](LAL-IMMIGRATION.md)

### What Was Built
- **Unified precompiler** (`build-tools/precompiler`): Replaced separate `oal-exporter` and `mal-compiler` modules. Compiles all 71 MAL YAML rule files (meter-analyzer-config, otel-rules, log-mal-rules, envoy-metrics-rules, telegraf-rules, zabbix-rules) producing 1209 meter classes and 1250 Groovy scripts.
- **Manifests**: `META-INF/mal-groovy-manifest.txt` (script→class mapping), `META-INF/mal-groovy-expression-hashes.txt` (SHA-256 for combination pattern resolution), `META-INF/mal-meter-classes.txt` (Javassist-generated classes), `META-INF/annotation-scan/MeterFunction.txt` (16 function classes).
- **Combination pattern**: Multiple YAML files from different data sources (otel, telegraf, zabbix) may define metrics with the same name. Deterministic suffixes (`_1`, `_2`) with expression hash tracking enable unambiguous resolution.
- **Same-FQCN replacements**: `DSL.java` (MAL), `DSL.java` (LAL), `FilterExpression.java`, `MeterSystem.java` — all load pre-compiled classes from manifests instead of runtime compilation.
- **Comparison test suite**: 73 test classes, 1281 assertions covering all 71 YAML files. Each test validates pre-compiled classes produce identical results to fresh Groovy compilation.

### Key finding: MAL cannot use @CompileStatic
MAL expressions rely on `propertyMissing()` for sample name resolution and `ExpandoMetaClass` on `Number` for arithmetic operators — fundamentally dynamic Groovy features. Pre-compilation uses standard dynamic Groovy (same `CompilerConfiguration` as upstream). LAL already uses `@CompileStatic`.

### Risks
- Dynamic Groovy MOP may not work in GraalVM native image (Phase 3 concern)
- If `ExpandoMetaClass` fails in native image: fallback to upstream DSL changes

---

## Challenge 3: Classpath Scanning (Guava ClassPath) — SOLVED

### What Happens
`ClassPath.from()` used in `SourceReceiverImpl.scan()`, `AnnotationScan`, `MeterSystem`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder`.

### What Was Solved
- `AnnotationScan` and `SourceReceiverImpl` replaced with same-FQCN classes that read from build-time manifests. 6 annotation/interface manifests under `META-INF/annotation-scan/`: `ScopeDeclaration`, `Stream`, `Disable`, `MultipleDisable`, `SourceDispatcher`, `ISourceDecorator`.
- `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder` — these only run inside the OAL engine at build time, not at runtime. Automatically solved.
- `MeterSystem` replaced with same-FQCN class that reads from `META-INF/annotation-scan/MeterFunction.txt` manifest (16 meter function classes). Solved as part of MAL immigration.

---

## Challenge 4: Module System & Configuration — SOLVED

### Current Behavior
`ModuleManager` uses `ServiceLoader` (SPI). `application.yml` selects providers. Config loaded via reflection (`Field.setAccessible` + `field.set` in `YamlConfigLoaderUtils.copyProperties`).

### Approach (this repo)
1. **New module manager**: Directly constructs chosen `ModuleDefine`/`ModuleProvider` — no SPI
2. **Simplified config file**: Only knobs for selected providers
3. **Config loading**: **No reflection.** Build-time tool scans all `ModuleConfig` subclass fields → generates same-FQCN replacement of `YamlConfigLoaderUtils` that uses Lombok setters and VarHandle to set config fields directly. Eliminates `Field.setAccessible`/`field.set` and the need for `reflect-config.json` for config classes.

**Details**: [CONFIG-INIT-IMMIGRATION.md](CONFIG-INIT-IMMIGRATION.md)

### What Was Built
- `FixedModuleManager` — direct module/provider construction via `ModuleDefine.prepare()` overload, no SPI
- `GraalVMOAPServerStartUp` — entry point with `configuration.has()` guards for 6 optional modules
- `application.yml` — simplified config for selected providers
- `ConfigInitializerGenerator` — build-time tool that scans config classes and generates `YamlConfigLoaderUtils` replacement
- `YamlConfigLoaderUtils` — same-FQCN replacement using type-dispatch + setter/VarHandle instead of reflection
- `ModuleDefine` — same-FQCN replacement (`library-module-for-graalvm`) adding `prepare(ModuleManager, ModuleProvider, ...)` overload for direct provider wiring without ServiceLoader

---

## Challenge 5: Same-FQCN Packaging — SOLVED (Repackaged Modules)

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

## Challenge 6: Additional GraalVM Risks

| Risk | Mitigation |
|------|------------|
| **Reflection** (annotations, OAL enricher — not config loading) | Captured during pre-compilation; `reflect-config.json`. Config loading uses generated code (see [CONFIG-INIT-IMMIGRATION.md](CONFIG-INIT-IMMIGRATION.md)), no reflection. |
| **gRPC 1.70.0 / Netty 4.2.9** | GraalVM reachability metadata repo, Netty substitutions |
| **Resource loading** (`ResourceUtils`, config files) | `resource-config.json` via tracing agent |
| **Log4j2** | GraalVM metadata, disable JNDI |
| **Kafka client** (for Kafka fetcher) | Known GraalVM support, may need config |
| **ElasticSearch client** (not used, BanyanDB selected) | N/A for now |
| **Kubernetes client 6.7.1** (for cluster + config) | Has GraalVM support, may need config |

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

## Proposed Phases

### Phase 1: Build System Setup — COMPLETE
- [x] Set up Maven + Makefile in this repo
- [x] Build skywalking submodule as a dependency
- [x] Set up GraalVM JDK 25 in CI (`.github/workflows/ci.yml`)
- [x] Create a JVM-mode starter with fixed module wiring (`FixedModuleManager` + `GraalVMOAPServerStartUp`)
- [x] Simplified config file for selected modules (`application.yml`)

### Phase 2: Build-Time Pre-Compilation & Verification — COMPLETE

**OAL immigration — COMPLETE:**
- [x] OAL engine → export `.class` files (9 defines, ~620 metrics, ~620 builders, ~45 dispatchers)
- [x] Classpath scanning → export class index (7 annotation/interface manifests including MeterFunction)
- [x] Runtime registration from manifests (3 same-FQCN replacement classes: `OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`)
- [x] Verification tests (`PrecompilerTest`, `PrecompiledRegistrationTest`)

**MAL immigration — COMPLETE:**
- [x] Unified precompiler (`build-tools/precompiler`): replaces separate `oal-exporter` and `mal-compiler`
- [x] MAL Groovy pre-compilation: 71 YAML files → 1250 Groovy scripts + 1209 Javassist meter classes
- [x] Combination pattern support: deterministic suffixes + expression hash tracking for cross-source metric deduplication
- [x] Same-FQCN replacements: `DSL.java` (MAL), `DSL.java` (LAL), `FilterExpression.java`, `MeterSystem.java`
- [x] Comparison test suite: 73 test classes, 1281 assertions (all 71 YAML files, 100% coverage)
- [x] SHA-256 staleness detection for submodule YAML drift
- [x] `MeterSystem` classpath scan eliminated via manifest

**LAL immigration — COMPLETE:**
- [x] LAL Groovy pre-compilation: 8 YAML files → 10 rules → 6 unique pre-compiled classes (`@CompileStatic`)
- [x] Same-FQCN replacement: `DSL.java` (LAL) loads pre-compiled scripts via SHA-256 hash lookup
- [x] Comparison test suite: 5 test classes, 19 assertions (all 8 YAML files, 100% branch coverage)

**Config initialization — COMPLETE:**
- [x] `ConfigInitializerGenerator` build tool scans all provider config classes, generates same-FQCN `YamlConfigLoaderUtils` replacement
- [x] Generated class uses Lombok setters, VarHandle, and getter+clear+addAll — zero `Field.setAccessible` at runtime
- [x] Reflective fallback for unknown config types (safety net)

**Config data serialization — COMPLETE:**
- [x] Precompiler serializes parsed config POJOs to `META-INF/config-data/*.json` (7 JSON files)
- [x] 3 same-FQCN replacement loaders: `MeterConfigs` (meter-analyzer-config), `Rules` (otel-rules, envoy-metrics-rules, telegraf-rules, log-mal-rules), `LALConfigs` (lal)
- [x] Replacement loaders deserialize from JSON instead of filesystem YAML, with glob matching for enabled rules

**Module system — COMPLETE:**
- [x] `library-module-for-graalvm`: `ModuleDefine` replacement with direct `prepare()` overload (bypasses ServiceLoader)
- [x] `FixedModuleManager` simplified: calls `module.prepare(manager, provider, config, params)` directly
- [x] `GraalVMOAPServerStartUp`: `configuration.has()` guards for 6 optional modules (receiver-zabbix, receiver-zipkin, kafka-fetcher, cilium-fetcher, query-zipkin, exporter)

**Distro resource packaging — COMPLETE:**
- [x] Identified all 236 upstream resource files: 146 runtime files → distro `config/`, 89 pre-compiled files → JARs
- [x] Assembly descriptor (`distribution.xml`) packages runtime config files from upstream
- [x] Pre-compiled OAL/MAL/LAL files excluded from distro (not needed at runtime)

**JVM boot verification — COMPLETE:**
- [x] Full boot with BanyanDB storage: 38 modules started (6 optional modules disabled by default)
- [x] Pre-compiled assets loaded: 620 OAL metrics + 45 dispatchers, 1250 MAL scripts, 29 filter scripts, 7 LAL scripts
- [x] Config data loaded from JSON: 9 meter configs, 55 otel rules, 2 envoy rules, 1 telegraf rule, 1 log-mal rule, 8 LAL configs
- [x] HTTP endpoints: gRPC :11800, HTTP :12800, Firehose :12801, PromQL :9090, LogQL :3100, Prometheus :1234
- [x] Debug logging for per-script MAL/LAL/filter loading (`[count/total]: metricName`) for e2e verification
- [x] Dependency versions centralized in root `pom.xml`

### Phase 3: Native Image Build
- [ ] `native-image-maven-plugin` configuration in `oap-graalvm-native`
- [ ] Run tracing agent to capture reflection/resource/JNI metadata
- [ ] `reflect-config.json` for OAL-generated classes (`Class.forName()` calls)
- [ ] `resource-config.json` for runtime config files loaded via `ResourceUtils.read()`
- [ ] Configure gRPC/Netty/Protobuf for native image
- [ ] GraalVM Feature class for SkyWalking-specific registrations
- [x] Resolve HierarchyDefinitionService Groovy blocker (same-FQCN replacement with Java-backed closures)
- [ ] Verify OTEL and Envoy ServiceLoader SPI work in native image (GraalVM supports META-INF/services natively)
- [ ] Get OAP server booting as native image with BanyanDB

### Phase 4: Harden & Test
- [ ] Verify all receiver plugins work
- [ ] Verify all query APIs work
- [ ] Verify cluster mode (K8s)
- [ ] Verify alarm module
- [ ] Performance benchmarking vs JVM
- [ ] CI: automated native-image build + smoke tests

---

## Upstream Changes Tracker
- [x] OAL engine: build-time class export works via existing debug API (no upstream change needed)
- [x] MAL: No upstream changes needed — pre-compilation uses same dynamic Groovy `CompilerConfiguration` as upstream
- [ ] Dynamic Groovy MOP in native image: may need upstream DSL changes if `ExpandoMetaClass` fails (Phase 3 concern)
- [ ] Other findings during implementation
