# SkyWalking GraalVM Distro - Build Plan

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
| **Cluster** | ClusterModule | Kubernetes |
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
All `.oal` scripts are known. Run OAL engine at build time, export `.class` files, load them directly at runtime from manifests. See [OAL-IMMIGRATION.md](OAL-IMMIGRATION.md) for details.

### What Was Built
- `OALClassExporter` processes all 9 OAL defines, exports ~620 metrics classes, ~620 builder classes, ~45 dispatchers
- 3 manifest files: `oal-metrics-classes.txt`, `oal-dispatcher-classes.txt`, `oal-disabled-sources.txt`
- Same-FQCN replacement `OALEngineLoaderService` loads pre-compiled classes from manifests instead of running Javassist

### Upstream Changes Needed
- None. Build-time class export works via existing debug API (`setOpenEngineDebug(true)` + `setGeneratedFilePath()`)

---

## Challenge 2: MAL and LAL (Groovy + Javassist)

### What Happens
- MAL uses `GroovyShell` + `DelegatingScript` for meter rule expressions (~1188 rules). Also, `MeterSystem.create()` uses Javassist to dynamically generate one meter subclass per metric rule.
- LAL uses `GroovyShell` + `@CompileStatic` + `LALPrecompiledExtension` for log analysis scripts (10 rules).

### Approach (this repo)
Run full MAL/LAL initialization at build time via `build-tools/mal-compiler`. Export Javassist-generated `.class` files + compiled Groovy script bytecode. At runtime, load from manifests. See [MAL-IMMIGRATION.md](MAL-IMMIGRATION.md) for details.

### Key finding: MAL cannot use @CompileStatic
MAL expressions rely on `propertyMissing()` for sample name resolution and `ExpandoMetaClass` on `Number` for arithmetic operators — fundamentally dynamic Groovy features. Pre-compilation uses standard dynamic Groovy (same `CompilerConfiguration` as upstream). LAL already uses `@CompileStatic`.

### Risks
- Dynamic Groovy MOP may not work in GraalVM native image (Phase 3 concern)
- If `ExpandoMetaClass` fails in native image: fallback to upstream DSL changes

---

## Challenge 3: Classpath Scanning (Guava ClassPath) — PARTIALLY SOLVED

### What Happens
`ClassPath.from()` used in `SourceReceiverImpl.scan()`, `AnnotationScan`, `MeterSystem`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder`.

### What Was Solved
`AnnotationScan` and `SourceReceiverImpl` replaced with same-FQCN classes that read from build-time manifests. 6 annotation/interface manifests under `META-INF/annotation-scan/`: `ScopeDeclaration`, `Stream`, `Disable`, `MultipleDisable`, `SourceDispatcher`, `ISourceDecorator`.

`DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder` — these only run inside the OAL engine at build time, not at runtime. Automatically solved.

### What Remains
`MeterSystem` uses Guava `ClassPath.from()` to scan for meter function classes at runtime. This needs a manifest or build-time scan as part of MAL immigration.

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

## Challenge 5: Additional GraalVM Risks

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

### Phase 2: Build-Time Pre-Compilation & Verification — IN PROGRESS

**OAL immigration — COMPLETE:**
- [x] OAL engine → export `.class` files (`OALClassExporter`, 9 defines, ~620 metrics, ~45 dispatchers)
- [x] Classpath scanning → export class index (6 annotation/interface manifests in `oal-exporter`)
- [x] Runtime registration from manifests (3 same-FQCN replacement classes: `OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`)
- [x] Verification tests (`OALClassExporterTest` — 3 tests, `PrecompiledRegistrationTest` — 12 tests)

**Remaining:**
- [ ] MAL Groovy pre-compilation (`build-tools/mal-compiler` skeleton exists)
- [ ] LAL Groovy pre-compilation (can be part of mal-compiler)
- [ ] `MeterSystem` classpath scan: uses Guava scanning for meter function classes — needs manifest or build-time scan (part of MAL immigration)
- [ ] Config generator (`build-tools/config-generator` skeleton exists) — eliminate `Field.setAccessible` reflection in config loading
- [ ] Package everything into native-image classpath

### Phase 3: Native Image Build
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
- [ ] MAL/LAL: Groovy static compilation / DSL adjustments (not started)
- [ ] Other findings during implementation
