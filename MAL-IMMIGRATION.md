# Phase 2: MAL/LAL Build-Time Pre-Compilation — COMPLETE

## Context

MAL (Meter Analysis Language) and LAL (Log Analysis Language) have three GraalVM-incompatible runtime patterns:

1. **MeterSystem ClassPath scanning**: `MeterSystem` constructor uses Guava `ClassPath.from()` to discover `@MeterFunction`-annotated classes (16 meter functions).
2. **MeterSystem Javassist dynamic class generation**: `MeterSystem.create()` uses Javassist `ClassPool.makeClass()` to create one dynamic meter subclass per metric rule at runtime (~1188 rules across all MAL sources). Classes live in `org.apache.skywalking.oap.server.core.analysis.meter.dynamic.*`.
3. **Groovy runtime compilation**: MAL uses `GroovyShell.parse()` with dynamic Groovy features. LAL uses `GroovyShell.parse()` with `@CompileStatic`. Both compile scripts at startup.

**Key architectural difference from OAL**: MAL initialization is a tightly coupled pipeline where Groovy compilation, static analysis, and Javassist generation are interleaved:

```
Rule YAML → MetricConvert constructor → Analyzer.build()
  → DSL.parse()               [Groovy compilation]
  → e.parse()                  [static analysis → scopeType, functionName, metricType]
  → meterSystem.create()       [Javassist class generation + MetricsStreamProcessor registration]
```

The build tool must execute this entire chain — Groovy pre-compilation and Javassist pre-generation cannot be separated.

**Solution**: Run the full MAL/LAL initialization at build time. Export Javassist-generated `.class` files + compiled Groovy script bytecode. At runtime, load pre-generated classes from manifests — no ClassPath scanning, no Javassist, no Groovy compilation.

---

## Rule file inventory

| Source | Path | Loader | Files | Metric Rules |
|--------|------|--------|-------|--------------|
| Agent meter | `meter-analyzer-config/` | `MeterConfigs.loadConfig()` | 11 | ~147 |
| OTel metrics | `otel-rules/` | `Rules.loadRules()` | 55 | ~1039 |
| Log MAL | `log-mal-rules/` | `Rules.loadRules()` | 2 | ~2 |
| LAL scripts | `lal/` | `LALConfigs.load()` | 8 | 10 |
| **Total** | | | **76** | **~1198** |

Each MAL metric rule generates one Groovy script + one Javassist dynamic meter class. Each LAL rule generates one Groovy script.

---

## Step 1: MeterFunction Manifest + Same-FQCN MeterSystem

### Problem

`MeterSystem` constructor (`MeterSystem.java:75-96`) scans the classpath:

```java
ClassPath classpath = ClassPath.from(MeterSystem.class.getClassLoader());
ImmutableSet<ClassPath.ClassInfo> classes = classpath.getTopLevelClassesRecursive("org.apache.skywalking");
for (ClassPath.ClassInfo classInfo : classes) {
    Class<?> functionClass = classInfo.load();
    if (functionClass.isAnnotationPresent(MeterFunction.class)) {
        functionRegister.put(metricsFunction.functionName(), functionClass);
    }
}
```

### 16 Meter Function classes

| Function Name | Class | Accept Type |
|---|---|---|
| `avg` | `AvgFunction` | `Long` |
| `avgLabeled` | `AvgLabeledFunction` | `DataTable` |
| `avgHistogram` | `AvgHistogramFunction` | `BucketedValues` |
| `avgHistogramPercentile` | `AvgHistogramPercentileFunction` | `PercentileArgument` |
| `latest` | `LatestFunction` | `Long` |
| `latestLabeled` | `LatestLabeledFunction` | `DataTable` |
| `max` | `MaxFunction` | `Long` |
| `maxLabeled` | `MaxLabeledFunction` | `DataTable` |
| `min` | `MinFunction` | `Long` |
| `minLabeled` | `MinLabeledFunction` | `DataTable` |
| `sum` | `SumFunction` | `Long` |
| `sumLabeled` | `SumLabeledFunction` | `DataTable` |
| `sumHistogram` | `HistogramFunction` | `BucketedValues` |
| `sumHistogramPercentile` | `SumHistogramPercentileFunction` | `PercentileArgument` |
| `sumPerMin` | `SumPerMinFunction` | `Long` |
| `sumPerMinLabeled` | `SumPerMinLabeledFunction` | `DataTable` |

### Approach

1. Extend `OALClassExporter` to scan `@MeterFunction` annotation at build time. Write `META-INF/annotation-scan/MeterFunction.txt` with format `functionName=FQCN` (one entry per line).

2. Create same-FQCN replacement `MeterSystem` in `oap-graalvm-server` that reads function registry from the manifest instead of `ClassPath.from()`:

```java
// Pseudocode for replacement MeterSystem constructor
public MeterSystem(ModuleManager manager) {
    this.manager = manager;
    this.classPool = ClassPool.getDefault();

    // Read from manifest instead of ClassPath.from()
    for (String line : readManifest("META-INF/annotation-scan/MeterFunction.txt")) {
        String[] parts = line.split("=", 2);
        String functionName = parts[0];
        Class<?> functionClass = Class.forName(parts[1]);
        functionRegister.put(functionName, (Class<? extends AcceptableValue>) functionClass);
    }
}
```

At this step, `create()` still uses Javassist — this is fine for JVM mode. Step 2 eliminates it.

---

## Step 2: Build-Time MAL/LAL Pre-Compilation + MeterSystem Class Generation

**Module**: `build-tools/mal-compiler` (skeleton exists)

### MALCompiler.java — main build tool

The tool executes the full initialization pipeline at build time:

#### Phase A: Initialize infrastructure

```java
// 1. Initialize scope registry (same as OALClassExporter)
DefaultScopeDefine.reset();
AnnotationScan scopeScan = new AnnotationScan();
scopeScan.registerListener(new DefaultScopeDefine.Listener());
scopeScan.scan();

// 2. Create ExportingMeterSystem — intercepts Javassist to export .class files
ExportingMeterSystem meterSystem = new ExportingMeterSystem(outputDir);
```

#### Phase B: Load and compile all MAL rules

```java
// 3. Agent meter rules (meter-analyzer-config/)
List<MeterConfig> agentConfigs = MeterConfigs.loadConfig("meter-analyzer-config", activeFiles);
for (MeterConfig config : agentConfigs) {
    new MetricConvert(config, meterSystem);  // triggers: Groovy compile + parse + Javassist
}

// 4. OTel rules (otel-rules/)
List<Rule> otelRules = Rules.loadRules("otel-rules", enabledOtelRules);
for (Rule rule : otelRules) {
    new MetricConvert(rule, meterSystem);
}

// 5. Log MAL rules (log-mal-rules/)
List<Rule> logMalRules = Rules.loadRules("log-mal-rules", enabledLogMalRules);
for (Rule rule : logMalRules) {
    new MetricConvert(rule, meterSystem);
}
```

#### Phase C: Load and compile all LAL rules

```java
// 6. LAL rules (lal/)
List<LALConfigs> lalConfigs = LALConfigs.load("lal", lalFiles);
for (LALConfig config : flattenedConfigs) {
    DSL.of(moduleManager, logConfig, config.getDsl());  // Groovy compile with @CompileStatic
}
```

#### Phase D: Export bytecode + write manifests

The tool intercepts both Groovy and Javassist class generation to capture bytecode:

**Javassist interception** (`ExportingMeterSystem`): Override `create()` to call `ctClass.toBytecode()` and write `.class` files to the output directory. Also records metadata for the manifest.

**Groovy script capture**: Use Groovy's `CompilationUnit` API or intercept `GroovyShell.parse()` to extract compiled script bytecode. Each MAL expression compiles to a unique script class (name based on hash or sequential index).

**Manifest files**:

`META-INF/mal-meter-classes.txt` — Javassist-generated meter classes:
```
# format: metricsName|scopeId|functionName|dataType|FQCN
meter_java_agent_created_tracing_context_count|1|sum|java.lang.Long|org.apache.skywalking.oap.server.core.analysis.meter.dynamic.meter_java_agent_created_tracing_context_count
...
```

`META-INF/mal-groovy-scripts.txt` — Pre-compiled MAL Groovy scripts:
```
# format: metricName=scriptClassName
meter_java_agent_created_tracing_context_count=org.apache.skywalking.oap.server.core.analysis.meter.script.Script0001
...
```

`META-INF/lal-scripts.txt` — Pre-compiled LAL Groovy scripts:
```
# format: layer:ruleName=scriptClassName
GENERAL:default=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0001
NGINX:nginx-access-log=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0002
...
```

### ExportingMeterSystem — Javassist interception

This is a build-time variant of `MeterSystem` that exports bytecode instead of loading classes:

```java
// Pseudocode
public class ExportingMeterSystem extends MeterSystem {
    private final Path outputDir;
    private final List<MeterClassMetadata> metadata = new ArrayList<>();

    @Override
    public synchronized <T> void create(String metricsName, String functionName,
                                         ScopeType type, Class<T> dataType) {
        // Same Javassist logic as upstream MeterSystem.create()
        CtClass metricsClass = classPool.makeClass(METER_CLASS_PACKAGE + className, parentClass);
        // ... add constructor and createNew() method ...

        // Instead of toClass(), write bytecode to disk
        byte[] bytecode = metricsClass.toBytecode();
        Path classFile = outputDir.resolve(packageToPath(METER_CLASS_PACKAGE + className) + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytecode);

        // Record metadata for manifest
        metadata.add(new MeterClassMetadata(metricsName, type.getScopeId(),
            functionName, dataType.getName(), METER_CLASS_PACKAGE + className));
    }
}
```

### Same-FQCN replacement classes

**1. MAL `DSL`** (`oap-graalvm-server/.../meter/analyzer/dsl/DSL.java`)

Same FQCN as `org.apache.skywalking.oap.meter.analyzer.dsl.DSL`. `parse()` loads a pre-compiled Groovy script class instead of calling `GroovyShell.parse()`:

```java
// Pseudocode
public static Expression parse(String metricName, String expression) {
    // Look up pre-compiled script class from manifest
    String scriptClassName = lookupScript(metricName);
    Class<?> scriptClass = Class.forName(scriptClassName);
    DelegatingScript script = (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();

    // Same CompilerConfiguration setup (imports, security) is baked into the pre-compiled class
    return new Expression(metricName, expression, script);
}
```

The `Expression.empower()` method still runs at runtime — it calls `setDelegate()` and `ExpandoMetaClass` registration. These are Java API calls on the Groovy runtime, not compilation.

**2. `FilterExpression`** (`oap-graalvm-server/.../meter/analyzer/dsl/FilterExpression.java`)

Same FQCN. Loads pre-compiled filter closure class instead of `GroovyShell.evaluate()`.

**3. LAL `DSL`** (`oap-graalvm-server/.../log/analyzer/dsl/DSL.java`)

Same FQCN as `org.apache.skywalking.oap.log.analyzer.dsl.DSL`. `of()` loads pre-compiled LAL script class (already `@CompileStatic`) instead of calling `GroovyShell.parse()`:

```java
// Pseudocode
public static DSL of(ModuleManager moduleManager, LogAnalyzerModuleConfig config, String dsl) {
    String scriptClassName = lookupLALScript(layer, ruleName);
    Class<?> scriptClass = Class.forName(scriptClassName);
    DelegatingScript script = (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();
    FilterSpec filterSpec = new FilterSpec(moduleManager, config);
    script.setDelegate(filterSpec);
    return new DSL(script, filterSpec);
}
```

**4. `MeterSystem`** (enhanced from Step 1)

The `create()` method becomes a manifest lookup + `MetricsStreamProcessor` registration:

```java
// Pseudocode
public synchronized <T> void create(String metricsName, String functionName,
                                     ScopeType type, Class<T> dataType) {
    if (meterPrototypes.containsKey(metricsName)) {
        return; // already registered
    }

    // Load pre-generated class from classpath
    MeterClassMetadata meta = lookupMeterClass(metricsName);
    Class<?> targetClass = Class.forName(meta.fqcn);
    AcceptableValue prototype = (AcceptableValue) targetClass.getDeclaredConstructor().newInstance();
    meterPrototypes.put(metricsName, new MeterDefinition(type, prototype, dataType));

    // Register with stream processor (same as upstream)
    MetricsStreamProcessor.getInstance().create(
        manager,
        new StreamDefinition(metricsName, type.getScopeId(), prototype.builder(),
            MetricsStreamProcessor.class),
        targetClass
    );
}
```

`buildMetrics()` and `doStreamingCalculation()` work unchanged — they use the prototype map.

### MAL Groovy: Why @CompileStatic is NOT possible

MAL expressions rely on three dynamic Groovy features:

1. **`propertyMissing(String)`** (`Expression.java:126`): When an expression references `counter` or `jvm_memory_bytes_used`, Groovy calls `ExpressionDelegate.propertyMissing(sampleName)` which looks up the sample family from a `ThreadLocal<Map<String, SampleFamily>>`. Static compilation cannot resolve these properties.

2. **`ExpandoMetaClass` on `Number`** (`Expression.java:104-111`): The `empower()` method registers `plus`, `minus`, `multiply`, `div` closures on `Number.class` to allow expressions like `100 * server_cpu_seconds`. This is runtime metaclass manipulation.

3. **Closure arguments** in DSL methods: Expressions like `.tag({tags -> tags.gc = 'young_gc'})` and `.filter({tags -> tags.job_name == 'mysql'})` pass Groovy closures with dynamic property access.

**Approach**: Pre-compile using standard dynamic Groovy (same `CompilerConfiguration` as upstream — `DelegatingScript` base class, `SecureASTCustomizer`, `ImportCustomizer`). The compiled `.class` files contain the same bytecode that `GroovyShell.parse()` would produce. At runtime, `Class.forName()` + `newInstance()` loads the pre-compiled script, and `Expression.empower()` sets up the delegate and `ExpandoMetaClass`.

**GraalVM native image risk** (Phase 3 concern): Dynamic Groovy compiled classes use `invokedynamic` and Groovy's MOP (Meta-Object Protocol) for method resolution. In native image, this may need:
- `reflect-config.json` for all compiled script classes
- Groovy MOP runtime classes registered for reachability
- If `ExpandoMetaClass` does not work in native image: fallback to upstream DSL changes (replace operator overloading with explicit `SampleFamily.multiply(100, sf)` calls)

Phase 2 focuses on JVM mode — pre-compilation for startup speed. Phase 3 addresses native image compatibility.

### LAL Groovy: Already @CompileStatic

LAL already uses `@CompileStatic` with `LALPrecompiledExtension` for type checking. The `CompilationUnit` approach works directly. Compiled scripts extend `LALDelegatingScript` and are fully statically typed.

---

## Step 3: Verification Tests

### MALCompilerTest (in `build-tools/mal-compiler/src/test/`)

```
- allRuleYamlFilesLoadable:
    Verify all 76 rule files exist on classpath

- fullCompilationGeneratesExpectedCounts:
    Run MALCompiler.main(), verify:
    - Generated meter .class count > 0 for each rule source
    - Groovy script .class count matches rule count
    - All 3 manifest files exist and are non-empty

- manifestEntriesAreWellFormed:
    Parse manifest files, verify format and field count per line
```

### MALPrecompiledRegistrationTest (in `oap-graalvm-server/src/test/`)

```
- meterFunctionManifestMatchesClasspath:
    Compare manifest against Guava ClassPath scan of @MeterFunction
    (same pattern as PrecompiledRegistrationTest for OAL)

- all16MeterFunctionsInManifest:
    Verify all 16 known function names appear

- precompiledMeterClassesLoadable:
    For each entry in mal-meter-classes.txt:
    - Class.forName() succeeds
    - Class extends the correct meter function parent

- precompiledGroovyScriptsLoadable:
    For each entry in mal-groovy-scripts.txt:
    - Class.forName() succeeds
    - Class is assignable to DelegatingScript

- precompiledLALScriptsLoadable:
    For each entry in lal-scripts.txt:
    - Class.forName() succeeds
    - Class is assignable to LALDelegatingScript
```

---

## Same-FQCN Replacements (MAL)

| Upstream Class | Upstream Location | Replacement Location | What Changed |
|---|---|---|---|
| `MeterSystem` | `server-core/.../analysis/meter/MeterSystem.java` | `oap-libs-for-graalvm/server-core-for-graalvm/` | Complete rewrite. Reads `@MeterFunction` classes from `META-INF/annotation-scan/MeterFunction.txt` manifest instead of Guava `ClassPath.from()`. Loads pre-generated Javassist meter classes from classpath instead of runtime `ClassPool.makeClass()`. |
| `DSL` (MAL) | `analyzer/meter-analyzer/.../dsl/DSL.java` | `oap-libs-for-graalvm/meter-analyzer-for-graalvm/` | Complete rewrite. Loads pre-compiled Groovy `DelegatingScript` classes from `META-INF/mal-groovy-scripts.txt` manifest instead of `GroovyShell.parse()` runtime compilation. |
| `FilterExpression` | `analyzer/meter-analyzer/.../dsl/FilterExpression.java` | `oap-libs-for-graalvm/meter-analyzer-for-graalvm/` | Complete rewrite. Loads pre-compiled Groovy filter closure classes from `META-INF/mal-filter-scripts.properties` manifest instead of `GroovyShell.evaluate()` runtime compilation. |

All replacements are repackaged into their respective `-for-graalvm` modules via `maven-shade-plugin` — the original `.class` files are excluded from the shaded JARs.

---

## Files Created

1. **`build-tools/precompiler/`** (unified, replaces separate `oal-exporter` + `mal-compiler`)
   - Main build tool: loads all MAL/LAL rules, runs full initialization pipeline, exports .class files + manifests

2. **`oap-libs-for-graalvm/server-core-for-graalvm/src/main/java/.../core/analysis/meter/MeterSystem.java`**
   - Same-FQCN replacement: reads function registry from manifest, loads pre-generated classes in `create()`

3. **`oap-libs-for-graalvm/meter-analyzer-for-graalvm/src/main/java/.../meter/analyzer/dsl/DSL.java`**
   - Same-FQCN replacement: loads pre-compiled MAL Groovy scripts from manifest

4. **`oap-libs-for-graalvm/meter-analyzer-for-graalvm/src/main/java/.../meter/analyzer/dsl/FilterExpression.java`**
   - Same-FQCN replacement: loads pre-compiled filter closures from manifest

5. **`oap-graalvm-server/src/test/java/.../graalvm/PrecompiledMALExecutionTest.java`**
   - Runtime registration and loading tests

6. **`oap-graalvm-server/src/test/java/.../graalvm/mal/`** — 73 comparison test classes covering all 71 MAL YAML files

## Key Upstream Files (read-only)

- `MeterSystem.java` — ClassPath scan (`constructor:75-96`) + Javassist class gen (`create():180-259`)
- `DSL.java` (meter-analyzer) — MAL Groovy compilation: `GroovyShell.parse()` with `DelegatingScript`, `SecureASTCustomizer`
- `Expression.java` — Script execution: `DelegatingScript.run()`, `ExpandoMetaClass` on Number, `ExpressionDelegate.propertyMissing()`
- `FilterExpression.java` — Filter closure compilation: `GroovyShell.evaluate()`
- `Analyzer.java` — Initialization chain: `build()` → `DSL.parse()` → `e.parse()` → `meterSystem.create()`
- `MetricConvert.java` — Rule → Analyzer creation: `new MetricConvert(rule, meterSystem)` triggers full pipeline
- `DSL.java` (log-analyzer) — LAL Groovy compilation: `@CompileStatic` + `LALPrecompiledExtension`
- `LALDelegatingScript.java` — LAL script base class: `filter()`, `json()`, `text()`, `extractor()`, `sink()`
- `LogFilterListener.java` — LAL DSL factory: `DSL.of()` for each rule, stores in `Map<Layer, Map<String, DSL>>`
- `Rules.java` — OTel/log-MAL rule loading: `loadRules(path, enabledRules)`
- `MeterConfigs.java` — Agent meter rule loading: `loadConfig(path, fileNames)`
- `LALConfigs.java` — LAL rule loading: `load(path, files)`
- `MeterFunction.java` — Annotation: `@MeterFunction(functionName = "...")` on meter function classes
- `AcceptableValue.java` — Interface all meter functions implement
- `MeterClassPackageHolder.java` — Package anchor for Javassist-generated classes

---

## Verification

```bash
# 1. Build everything
make build-distro

# 2. Check generated meter classes exist
ls build-tools/mal-compiler/target/generated-mal-classes/org/apache/skywalking/oap/server/core/analysis/meter/dynamic/

# 3. Check Groovy script classes exist
ls build-tools/mal-compiler/target/generated-mal-classes/org/apache/skywalking/oap/server/core/analysis/meter/script/

# 4. Check manifest files
cat build-tools/mal-compiler/target/generated-mal-classes/META-INF/mal-meter-classes.txt | wc -l
cat build-tools/mal-compiler/target/generated-mal-classes/META-INF/mal-groovy-scripts.txt | wc -l
cat build-tools/mal-compiler/target/generated-mal-classes/META-INF/lal-scripts.txt | wc -l

# 5. Check MeterFunction manifest (produced by oal-exporter)
cat build-tools/oal-exporter/target/generated-oal-classes/META-INF/annotation-scan/MeterFunction.txt

# 6. Verify tests pass
make build-distro  # runs MALCompilerTest + MALPrecompiledRegistrationTest
```
