# DSL Build-Time Pre-Compilation (OAL/MAL/LAL/Hierarchy)

## Context

SkyWalking OAP uses four DSL compilers that generate Java bytecode at startup via Javassist:

| DSL | Purpose | Compiled Interface | Generated Classes |
|-----|---------|-------------------|-------------------|
| **OAL** | Metrics aggregation rules | Metrics, Builder, Dispatcher classes | ~620 metrics + ~620 builders + ~45 dispatchers |
| **MAL** | Meter expression rules | `MalExpression` | ~1250 expression classes |
| **LAL** | Log analysis rules | `LalExpression` | ~10 expression classes (6 unique) |
| **Hierarchy** | Service hierarchy matching | `BiFunction<Service, Service, Boolean>` | ~4 rule classes |

All four share the same pipeline (since upstream PR #13723):
```
DSL text → ANTLR4 parse → Immutable AST → Javassist bytecode → .class
```

GraalVM native image cannot run Javassist at runtime. **Solution**: run all four compilers at build time via their exposed APIs (`setClassOutputDir()`, `setClassNameHint()`), capture `.class` files into the precompiler output JAR. At runtime, load pre-compiled classes instead of compiling.

---

## Build-Time Compilation

**Module**: `build-tools/precompiler`

The precompiler invokes each v2 compiler at build time, capturing generated `.class` files to the output directory. All classes are packaged into `precompiler-*-generated.jar`, which is on the native-image build classpath.

### OAL

Uses `OALEngineV2.start()` with `setOpenEngineDebug(true)` and `setGeneratedFilePath()` to export classes. Processes all 9 OAL defines:

| Define | Config File |
|--------|-------------|
| `DisableOALDefine` | `oal/disable.oal` |
| `CoreOALDefine` | `oal/core.oal` |
| `JVMOALDefine` | `oal/java-agent.oal` |
| `CLROALDefine` | `oal/dotnet-agent.oal` |
| `BrowserOALDefine` | `oal/browser.oal` |
| `MeshOALDefine` | `oal/mesh.oal` |
| `EBPFOALDefine` | `oal/ebpf.oal` |
| `TCPOALDefine` | `oal/tcp.oal` |
| `CiliumOALDefine` | `oal/cilium.oal` |

Generated class packages:
- Metrics: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.*`
- Builders: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.*`
- Dispatchers: `org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.*`

### MAL

Uses `MALClassGenerator` with `setClassOutputDir()` and `setClassNameHint()` for deterministic class naming.

```
For each MAL YAML rule file (71 files, ~1250 rules):
  1. Parse YAML → List<MetricsRule>
  2. For each rule:
     a. generator.setClassNameHint(sanitize(yamlSourceId + "_" + metricName))
     b. generator.setYamlSource(yamlSourceId)
     c. MalExpression malExpr = generator.compile(metricName, expression)
     d. .class file auto-written to classOutputDir
     e. Record mapping: sha256(expression)|metricName → FQCN
  3. Compile filter expressions the same way
  4. Write manifest: META-INF/mal-v2-expression-map.properties
```

Rule file sources:

| Source | Path | Files | Rules |
|--------|------|-------|-------|
| Agent meter | `meter-analyzer-config/` | 11 | ~147 |
| OTel metrics | `otel-rules/` | 55 | ~1039 |
| Log MAL | `log-mal-rules/` | 2 | ~2 |
| Envoy metrics | `envoy-metrics-rules/` | 2 | ~26 |
| Telegraf | `telegraf-rules/` | 1 | ~20 |
| Zabbix | `zabbix-rules/` | 1 | ~15 |

Generated class package: `org.apache.skywalking.oap.meter.analyzer.v2.compiler.rt.*`

### LAL

Uses `LALClassGenerator` with `setClassOutputDir()` and `setClassNameHint()`.

```
For each LAL YAML rule file (8 files, 10 rules):
  1. Parse YAML → List<LALConfig>
  2. For each rule:
     a. generator.setClassNameHint(sanitize(yamlSourceId + "_" + ruleName))
     b. generator.setYamlSource(yamlSourceId)
     c. generator.setExtraLogType(extraLogType)  // if needed
     d. LalExpression expr = generator.compile(dsl)
     e. .class file auto-written to classOutputDir
```

| File | Rules |
|------|-------|
| `default.yaml` | 1 |
| `nginx.yaml` | 2 |
| `mysql-slowsql.yaml` | 1 |
| `pgsql-slowsql.yaml` | 1 |
| `redis-slowsql.yaml` | 1 |
| `envoy-als.yaml` | 2 |
| `mesh-dp.yaml` | 1 |
| `k8s-service.yaml` | 1 |

Generated class package: `org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.*`

### Hierarchy

Uses `HierarchyRuleClassGenerator` with `setClassOutputDir()` and `setClassNameHint()`.

```
1. Load hierarchy-definition.yml
2. For each rule expression:
   a. generator.setClassNameHint(sanitize(ruleName))
   b. BiFunction result = generator.compile(ruleName, expression)
   c. .class file auto-written to classOutputDir
```

4 rule types: `name`, `short-name`, `lower-short-name-remove-namespace`, `lower-short-name-with-fqdn`.

Generated class package: `org.apache.skywalking.oap.server.core.config.v2.compiler.hierarchy.rule.rt.*`

---

## Runtime Loading via Same-FQCN Replacements

At runtime (native image or JVM distro), same-FQCN replacement classes load pre-compiled classes instead of running Javassist.

### OAL: `OALEngineLoaderService`

**Upstream**: `server-core/.../oal/rt/OALEngineLoaderService.java`
**Replacement**: `oap-libs-for-graalvm/server-core-for-graalvm/`

On first `load()` call, reads manifests and registers all OAL classes:
- `META-INF/oal-metrics-classes.txt` → `StreamAnnotationListener.notify()`
- `META-INF/oal-dispatcher-classes.txt` → `DispatcherDetectorListener.addIfAsSourceDispatcher()`
- `META-INF/oal-disabled-sources.txt` → `DisableRegister`

### MAL: `DSL` (v2)

**Upstream**: `analyzer/meter-analyzer/.../v2/dsl/DSL.java`
**Replacement**: `oap-libs-for-graalvm/meter-analyzer-for-graalvm/`

`parse(metricName, expression)` loads per-file configs from `META-INF/mal-v2/`, looks up the pre-compiled class by expression text, and wires closure fields:

```java
// At startup: load META-INF/mal-v2.manifest → per-file configs → expression→FQCN map
String className = expressionMap.get(expression);
MalExpression malExpr = (MalExpression) Class.forName(className)
    .getDeclaredConstructor().newInstance();
wireClosures(malExpr.getClass(), malExpr);  // LambdaMetafactory wiring
return new Expression(metricName, expression, malExpr);
```

**Per-file manifests**: `META-INF/mal-v2/` mirrors the original YAML directory structure. Each config file (e.g., `otel-rules/vm.yaml`) contains rule names, full expressions, filter info, and compiled class FQCNs. `META-INF/mal-v2.manifest` lists all config files. Runtime builds an in-memory `expression → FQCN` map from all per-file configs. Since expressions are globally unique (different YAML files produce different expressions even for the same metric name), no hash or disambiguation is needed.

**Closure wiring**: Pre-compiled `MalExpression` classes may have public fields typed as functional interfaces (`TagFunction`, `ForEachFunction`, `PropertiesExtractor`, `DecorateFunction`). At build time, `MALClassGenerator` wires these via `LambdaMetafactory` after class loading. At runtime, the replacement `DSL.java` replicates this wiring by scanning public fields and invoking `LambdaMetafactory.metafactory()` to create the functional interface wrapper from the corresponding method (e.g., field `_tag` → method `_tag_apply`).

### LAL: `DSL` (v2)

**Upstream**: `analyzer/log-analyzer/.../v2/dsl/DSL.java`
**Replacement**: `oap-libs-for-graalvm/log-analyzer-for-graalvm/`

`of(moduleManager, config, dsl, extraLogType, ruleName, yamlSource)` computes the deterministic class name and loads via `Class.forName()`.

### Hierarchy: `CompiledHierarchyRuleProvider`

**Upstream**: `analyzer/hierarchy/.../v2/compiler/CompiledHierarchyRuleProvider.java`
**Replacement**: `oap-libs-for-graalvm/server-core-for-graalvm/`

`buildRules(ruleExpressions)` loads pre-compiled `BiFunction` classes by rule name.

---

## Classpath Scanning Replacements

Guava `ClassPath.from()` is used in several places. All replaced with manifest-based loading:

| Upstream Class | Manifest | Purpose |
|---|---|---|
| `AnnotationScan` | `META-INF/annotation-scan/{Annotation}.txt` | 6 annotation manifests |
| `SourceReceiverImpl` | `META-INF/annotation-scan/SourceDispatcher.txt` | Dispatcher/decorator discovery |
| `MeterSystem` | `META-INF/annotation-scan/MeterFunction.txt` | 16 meter function classes |

---

## MeterSystem Javassist (Unchanged)

`MeterSystem.create()` generates one dynamic meter subclass per metric rule (~1188 classes) via Javassist. This is separate from MAL DSL compilation and is handled the same as before: run at build time, export `.class` files, load from manifests at runtime.

---

## Manifest Files

All manifests are in `META-INF/` within the precompiler output JAR:

| Manifest | Format | Purpose |
|---|---|---|
| `oal-metrics-classes.txt` | FQCN per line | OAL metrics class registration |
| `oal-dispatcher-classes.txt` | FQCN per line | OAL dispatcher class registration |
| `oal-disabled-sources.txt` | Source name per line | OAL disabled sources |
| `mal-v2.manifest` | File path per line | Lists all per-file MAL configs |
| `mal-v2/{path}/{name}.yaml` | Properties | Per-file config: rule names, expressions, filter, class FQCNs |
| `mal-v2-classes.txt` | FQCN per line | Reflection config for MAL expression classes |
| `lal-v2-classes.txt` | FQCN per line | Reflection config for LAL expression classes |
| `hierarchy-v2-classes.txt` | FQCN per line | Reflection config for hierarchy rule classes |
| `mal-meter-classes.txt` | `name\|scopeId\|func\|type\|FQCN` | MeterSystem pre-generated classes |
| `annotation-scan/*.txt` | FQCN per line or `key=FQCN` | Annotation/interface scan replacements |
| `config-data/*.json` | JSON | Serialized rule configs for runtime loaders |

---

## Reflection Config Generation

The precompiler auto-generates `reflect-config.json` from manifests:
- OAL metrics/dispatchers: constructor-only access
- MAL/LAL/Hierarchy expression classes: constructor-only access
- Annotation-scanned classes: full method/field access
- Armeria HTTP handlers, GraphQL resolvers/types: full access

MAL v2 expressions use `LambdaMetafactory` for closure wiring. Since the pre-compiled classes are on the native-image build classpath, the static analysis resolves these lambdas automatically.

---

## Same-FQCN Replacement Summary

| Upstream Class | Module | What Changed |
|---|---|---|
| `OALEngineLoaderService` | `server-core-for-graalvm` | Load OAL classes from manifests |
| `AnnotationScan` | `server-core-for-graalvm` | Read annotation manifests instead of Guava scan |
| `SourceReceiverImpl` | `server-core-for-graalvm` | Read dispatcher manifests instead of Guava scan |
| `MeterSystem` | `server-core-for-graalvm` | Read MeterFunction manifest + load pre-generated meter classes |
| `HierarchyDefinitionService` | `server-core-for-graalvm` | Java-backed hierarchy rules |
| `CompiledHierarchyRuleProvider` | `server-core-for-graalvm` | Load pre-compiled hierarchy rule classes |
| `DSL` (MAL v2) | `meter-analyzer-for-graalvm` | Load pre-compiled MalExpression classes |
| `DSL` (LAL v2) | `log-analyzer-for-graalvm` | Load pre-compiled LalExpression classes |
| `Rules` | `meter-analyzer-for-graalvm` | Load rule configs from JSON manifests |
| `MeterConfigs` | `agent-analyzer-for-graalvm` | Load meter configs from JSON manifests |
| `LALConfigs` | `log-analyzer-for-graalvm` | Load LAL configs from JSON manifests |

---

## Verification

```bash
# 1. Build precompiler and verify class generation
mvn -pl build-tools/precompiler install -DskipTests

# 2. Check generated v2 classes exist in output JAR
jar tf build-tools/precompiler/target/precompiler-*-generated.jar | grep "v2/compiler/rt"

# 3. Run tests
mvn -pl oap-graalvm-server test

# 4. Full distro build
make build-distro

# 5. Native image
make native-image
```
