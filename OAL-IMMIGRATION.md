# Phase 2: OAL Build-Time Pre-Compilation

## Context

OAL engine generates metrics, builder, and dispatcher classes at runtime via Javassist (`ClassPool.makeClass()` → `CtClass.toClass()`). GraalVM native image doesn't support runtime bytecode generation. Additionally, Guava's `ClassPath.from()` — used by `AnnotationScan.scan()` and `SourceReceiverImpl.scan()` — doesn't work in native image (no JAR-based classpath). So we cannot rely on classpath scanning to discover pre-generated classes.

**Solution**: Run OAL engine at build time, export `.class` files + a manifest of class names. At runtime, load classes by name from the manifest and register them **directly** with `StreamAnnotationListener` and `DispatcherManager` — no classpath scanning.

---

## Step 1: Build-Time OAL Class Export Tool

**Module**: `build-tools/oal-exporter` (skeleton exists)

**Create**: `OALClassExporter.java` — main class that:

1. For each of the 9 `OALDefine` configs: instantiate `OALEngineV2`, set `StorageBuilderFactory.Default`, call `engine.start()` (Javassist generates classes in the build JVM)
2. Export bytecode via existing debug mechanism: `generator.setOpenEngineDebug(true)` + `OALClassGeneratorV2.setGeneratedFilePath(outputDir)` — `writeGeneratedFile()` writes `.class` files using `ctClass.toBytecode(DataOutputStream)`
3. After each engine run, call `engine.notifyAllListeners()` with **collecting listeners** that record class names (not real registration)
4. Write manifest files:
   - `META-INF/oal-metrics-classes.txt` — one fully-qualified class name per line
   - `META-INF/oal-dispatcher-classes.txt` — one fully-qualified class name per line
   - `META-INF/oal-disabled-sources.txt` — one source name per line
5. Package output directory into a JAR artifact

**Note on listeners**: `engine.start()` already generates and loads classes, storing them in internal `metricsClasses`/`dispatcherClasses` lists. `notifyAllListeners()` iterates these lists. We provide lightweight listeners that just collect class names for the manifest, not real stream processors.

**Dependencies** for `oal-exporter/pom.xml`:
- `oal-rt` — engine, generator, parser, enricher, FreeMarker templates
- `server-core` — `OALDefine` subclasses, source classes, annotations, `StorageBuilderFactory`
- `server-starter` — OAL script resource files (`oal/*.oal`)
- Receiver plugins that define `OALDefine` subclasses (for `BrowserOALDefine`, `MeshOALDefine`, etc.)

**Build integration**: `exec-maven-plugin` runs `OALClassExporter.main()` during `process-classes` phase. Then `maven-jar-plugin` with a custom classifier packages the generated `.class` files + manifest into `oal-generated-classes.jar`.

### 9 OAL Defines to process

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

## Step 2: Runtime Direct Registration (No Classpath Scanning)

**Create**: `PrecompiledOALEngineLoaderService.java` in `oap-graalvm-server`

Extends `OALEngineLoaderService`. Overrides `load()` to:
1. On first invocation: read manifest files from classpath resources
2. For each metrics class name: `Class.forName(name)` → `streamAnnotationListener.notify(clazz)` (same as `StreamAnnotationListener` — routes to `MetricsStreamProcessor.create()`)
3. For each dispatcher class name: `Class.forName(name)` → `dispatcherDetectorListener.addIfAsSourceDispatcher(clazz)` (same as `DispatcherManager.addIfAsSourceDispatcher()`)
4. For each disabled source: `DisableRegister.INSTANCE.add(name)`
5. All subsequent `load()` calls → no-op (classes already registered)

```
// Pseudocode
public void load(OALDefine define) throws ModuleStartException {
    if (registered) return;
    registered = true;

    StreamAnnotationListener streamListener = new StreamAnnotationListener(moduleManager);
    DispatcherDetectorListener dispatcherListener = moduleManager.find(CoreModule.NAME)
        .provider().getService(SourceReceiver.class).getDispatcherDetectorListener();

    for (String className : readManifest("META-INF/oal-metrics-classes.txt")) {
        streamListener.notify(Class.forName(className));
    }
    for (String className : readManifest("META-INF/oal-dispatcher-classes.txt")) {
        dispatcherListener.addIfAsSourceDispatcher(Class.forName(className));
    }
    for (String source : readManifest("META-INF/oal-disabled-sources.txt")) {
        DisableRegister.INSTANCE.add(source);
    }
}
```

**Wiring**: Replace the default `OALEngineLoaderService` after `CoreModuleProvider.prepare()`. `registerServiceImplementation()` uses `HashMap.put()` — re-registration overwrites. Add a post-prepare hook in `ModuleWiringBridge` for `CoreModuleProvider` to swap in `PrecompiledOALEngineLoaderService`.

**Timing**: `CoreModuleProvider.start()` calls `oalEngineLoaderService.load(DisableOALDefine.INSTANCE)` first, then `annotationScan.scan()` and `receiver.scan()`. Our `PrecompiledOALEngineLoaderService.load()` runs first and registers ALL pre-generated classes (not just disable.oal). The subsequent `annotationScan.scan()` and `receiver.scan()` will also run, but for Phase 2 (JVM mode), they work fine via `ClassPath.from()` and simply re-discover static classes. The OAL classes are already registered, so duplicates are harmless (stream processors check for duplicates). In Phase 3 (native image), these scans will be replaced entirely.

---

## Step 3: Why Class.forName() Works for Native Image

`Class.forName()` is supported in GraalVM native image when classes are registered in `reflect-config.json`. Since all pre-generated classes are on the classpath at native-image build time, the GraalVM compiler includes them in the binary. The `reflect-config.json` entries (Phase 3 task) enable runtime `Class.forName()` lookup. For Phase 2 (JVM mode), `Class.forName()` works naturally.

The 3 OAL-internal scans (`MetricsHolder`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`) only run during OAL engine execution — they happen at **build time** in our tool, not at runtime. So they're automatically solved.

---

## Files to Create

1. **`build-tools/oal-exporter/src/main/java/org/apache/skywalking/oap/server/buildtools/oal/OALClassExporter.java`**
   - Main class: iterates 9 OAL defines, runs engine, exports bytecode + writes manifest files

2. **`oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/graalvm/PrecompiledOALEngineLoaderService.java`**
   - Extends `OALEngineLoaderService`, loads from manifest + direct registration

## Files to Modify

1. **`build-tools/oal-exporter/pom.xml`** — add dependencies, configure `exec-maven-plugin` + `maven-jar-plugin`
2. **`oap-graalvm-server/pom.xml`** — add dependency on `oal-exporter` generated JAR
3. **`oap-graalvm-server/.../ModuleWiringBridge.java`** — post-prepare hook to replace `OALEngineLoaderService` with `PrecompiledOALEngineLoaderService`
4. **`Makefile`** — ensure build order: skywalking → oal-exporter → oap-graalvm-server

## Key Upstream Files (read-only)

- `OALEngineV2.java` — `start()` (parse → enrich → generate), `notifyAllListeners()` (register)
- `OALClassGeneratorV2.java` — `setOpenEngineDebug(true)`, `setGeneratedFilePath()`, `writeGeneratedFile()` exports via `ctClass.toBytecode()`
- `OALEngineLoaderService.java` — `load()` creates engine, sets listeners, calls `start()`+`notifyAllListeners()`
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
ls build-tools/oal-exporter/target/generated-oal-classes/
# Should have: org/apache/skywalking/.../oal/rt/metrics/*.class
#              org/apache/skywalking/.../oal/rt/metrics/builder/*.class
#              org/apache/skywalking/.../oal/rt/dispatcher/*.class

# 3. Check manifest files
cat build-tools/oal-exporter/target/generated-oal-classes/META-INF/oal-metrics-classes.txt
cat build-tools/oal-exporter/target/generated-oal-classes/META-INF/oal-dispatcher-classes.txt
# Should list all generated class names

# 4. Verify JAR packaging
jar tf build-tools/oal-exporter/target/oal-generated-classes.jar

# 5. Verify oap-graalvm-server compiles with the generated JAR dependency
```