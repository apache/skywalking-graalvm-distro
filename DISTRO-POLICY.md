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

## Challenge 4: Module System & Configuration

### Current Behavior
`ModuleManager` uses `ServiceLoader` (SPI). `application.yml` selects providers. Config loaded via reflection (`Field.setAccessible` + `field.set` in `YamlConfigLoaderUtils.copyProperties`).

### Approach (this repo)
1. **New module manager**: Directly constructs chosen `ModuleDefine`/`ModuleProvider` — no SPI
2. **Simplified config file**: Only knobs for selected providers
3. **Config loading**: **No reflection.** At build time, read `application.yml` + scan `ModuleConfig` subclass fields → generate Java code that directly sets config fields (e.g. `config.restPort = 12800;`). Eliminates `Field.setAccessible`/`field.set` and the need for `reflect-config.json` for config classes.

### What Was Built (Phase 1)
- `FixedModuleManager` — direct module/provider construction, no SPI
- `ModuleWiringBridge` — wires all selected modules/providers
- `GraalVMOAPServerStartUp` — entry point
- `application.yml` — simplified config for selected providers

---

## Challenge 5: Same-FQCN Packaging for Distribution

### Problem

The same-FQCN replacement technique works during Maven compilation and testing because Maven places `target/classes/` (the module's own compiled source) on the classpath **before** all dependency JARs. Java's classloader uses first-found-wins, so our replacement classes shadow the upstream originals.

However, this implicit ordering does **not** carry over to distribution packaging:

- **Uber JAR** (maven-shade-plugin): When merging all JARs into one, duplicate FQCN class files collide. Only one copy survives, and the winner depends on dependency processing order — not guaranteed to be ours.
- **Classpath mode** (separate JARs): `java -cp jar1:jar2:jar3` or `native-image -cp jar1:jar2:jar3` uses first-found-wins based on `-cp` ordering. Requires explicit ordering that is fragile and easy to break.
- **GraalVM native-image**: Uses the same classpath resolution as JVM. Duplicate FQCNs on the classpath may produce warnings or unpredictable behavior.

### 7 Same-FQCN Replacement Classes

| Replacement Class | Upstream Artifact | Purpose |
|---|---|---|
| `OALEngineLoaderService` | `server-core` | Load OAL classes from manifests instead of Javassist |
| `AnnotationScan` | `server-core` | Read annotation manifests instead of Guava ClassPath |
| `SourceReceiverImpl` | `server-core` | Read dispatcher/decorator manifests instead of Guava ClassPath |
| `MeterSystem` | `server-core` | Read MeterFunction manifest + pre-generated meter classes |
| `DSL` (MAL) | `meter-analyzer` | Load pre-compiled MAL Groovy scripts from manifest |
| `FilterExpression` | `meter-analyzer` | Load pre-compiled filter closures from manifest |
| `DSL` (LAL) | `log-analyzer` | Load pre-compiled LAL scripts via SHA-256 hash lookup |

### Approach: Uber JAR with Explicit Shade Filters

The `oap-graalvm-native` module will use `maven-shade-plugin` to produce a single uber JAR with explicit filters that exclude the 7 upstream classes. This makes conflict resolution explicit and auditable.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <filters>
            <filter>
                <artifact>org.apache.skywalking:server-core</artifact>
                <excludes>
                    <exclude>org/apache/skywalking/oap/server/core/oal/rt/OALEngineLoaderService.class</exclude>
                    <exclude>org/apache/skywalking/oap/server/core/annotation/AnnotationScan.class</exclude>
                    <exclude>org/apache/skywalking/oap/server/core/source/SourceReceiverImpl.class</exclude>
                    <exclude>org/apache/skywalking/oap/server/core/analysis/meter/MeterSystem.class</exclude>
                </excludes>
            </filter>
            <filter>
                <artifact>org.apache.skywalking:meter-analyzer</artifact>
                <excludes>
                    <exclude>org/apache/skywalking/oap/meter/analyzer/dsl/DSL.class</exclude>
                    <exclude>org/apache/skywalking/oap/meter/analyzer/dsl/FilterExpression.class</exclude>
                </excludes>
            </filter>
            <filter>
                <artifact>org.apache.skywalking:log-analyzer</artifact>
                <excludes>
                    <exclude>org/apache/skywalking/oap/log/analyzer/dsl/DSL.class</exclude>
                </excludes>
            </filter>
        </filters>
    </configuration>
</plugin>
```

The resulting uber JAR contains exactly one copy of each class — no duplicates, no ordering ambiguity. This JAR is then fed to `native-image` for AOT compilation.

**Important**: When new same-FQCN replacement classes are added, the shade filter list must be updated to match. This list should be kept in sync with the table above.

---

## Challenge 6: Additional GraalVM Risks

| Risk | Mitigation |
|------|------------|
| **Reflection** (annotations, OAL enricher — not config loading) | Captured during pre-compilation; `reflect-config.json`. Config loading uses generated code, no reflection. |
| **gRPC 1.70.0 / Netty 4.2.9** | GraalVM reachability metadata repo, Netty substitutions |
| **Resource loading** (`ResourceUtils`, config files) | `resource-config.json` via tracing agent |
| **Log4j2** | GraalVM metadata, disable JNDI |
| **Kafka client** (for Kafka fetcher) | Known GraalVM support, may need config |
| **ElasticSearch client** (not used, BanyanDB selected) | N/A for now |
| **Kubernetes client 6.7.1** (for cluster + config) | Has GraalVM support, may need config |

---

## Proposed Phases

### Phase 1: Build System Setup — COMPLETE
- [x] Set up Maven + Makefile in this repo
- [x] Build skywalking submodule as a dependency
- [ ] Set up GraalVM JDK 25 in CI
- [x] Create a JVM-mode starter with fixed module wiring (`FixedModuleManager` + `ModuleWiringBridge` + `GraalVMOAPServerStartUp`)
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

**Remaining:**
- [ ] Config generator (`build-tools/config-generator` skeleton exists) — eliminate `Field.setAccessible` reflection in config loading
- [ ] Package everything into native-image classpath

### Phase 3: Native Image Build
- [ ] Uber JAR packaging via `maven-shade-plugin` in `oap-graalvm-native` with explicit shade filters for same-FQCN classes (see Challenge 5)
- [ ] `native-image-maven-plugin` configuration
- [ ] Run tracing agent to capture reflection/resource/JNI metadata
- [ ] Configure gRPC/Netty/Protobuf for native image
- [ ] GraalVM Feature class for SkyWalking-specific registrations
- [ ] `reflect-config.json` for OAL-generated classes (`Class.forName()` calls)
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
