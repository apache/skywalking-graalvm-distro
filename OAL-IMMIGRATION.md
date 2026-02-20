# Phase 2: OAL Build-Time Pre-Compilation — COMPLETE

## Context

OAL engine generates metrics, builder, and dispatcher classes at runtime via Javassist (`ClassPool.makeClass()` → `CtClass.toClass()`). GraalVM native image doesn't support runtime bytecode generation. Additionally, Guava's `ClassPath.from()` — used by `AnnotationScan.scan()` and `SourceReceiverImpl.scan()` — doesn't work in native image (no JAR-based classpath).

**Solution**: Run OAL engine at build time, export `.class` files + manifests. Replace upstream classes with same-FQCN versions that load from manifests instead of scanning or generating code.

---

## Step 1: Build-Time OAL Class Export Tool — DONE

**Module**: `build-tools/precompiler` (originally `build-tools/oal-exporter`, merged into unified precompiler)

**Created**: `OALClassExporter.java` — main class that:

1. Validates all 9 OAL script files are on the classpath
2. Initializes `DefaultScopeDefine` by scanning `@ScopeDeclaration` annotations (OAL enricher needs scope metadata)
3. For each of the 9 `OALDefine` configs: instantiates `OALEngineV2`, enables debug output (`setOpenEngineDebug(true)` + `setGeneratedFilePath()`), calls `engine.start()` which parses OAL → enriches → generates `.class` files via Javassist
4. Scans the output directory for generated `.class` files and writes OAL manifests:
   - `META-INF/oal-metrics-classes.txt` — ~620 fully-qualified class names
   - `META-INF/oal-dispatcher-classes.txt` — ~45 fully-qualified class names
   - `META-INF/oal-disabled-sources.txt` — disabled source names from `disable.oal`
5. Runs Guava `ClassPath.from()` scan at build time to produce 6 annotation/interface manifests under `META-INF/annotation-scan/`:
   - `ScopeDeclaration.txt` — classes annotated with `@ScopeDeclaration`
   - `Stream.txt` — classes annotated with `@Stream` (hardcoded only, not OAL-generated)
   - `Disable.txt` — classes annotated with `@Disable`
   - `MultipleDisable.txt` — classes annotated with `@MultipleDisable`
   - `SourceDispatcher.txt` — concrete implementations of `SourceDispatcher` interface (hardcoded only)
   - `ISourceDecorator.txt` — concrete implementations of `ISourceDecorator` interface

**Key difference from original plan**: No "collecting listeners" needed. `engine.start()` generates `.class` files directly to disk via the debug API. We scan the output directory for class files rather than hooking into engine callbacks.

### 9 OAL Defines processed

| Define | Config File | Source Package | Catalog |
|--------|-------------|----------------|---------|
| `DisableOALDefine` | `oal/disable.oal` | `core.source` | — |
| `CoreOALDefine` | `oal/core.oal` | `core.source` | — |
| `JVMOALDefine` | `oal/java-agent.oal` | `core.source` | — |
| `CLROALDefine` | `oal/dotnet-agent.oal` | `core.source` | — |
| `BrowserOALDefine` | `oal/browser.oal` | `core.browser.source` | — |
| `MeshOALDefine` | `oal/mesh.oal` | `core.source` | `ServiceMesh` |
| `EBPFOALDefine` | `oal/ebpf.oal` | `core.source` | — |
| `TCPOALDefine` | `oal/tcp.oal` | `core.source` | `EnvoyTCP` |
| `CiliumOALDefine` | `oal/cilium.oal` | `core.source` | — |

### Generated class packages

- Metrics: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.*Metrics`
- Builders: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.*MetricsBuilder`
- Dispatchers: `org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.[catalog].*Dispatcher`

---

## Step 2: Runtime Registration via Same-FQCN Replacement Classes — DONE

Instead of extending upstream classes or hooking via `ModuleWiringBridge`, we use **same-FQCN replacement**: create classes in `oap-graalvm-server` with the exact same fully-qualified class name as the upstream class. Maven classpath precedence ensures our version is loaded instead of the upstream version.

### 3 replacement classes created:

**1. `OALEngineLoaderService`** (`oap-graalvm-server/.../core/oal/rt/OALEngineLoaderService.java`)

Same FQCN as upstream `org.apache.skywalking.oap.server.core.oal.rt.OALEngineLoaderService`. On first `load()` call:
- Reads `META-INF/oal-disabled-sources.txt` → registers with `DisableRegister`
- Reads `META-INF/oal-metrics-classes.txt` → `Class.forName()` → `StreamAnnotationListener.notify()`
- Reads `META-INF/oal-dispatcher-classes.txt` → `Class.forName()` → `DispatcherDetectorListener.addIfAsSourceDispatcher()`
- All subsequent `load()` calls are no-ops (all classes registered on first call regardless of which `OALDefine` triggered it)

**2. `AnnotationScan`** (`oap-graalvm-server/.../core/annotation/AnnotationScan.java`)

Same FQCN as upstream `org.apache.skywalking.oap.server.core.annotation.AnnotationScan`. Instead of Guava `ClassPath.from()` scanning, reads manifest files from `META-INF/annotation-scan/{AnnotationSimpleName}.txt`. Each registered `AnnotationListener` is matched against its corresponding manifest.

**3. `SourceReceiverImpl`** (`oap-graalvm-server/.../core/source/SourceReceiverImpl.java`)

Same FQCN as upstream `org.apache.skywalking.oap.server.core.source.SourceReceiverImpl`. `scan()` reads from `META-INF/annotation-scan/SourceDispatcher.txt` and `META-INF/annotation-scan/ISourceDecorator.txt` instead of Guava classpath scanning.

### Key differences from original plan:
- **No extending** — same-FQCN replacement instead of subclassing
- **No `ModuleWiringBridge` changes** — classpath precedence handles the swap automatically
- **3 replacement classes, not 1** — `AnnotationScan` and `SourceReceiverImpl` also needed replacement
- **Classpath scanning fully eliminated** — not deferred to Phase 3; annotation manifests solve it now

---

## Step 3: Class Loading and Remaining Scans — DONE

### `Class.forName()` in native image
`Class.forName()` is supported in GraalVM native image when classes are registered in `reflect-config.json`. Since all pre-generated classes are on the classpath at native-image build time, the GraalVM compiler includes them in the binary. The `reflect-config.json` entries (Phase 3 task) will enable runtime `Class.forName()` lookup. For Phase 2 (JVM mode), `Class.forName()` works naturally.

### OAL-internal scans — build-time only
The 3 OAL-internal scans (`MetricsHolder`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`) only run inside the OAL engine during `engine.start()`. They happen at **build time** in `OALClassExporter`, not at runtime. Automatically solved.

### `MeterSystem` — deferred to MAL immigration
`MeterSystem` uses Guava `ClassPath.from()` to discover meter function classes at runtime. This is part of the MAL/meter subsystem, not OAL. Will be addressed as part of MAL immigration (Phase 2 remaining work).

### `reflect-config.json` — deferred to Phase 3
GraalVM reflection configuration for `Class.forName()` calls on OAL-generated and manifest-listed classes will be generated in Phase 3.

---

## Same-FQCN Replacements (OAL)

| Upstream Class | Upstream Location | Replacement Location | What Changed |
|---|---|---|---|
| `OALEngineLoaderService` | `server-core/.../oal/rt/OALEngineLoaderService.java` | `oap-libs-for-graalvm/server-core-for-graalvm/` | Complete rewrite. Loads pre-compiled OAL classes from build-time manifests instead of running ANTLR4 + FreeMarker + Javassist at runtime. |
| `AnnotationScan` | `server-core/.../annotation/AnnotationScan.java` | `oap-libs-for-graalvm/server-core-for-graalvm/` | Complete rewrite. Reads `META-INF/annotation-scan/{name}.txt` manifests instead of Guava `ClassPath.from()` scanning. |
| `SourceReceiverImpl` | `server-core/.../source/SourceReceiverImpl.java` | `oap-libs-for-graalvm/server-core-for-graalvm/` | Complete rewrite. Reads dispatcher/decorator manifests instead of Guava `ClassPath.from()` scanning. |

All three replacements are repackaged into `server-core-for-graalvm` via `maven-shade-plugin` — the original `.class` files are excluded from the shaded JAR.

---

## Files Created

1. **`build-tools/oal-exporter/src/main/java/.../OALClassExporter.java`**
   - Build-time tool: runs 9 OAL defines, exports `.class` files, writes OAL manifests + annotation/interface manifests

2. **`oap-libs-for-graalvm/server-core-for-graalvm/src/main/java/.../core/oal/rt/OALEngineLoaderService.java`**
   - Same-FQCN replacement: loads pre-compiled OAL classes from manifests

3. **`oap-libs-for-graalvm/server-core-for-graalvm/src/main/java/.../core/annotation/AnnotationScan.java`**
   - Same-FQCN replacement: reads annotation manifests instead of Guava classpath scanning

4. **`oap-libs-for-graalvm/server-core-for-graalvm/src/main/java/.../core/source/SourceReceiverImpl.java`**
   - Same-FQCN replacement: reads dispatcher/decorator manifests instead of Guava classpath scanning

5. **`build-tools/oal-exporter/src/test/java/.../OALClassExporterTest.java`**
   - 3 tests: OAL script coverage, classpath availability, full export with manifest verification

6. **`oap-graalvm-server/src/test/java/.../PrecompiledRegistrationTest.java`**
   - 12 tests: manifest vs Guava scan comparison, OAL class loading, scope registration, source→dispatcher→metrics chain consistency

## Files Modified

1. **`build-tools/oal-exporter/pom.xml`** — dependencies + `exec-maven-plugin` + `maven-jar-plugin`
2. **`oap-graalvm-server/pom.xml`** — dependency on `oal-exporter` generated JAR

## Key Upstream Files (read-only)

- `OALEngineV2.java` — `start()` (parse → enrich → generate), `notifyAllListeners()` (register)
- `OALClassGeneratorV2.java` — `setOpenEngineDebug(true)`, `setGeneratedFilePath()`, `writeGeneratedFile()` exports via `ctClass.toBytecode()`
- `OALEngineLoaderService.java` (upstream) — `load()` creates engine, sets listeners, calls `start()`+`notifyAllListeners()`
- `StorageBuilderFactory.java:67-78` — `Default` impl uses `metrics-builder` template path
- `StreamAnnotationListener.java` — `notify(Class)` reads `@Stream`, routes to `MetricsStreamProcessor.create()`
- `CoreModuleProvider.java:356-357` — registers `OALEngineLoaderService` in `prepare()`
- `CoreModuleProvider.java:417-421` — `start()` calls `load(DisableOALDefine)` then `scan()`

---

## Verification

```bash
# 1. Build everything
make build-distro

# 2. Check generated classes exist
ls build-tools/oal-exporter/target/generated-oal-classes/org/apache/skywalking/oap/server/core/source/oal/rt/metrics/
ls build-tools/oal-exporter/target/generated-oal-classes/org/apache/skywalking/oap/server/core/source/oal/rt/dispatcher/

# 3. Check manifest files
cat build-tools/oal-exporter/target/generated-oal-classes/META-INF/oal-metrics-classes.txt
cat build-tools/oal-exporter/target/generated-oal-classes/META-INF/oal-dispatcher-classes.txt

# 4. Check annotation scan manifests
ls build-tools/oal-exporter/target/generated-oal-classes/META-INF/annotation-scan/

# 5. Verify tests pass
make build-distro   # runs both OALClassExporterTest and PrecompiledRegistrationTest
```
