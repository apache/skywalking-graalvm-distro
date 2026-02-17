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

## Challenge 1: OAL Runtime Class Generation (Javassist)

### What Happens
OAL V2 generates metrics/builder/dispatcher classes at startup via Javassist (`ClassPool.makeClass()` → `CtClass.toClass()`). Already has `writeGeneratedFile()` for debug export.

### Approach (this repo)
All `.oal` scripts are known. Run OAL engine at build time, export `.class` files, load them directly in native-image mode.

### Upstream Changes Needed
- Potentially expose build-time class export as a stable API (currently debug-only)

---

## Challenge 2: MAL and LAL (Groovy)

### What Happens
MAL uses `GroovyShell` + `Binding` for meter rules. LAL uses `GroovyShell` + `DelegatingScript` + `Closure` + AST customizers.

### Approach (this repo)
Groovy static compilation (`@CompileStatic`). Pre-compile all MAL/LAL rule files at build time, export `.class` files.

### Risks
- `@CompileStatic` may not cover all dynamic features (track and work around)
- May need DSL adjustments (upstream PRs)

---

## Challenge 3: Classpath Scanning (Guava ClassPath)

### What Happens
`ClassPath.from()` used in `SourceReceiverImpl.scan()`, `AnnotationScan`, `MeterSystem`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder`.

### Approach (this repo)
Run classpath scanning during build-time pre-compilation. Verify all metrics/log-processing classes are discovered. Export scan results as static class index. Native-image mode loads from index.

---

## Challenge 4: Module System & Configuration

### Current Behavior
`ModuleManager` uses `ServiceLoader` (SPI). `application.yml` selects providers. Config loaded via reflection (`Field.setAccessible` + `field.set` in `YamlConfigLoaderUtils.copyProperties`).

### Approach (this repo)
1. **New module manager**: Directly constructs chosen `ModuleDefine`/`ModuleProvider` — no SPI
2. **Simplified config file**: Only knobs for selected providers
3. **Config loading**: Register known `ModuleConfig` classes for GraalVM reflection. Existing `copyProperties` works in native image with field registration.

---

## Challenge 5: Additional GraalVM Risks

| Risk | Mitigation |
|------|------------|
| **Reflection** (annotations, OAL enricher) | Captured during pre-compilation; `reflect-config.json` |
| **gRPC 1.70.0 / Netty 4.2.9** | GraalVM reachability metadata repo, Netty substitutions |
| **Resource loading** (`ResourceUtils`, config files) | `resource-config.json` via tracing agent |
| **Log4j2** | GraalVM metadata, disable JNDI |
| **Kafka client** (for Kafka fetcher) | Known GraalVM support, may need config |
| **ElasticSearch client** (not used, BanyanDB selected) | N/A for now |
| **Kubernetes client 6.7.1** (for cluster + config) | Has GraalVM support, may need config |

---

## Proposed Phases

### Phase 1: Build System Setup
- [ ] Set up Maven + Makefile in this repo
- [ ] Build skywalking submodule as a dependency
- [ ] Set up GraalVM JDK 25 in CI
- [ ] Create a JVM-mode starter with fixed module wiring (validate approach before native-image)
- [ ] Simplified config file for selected modules

### Phase 2: Build-Time Pre-Compilation & Verification
- [ ] Makefile/Maven step: run OAL engine → export `.class` files
- [ ] Makefile/Maven step: static-compile MAL Groovy scripts → export `.class` files
- [ ] Makefile/Maven step: static-compile LAL Groovy scripts → export `.class` files
- [ ] Makefile/Maven step: run classpath scanning → export class index
- [ ] Verify all metrics/log-processing classes are correctly discovered
- [ ] Package everything into native-image classpath

### Phase 3: Native Image Build
- [ ] `native-image-maven-plugin` configuration
- [ ] Run tracing agent to capture reflection/resource/JNI metadata
- [ ] Configure gRPC/Netty/Protobuf for native image
- [ ] GraalVM Feature class for SkyWalking-specific registrations
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
- [ ] OAL engine: stable build-time class export API
- [ ] MAL/LAL: DSL adjustments for Groovy static compilation (if needed)
- [ ] Other findings during implementation