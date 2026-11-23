# OAL Build-Time Pre-Compilation

## Context

The OAL engine generates metrics, builder and dispatcher classes at runtime via Javassist (`ClassPool.makeClass()` →
`CtClass.toClass()`). GraalVM native image doesn't support runtime bytecode generation. Additionally, Guava's
`ClassPath.from()`, used by `AnnotationScan.scan()` and `SourceReceiverImpl.scan()`, doesn't work in native image (no
JAR-based classpath).

**Solution**: run the OAL engine at build time and export the `.class` files; record in the rule registry what each
script generated; replace upstream classes with same-FQCN versions that register the recorded classes or read
build-time manifests instead of generating code or scanning.

---

## Build Time: the precompiler's OAL pass

**Module**: `build-tools/precompiler` (`OalPass`; step 2 is `Precompiler.declareScopes`, step 5 `ClasspathScans`)

1. Discovers every `OALDefine` subclass on the precompiler's class path and takes its `public static INSTANCE`, the
   object providers load; a define without one fails the build. The rules overlay's excluded scripts are left out, and
   each script it adds gets a define of its own (`core.source` sources, dispatchers named
   `Overlay<script>_<Source>Dispatcher`). A staged `oal/*.oal` that no define names fails the build.
2. Initializes `DefaultScopeDefine` by scanning `@ScopeDeclaration` annotations (the OAL enricher needs scope metadata).
3. For each define: instantiates `OALEngineV2`, enables debug output (`setOpenEngineDebug(true)` +
   `setGeneratedFilePath()`), calls `engine.start()`, which parses the script, enriches it and generates the `.class`
   files via Javassist, then `engine.notifyAllListeners()`, which hands the metrics and dispatcher classes to recording
   listeners (`OalListenerRecorder`), in upstream's order, with each metrics class's `@Stream` storage builder. The
   script's disables come from upstream's `OALScriptParserV2`; together they must equal what the generators registered
   (`DisableRegister#scriptDisablesWithoutStream`, as no stream is defined at build time).
4. Writes the OAL build intermediates the rule registry and `reflect-config.json` are generated from (the generated jar
   leaves them out): `oal-scripts.txt` (per script: define, file, metrics, dispatchers, disables; and the excluded
   scripts), `oal-metrics-classes.txt`, `oal-dispatcher-classes.txt`, `oal-builder-classes.txt`,
   `oal-disabled-sources.txt`.
5. Runs Guava `ClassPath.from()` to write the annotation/interface manifests under `META-INF/annotation-scan/`
   (`ScopeDeclaration`, `Stream` and `SourceDispatcher` of the hand-written classes only, `Disable`,
   `MultipleDisable`, `ISourceDecorator`).

The rule registry gets an `Oal_<script>` class per script (an `OalScript`: define, metrics, dispatchers, disables,
whether the overlay added it), `CompiledRules.oalScripts()` and `CompiledRules.oalExcludedScripts()`.

### The bundled defines

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
| `GenAIOALDefine` | `oal/virtual-gen-ai.oal` | `core.source` | — |

### Generated class packages

- Metrics: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.*Metrics`
- Builders: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.*MetricsBuilder`
- Dispatchers: `org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.[catalog]*Dispatcher`

The OAL-internal scans (`MetricsHolder`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`) run inside the engine
during `engine.start()`, so only at build time.

---

## Runtime: same-FQCN replacements

All three are repackaged into `oap-libs-for-graalvm/server-core-for-graalvm` via `maven-shade-plugin`, the original
`.class` files excluded from the shaded JAR.

| Upstream Class | What the replacement does |
|---|---|
| `OALEngineLoaderService` | `load(define)` registers the define's compiled script when its provider loads it, as upstream's engine does: its disables (`DisableRegister#addFromScript`, skipped with upstream's warning when the stream already runs), then the metrics classes → `StreamAnnotationListener.notify()`, then the dispatchers → `DispatcherDetectorListener.addIfAsSourceDispatcher()`. The first load also registers, after its define (`oal/disable.oal` at boot, so its disables reach them), the scripts the rules overlay added, which no provider loads. A define the overlay excluded loads nothing; one neither compiled nor excluded fails the boot. `getLoadedDefines()` is a read-only view, as upstream's. |
| `AnnotationScan` | Reads `META-INF/annotation-scan/{AnnotationSimpleName}.txt`, written for every `AnnotationListener`'s annotation, instead of Guava `ClassPath.from()` scanning; a missing manifest or class fails the boot. |
| `SourceReceiverImpl` | `scan()` reads `META-INF/annotation-scan/SourceDispatcher.txt` and `ISourceDecorator.txt` instead of Guava classpath scanning. |

A turned-off module's script, or one its module loads only on a setting (`tcp.oal` with the Envoy TCP access log
analysis, `cilium.oal` with the Cilium fetcher), stays compiled and unused, as upstream leaves it unloaded.

`replacement-source-sha256.properties` pins `OALEngineLoaderService`, and `OALEngineV2` and `OALClassGeneratorV2`,
whose registration steps the replacement replays.

## Key Upstream Files (read-only)

- `OALEngineV2.java`: `start()` (parse → enrich → generate), `notifyAllListeners()` (register)
- `OALClassGeneratorV2.java`: `generateClassAtRuntime()` (the disables), `setOpenEngineDebug(true)`,
  `setGeneratedFilePath()`, `writeGeneratedFile()`
- `OALEngineLoaderService.java`: `load()` creates the engine, sets the listeners, calls `start()` and
  `notifyAllListeners()`
- `StreamAnnotationListener.java`: `notify(Class)` reads `@Stream`, marks the stream defined, routes to the stream
  processor
- `CoreModuleProvider.java`: registers `OALEngineLoaderService` in `prepare()`; `start()` loads `DisableOALDefine`
  before the annotation scans; `notifyAfterCompleted()` warns about script disables no stream carries

---

## Tests

- `OalPassTest`: discovery covers every staged script, a define without its `INSTANCE` and a script without a define
  fail the build. `PrecompilerTest`: the manifests list every generated class, and the annotation-scan manifests are
  written. `ClasspathScansTest`: the annotations the scan writes a manifest for are the ones upstream's
  `AnnotationListener`s listen for.
- `RegistryParityTest`: the registry's scripts hold the build's defines (the very objects providers load), classes
  and disables, and the overlay's excluded scripts.
- `OALEngineLoaderServiceTest`: per-define registration (disables first), a define no provider loads has no effect, the
  first load's overlay scripts, excluded and uncompiled defines.
- `PrecompiledRegistrationTest`: the OAL classes load and carry `@Stream` / `SourceDispatcher` with declared scopes.
- `ShippedStorageBuilderFactoryTest`: the shipped storage registers `StorageBuilderFactory.Default`, which the
  precompiler generates the builders with.

```bash
make build-distro
less build-tools/precompiler/target/generated-registry/org/apache/skywalking/oap/server/graalvm/rules/Oal_core.java
```
