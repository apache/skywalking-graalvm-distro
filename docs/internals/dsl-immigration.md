# DSL Build-Time Pre-Compilation (OAL/MAL/LAL/TAL/Hierarchy)

## Context

SkyWalking OAP uses five DSL compilers that generate Java bytecode at startup via Javassist:

| DSL | Purpose | Compiled Interface | Generated Classes |
|-----|---------|-------------------|-------------------|
| **OAL** | Metrics aggregation rules | Metrics, Builder, Dispatcher classes | ~620 metrics + ~620 builders + ~45 dispatchers |
| **MAL** | Meter expression rules | `MalExpression` | ~1250 expression classes |
| **LAL** | Log analysis rules | `LalExpression` | 19 expression classes |
| **TAL** | Trace analysis rules: OTLP spans and SkyWalking agent segments (`sw-native`) | `TalExpression` | 14 expression classes |
| **Hierarchy** | Service hierarchy matching | `BiFunction<Service, Service, Boolean>` | ~4 rule classes |

All five share the same pipeline (since upstream PR #13723; TAL since 11.1):
```
DSL text → ANTLR4 parse → Immutable AST → Javassist bytecode → .class
```

GraalVM native image cannot run Javassist at runtime. **Solution**: run all five compilers at build time via their exposed APIs (`setClassOutputDir()`, `setClassNameHint()`), capture `.class` files into the precompiler output JAR. At runtime, load pre-compiled classes instead of compiling.

---

## Build-Time Compilation

**Module**: `build-tools/precompiler`

The precompiler invokes each v2 compiler at build time, capturing generated `.class` files to the output directory. All classes are packaged into `precompiler-*-generated.jar`, which is on the native-image build classpath.

### OAL

Uses `OALEngineV2.start()` with `setOpenEngineDebug(true)` and `setGeneratedFilePath()` to export classes, for every
`OALDefine` on the precompiler's class path (its `INSTANCE`), plus a define for each script a rules overlay adds. Then
`notifyAllListeners()` hands the script's metrics and dispatcher classes to recording listeners, which keep them in
upstream's order; the script's disables come from upstream's `OALScriptParserV2`, checked against what the generators
registered (`DisableRegister#scriptDisablesWithoutStream`). An `OALDefine` without its own `public static INSTANCE`, or
a staged `oal/*.oal` no define names, fails the build. The bundled defines:

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
| `GenAIOALDefine` | `oal/virtual-gen-ai.oal` |

Generated class packages:
- Metrics: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.*`
- Builders: `org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.*`
- Dispatchers: `org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.*`

### MAL

Runs upstream's `MetricConvert` over every rule file as the receivers do at boot (the MAL catalogs through upstream's
`Rules.loadRules`; the Zabbix files through upstream's `ZabbixConfigs.loadConfigs` and `ZabbixMetrics`, which builds a
converter per host pattern of a file, so a file without one compiles nothing and an invalid pattern fails the build, as
the boot does). An expression or filter asked for again at the same coordinates with the same text compiles once, as
the runtime registry holds one class for it.
Its `DSL.parse` and `FilterExpression` calls reach the precompiler's build-time copies of those two classes, which
compile with `MALClassGenerator` (`setClassOutputDir()`, `setSourceRef()`, `setContent()`) as upstream's startup path
does, so the classes get upstream's names (`{yaml}_L{line}_{metric}`, `{yaml}_L{line}_filter`).

```
For each MAL rule file:
  new MetricConvert(rule, meterSystem)
    → DSL.parse(metricName, expression, sourceRef)       // per metric rule
    → new FilterExpression(filter, "filter", sourceRef)  // the file's filter, if any
  The build-time copies record each class with what MetricConvert passed: coordinates, metric name, text
Write META-INF/mal-v2-rules.txt from the records
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

Uses one `LALClassGenerator` with `setClassOutputDir()`, fed what upstream `LogFilterListener.Factory` passes
`DSL.of` at boot:

```
For each lal/*.yaml file (LALConfigs.load stamps sourcePath and line, registers the file's layerDefinitions):
  For each rule:
    a. skipped, as upstream's loadStaticRules skips it with a warning: a layer no source registers, or a
       layer: auto rule with a sink but no layer statement (Factory.isRegisteredLayer / savesWithoutLayer)
    b. refused, as upstream refuses it at boot: fallback off a layer: auto rule, two rules of one name in a layer
    c. input and output types from upstream's Factory.resolveInputType / resolveOutputType (YAML, else the
       layer's LALSourceTypeProvider; an outputType short name through the LALOutputBuilder SPI)
    d. generator.setSourceRef(DslSourceRef.ofRule(sourcePath, line)); setClassNameHint(rule); setContent(dsl)
       LalExpression expr = generator.compile(dsl)       // .class written to classOutputDir
```

The skip predicates and resolvers are upstream's own, called through `LalStaticRuleResolver`, a precompiler class in
`LogFilterListener`'s package. `lal-v2-rules.txt` records, per rule, the `DslSourceRef` coordinates (an unresolved
line as `-1`, as the boot builds it), the name, the class, the effective input type, and what the class was compiled
from: the DSL digest and the requested input and output types. Two rules may share a line (flow-style YAML), as
upstream allows; the runtime `DSL` tells them apart by those inputs, and two on one line agreeing on name, DSL text and
types (in two layers) run one class, the layer being no input of it, as upstream runs its two alike classes.

| File | Rules |
|------|-------|
| `default.yaml` | 3 |
| `nginx.yaml` | 2 |
| `miniprogram.yaml` | 2 |
| `envoy-als.yaml` | 3 |
| `envoy-ai-gateway.yaml` | 2 |
| `mysql-slowsql.yaml`, `pgsql-slowsql.yaml`, `redis-slowsql.yaml` | 1 each |
| `mesh-dp.yaml`, `k8s-service.yaml`, `ai-agent.yaml`, `ios-metrickit.yaml` | 1 each |

Generated class package: `org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.*`

### TAL

Uses one `TALClassGenerator` per rule with `setClassOutputDir()`, `setSourceRef()` and `setClassNameHint()`,
mirroring upstream `SpanFilterListener.Factory.compile` + `TalDSL.of`:

```
registry = TALFunctionRegistry.discover(); registry.bind(null, input providers in ServiceLoader order)
For each tal/*.yaml file (TALConfigs.load stamps sourcePath, line and the file's metricPrefix):
  For each rule:
    a. input = the TALSourceTypeProvider claiming rule.inputType (`otlp` -> OTLP Span, `sw-native` -> SpanObject)
       Layer.valueOf(rule.layer)
    b. generator.setInputAlias(input.alias()); generator.setFunctionRegistry(registry)
       TalExpression expr = generator.compile(dsl)       // .class + .java written to classOutputDir
    c. record each function-object field of expr with the registry key of the object in it
    d. TALMetricShapes.claim(sourceRef + "#" + rule, generator.getMetricDeclarations())
    e. generator.createMetrics(meterSystem)              // the build-time MeterSystem MAL uses
```

| File | Input | Rules | Metrics |
|------|-------|-------|---------|
| `otlp-general.yaml` | `otlp` | 2 (`otlp-general`, `otlp-slow-sql`) | 28 `otlp_*` |
| `otlp-ai-evaluation.yaml` | `otlp` | 1 | 0 |
| `native-rpc.yaml` | `sw-native` | 3 | 0 |
| `native-virtual-service.yaml` | `sw-native` | 3 | 0 |
| `native-endpoint-cross-thread.yaml`, `native-network-alias.yaml`, `native-segment.yaml`, `native-virtual-genai.yaml`, `native-ai-evaluation.yaml` | `sw-native` | 1 each | 0 |

Since SWIP-17 (11.2) a generated class calls the functions it uses (`isGateway`, `formatServiceName`,
`evaluationActive`, ...) through private function-object fields, which upstream's generator sets by reflection from
the `TALFunctionRegistry` right after instantiating the class: the input providers, the helper objects each input
hands over (`TalGeneralFunctions`, `TalConfigFunctions`) and the module providers (`AIEvaluationTALFunctions`), bound
to the booted OAP's services. Those objects cannot be built at build time, so the manifest records, per field, the
registry key of the object the build-time generator put there (the owner's class name, or `class/alias` for a second
object of the same class); the runtime registry is discovered and bound in the same ServiceLoader order, so it holds
the same keys. `requiredModules` is carried in `tal.json`. A file requiring a module the image does not ship
(`AcceptedModules.shippedModuleNames()`, the stand-in `graalvm-unsupported-admin` included) is never loaded at boot,
so the build compiles none of it: it registers no layer, compiles no rule and creates no metric. The runtime skips a
carried file whose modules are not loaded, as upstream does. Two rules on one line agreeing on name, DSL text, input
and metric prefix (in two layers) run one class when the function fields and metric declarations recorded with it are
theirs too, and fail the build otherwise; the runtime `TalDSL` refuses two classes recorded for the same inputs.

Every file is loaded through upstream's `TALConfigs.load` before any rule compiles, as upstream's `loadRules` does, so
a layer one file declares under `layerDefinitions` serves the rules of every file. A rule that does not compile (a
construct or function of another input included), or that names an unknown layer or input type, fails the build with
upstream's boot-time message, as do two `talFiles` rules of one name in one layer. Upstream's TAL compiler emits
no debug probes, so TAL rules are not live-debuggable.

Nothing in the pass is specific to the shipped files: input providers and function providers come from the SPIs
(recorded in `tal-io-classes.txt` for reflection, with the input types and every function object), every declaration shape is carried (`buckets`, `ranks`,
`labelLimit`), the input types join the protobuf reflection roots as LAL's do, and `tal.json` is keyed by the name of
the file upstream's loader read for each stem (every file, as upstream takes any file `talFiles` names by its stem; the
loader picks the file of a stem, the name comes from the source it stamped; a file of `layerDefinitions` alone, which
the loader returns nothing for, is read with the same YAML binding and carried, so the boot registers its layers). The
manifest is written with `Properties#store`'s escapes in UTF-8 and read back the same way, so any rule or file name
round-trips, and records every input a class was compiled from (the `DslSourceRef` coordinates the boot looks the
rule up by, name, DSL digest, input type and alias, metric prefix): two rules can share a line in flow-style YAML, and
the runtime `TalDSL` loads only the class compiled from exactly the inputs it is given. The files are installed in
upstream's order, which decides which rule runs first: the registry generator runs one upstream `TALConfigs.load` over
the `talFiles` (OTLP) list and records the order it returns, and `nativeTalFiles` run in the order the list names them
(upstream's default order from its `application.yml`, then the others by name). `TALPrecompileTest` compiles operator-style fixtures (every metric function, labeled metrics with a label
limit, percentile buckets and ranks, alias and FQCN input types, a `source DatabaseSlowStatement {}` record, a
`sw-native` rule in a file with `requiredModules`, a `.yml` file, a rules-less file declaring a layer another file
uses) and the build failures; `TALDocScriptsTest` extracts the scripts of upstream's `tal-otlp.md` and of the
practice pages (`otlp-trace-practices/gateway-{overview,canary,nginx}.md`, `database-and-cache.md`,
`message-queue.md`), splices or substitutes each practice into a copy of the shipped file where the page says, compiles them through the same pass and runs the classes the manifest names against spans each practice is
about.

Generated class package: `org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt.*`

### Hierarchy

Uses `HierarchyRuleClassGenerator` with `setClassOutputDir()`, `setSourceRef()` and `setClassNameHint()`, as upstream's
`CompiledHierarchyRuleProvider` compiles the rules at boot.

```
1. Load hierarchy-definition.yml, and each auto-matching rule's line (DslYamlLineIndex.keyLines)
2. For each rule expression:
   a. generator.setSourceRef(DslSourceRef.ofRule("hierarchy-definition.yml", line))
   b. generator.setClassNameHint(ruleName)   (DslClassNaming makes it an identifier)
   c. BiFunction result = generator.compile(ruleName, expression)
   d. .class file auto-written to classOutputDir
3. Record rule name -> class in hierarchy-v2-rules.txt (escaped: a rule may be named anything upstream accepts)
```

4 bundled rules: `name`, `short-name`, `lower-short-name-remove-ns`, `lower-short-name-with-fqdn`.

Generated class package: `org.apache.skywalking.oap.server.core.config.v2.compiler.hierarchy.rule.rt.*`

---

## DSL Live Debugger (SWIP-13)

Upstream's debugger is a codegen-time decision: when `DSLDebugCodegenSwitch.isInjectionEnabled()`
is true, the OAL, MAL and LAL generators emit a `GateHolder` field per rule (per metric for OAL
dispatchers), a `debugHolder()` accessor and `if (holder.isGateOn())` probe call sites. The
upstream OAP flips the switch in `DSLDebuggingModuleProvider.prepare()`; this distro flips it in
the precompiler (`DSLDebugCodegenSwitch.enableInjection()` before every generator pass) and sets
`setContent(...)` on the MAL/LAL generators, so the pre-compiled classes carry the same probes
and rule text. Nothing is compiled at runtime: the wired-in upstream `dsl-debugging` module only
adds and removes recorders on the holders. Registration works unchanged — MAL holders are
published by upstream's receivers through `MalStaticBindingHook`, LAL holders by
`LogFilterListener` through `LalStaticBindingHook` (the log `DSL` replacement stamps the same
`ruleName`/`inputClass`/`outputClass` metadata upstream does), and OAL holders are found on the
loaded dispatchers via `DebugHolderProvider`. Idle cost is one volatile read per probe site.

Two details the distro supplies for the module: `OALEngineLoaderService.getLoadedDefines()` on
the replacement loader, and the raw `oal/*.oal` scripts at their classpath paths in the
precompiler jar, which `GET /runtime/oal/files/{name}` reads (the file name must be
path-escaped, `oal%2Fcore.oal`, as swctl and Horizon send it).

## Runtime Loading via Same-FQCN Replacements

At runtime (native image or JVM distro), same-FQCN replacement classes take the pre-compiled classes from the
generated rule registry instead of running Javassist, and compile or bind no rule file; the loaders read the packaged
sources of the files they load only to record them for the read-only rule catalog (`RuleSources`). Every staged rule
file is active, one yielding no rule too (comments only, or a LAL or TAL file without a `rules` list): its loader reads
and records it and returns no rule for it, as upstream's; a TAL file of this kind is carried in the registry with its
`layerDefinitions` and `requiredModules`, which the boot registers and declares as upstream's loader does.

### The static rule registry: `CompiledRules`

After the engine passes, the precompiler's `RegistryGenerator` generates Java source, compiles it in process
(`javax.tools`, against the precompiler's class path plus the generated classes) and puts the classes in the
generated jar, next to the hand-written holder types of `org.apache.skywalking.oap.server.graalvm.rules`
(`MalFile`, `CompiledMalExpression`, `CompiledMalFilter`, `LalFile`, `LalRule`, `TalFile`, `TalRule`,
`HierarchyRules`, `OalScript`):

- **A class per rule file** holding, as code, what upstream's loader read from the file at build time and
  constructing the classes compiled from it: `Mal_<catalog>_<file>` (the MAL `Rule` with each expression and its
  filter as a `CompiledMalExpression` / `CompiledMalFilter`: coordinates, metric name, text, `Generated::new`),
  `Lal_<file>` (the `LALConfigs`
  with every rule's coordinates, class and effective input type), `Tal_<file>` (the `TALConfigs` with every rule's
  coordinates, class, input type and alias, metric prefix, DSL digest, function fields and metric declarations),
  `Zabbix_<file>` (the `ZabbixConfig` with its compiled expressions and filter) and `Oal_<script>` (an `OalScript`:
  the script's `OALDefine`, the metrics and dispatcher classes its engine handed the listeners, the sources its
  `disable()` switches off, and whether the rules overlay added it). Each rule object is built by a factory method of
  its own, so no method nears the 64 KB method-code limit.
- **`HierarchyRegistry`** (the `hierarchy-definition.yml` data and a matcher per rule), **`LayerRegistry`** (the
  `layer-extensions.yml` entries) and **`MeterRegistry`** (the generated meter classes by the metric name the rule
  passes, as the class name lowercases it in the build's locale, which the boot's need not share; the
  `@MeterFunction` classes by function name).
- **`CompiledRules`**, the index every replacement reads. Rule objects are built fresh on every call, as reading
  the files did; the lookup maps are built once.

The lookup key is what upstream code passes in: the rule's source coordinates (MAL, LAL, TAL), with the inputs a
class was compiled from checked against those upstream passes. The classes are referenced directly, so the native image sees them as reachable. The registry is generated
from the manifests the engine passes write (below), which stay build intermediates: the generated jar leaves them
out. `RegistryParityTest` requires, for every bundled file, the registry data to equal what upstream's loaders
return and every class to be the one the manifests record; `RegistryGeneratorTest` round-trips the string literals
through javac. A rule file in the directory OAP reads its configuration from (the native image's `config/` next to its
executable, on the JVM the directory `application.yml` is read from), where upstream keeps its rule files, fails the
boot (`CompiledRulesCheck`): rule files enter the image only at build time, and nothing would read it.

### OAL: `OALEngineLoaderService`

**Upstream**: `server-core/.../oal/rt/OALEngineLoaderService.java`
**Replacement**: `oap-libs-for-graalvm/server-core-for-graalvm/`

`load(define)` registers the define's compiled script when its provider loads it, as upstream's
`OALEngineV2#notifyAllListeners` registers the classes it generates: the metrics classes →
`StreamAnnotationListener.notify()`, then the dispatcher classes → `DispatcherDetectorListener.addIfAsSourceDispatcher()`.
A turned-off module's script, or one its module loads only on a setting (`tcp.oal` with the Envoy TCP access log
analysis), stays compiled and unused, and `getLoadedDefines()`, which the read-only OAL catalog of the dsl-debugging
module lists, holds the loaded defines only. A script's disables register first, when its define loads, as upstream's
`OALClassGeneratorV2` registers them before the streams (`DisableRegister#addFromScript`, skipped with upstream's
warning when the stream already runs), so the disables of a script whose module is off have no effect, and
`oal/disable.oal`, which upstream's core loads first, disables the streams defined after it, those declared in code
included. The first load registers, after its define, the scripts the rules overlay added, which no provider loads. A
define the overlay excluded loads nothing; a define neither compiled nor excluded fails the boot.

### MAL: `DSL` (v2), `FilterExpression`, `Rules`

**Upstream**: `analyzer/meter-analyzer/.../v2/dsl/DSL.java`, `FilterExpression.java`, `.../prometheus/rule/Rules.java`
**Replacement**: `oap-libs-for-graalvm/meter-analyzer-for-graalvm/`

`parse(metricName, expression, sourceRef)` takes the class compiled for the rule at the coordinates `MetricConvert`
passes (`<catalog>/<file>.yaml:<line>`) and fails the boot unless the metric name and expression text are those it was
compiled from. Equal texts are no identity: two rules of one text (two lines of `banyandb-instance.yaml`, or the
filter several rule files of a family share) keep the classes compiled for each, named for their own file and line.
`FilterExpression` does the same with the file's filter coordinates and literal. A call without coordinates (no rule
upstream locates) takes a class compiled from the same metric name and text, or literal. `Rules.loadRules` returns the
requested rule files of a catalog, failing as upstream does on a name that is no compiled rule file, and registers
their inline `layerDefinitions` as upstream does.

**Closure fields**: pre-compiled `MalExpression` classes with functional-interface fields (`TagFunction`,
`ForEachFunction`, `PropertiesExtractor`, `DecorateFunction`) instantiate their companion classes in their own static
initializer, so no wiring is needed after instantiation.

### LAL: `DSL` (v2), `LALConfigs`

**Upstream**: `analyzer/log-analyzer/.../v2/dsl/DSL.java`, `.../v2/provider/LALConfigs.java`
**Replacement**: `oap-libs-for-graalvm/log-analyzer-for-graalvm/`

`of(moduleManager, config, dsl, inputType, outputType, ruleName, sourceRef)` takes, among the classes compiled at
the rule's `DslSourceRef` coordinates (`lal/<file>.yaml:<line>`), the one compiled from the name, DSL text and input
and output types upstream passes, and fails the boot when there is none. A call without coordinates is an ad-hoc rule
(the LAL log test tool), which the image cannot compile: it fails with a message saying so. The registry also carries
the effective input type the compiler resolved, which upstream `LogFilterListener` uses to route Envoy HTTP vs TCP
access logs to the right rule. `LALConfigs.load` returns the listed rule files as upstream's loader read them at build
time, the coordinates it stamped included, in the order one upstream load over them all returned (the order their
rules run on a log), and registers their inline `layerDefinitions` as upstream does. The listed files it reads are
those the build activated (every staged one), each passed through `RuleSetMerger` from its carried source, whose absence
fails the boot; the MAL `Rules` replacement matches its patterns among the activated files the same way.

### TAL: `TalDSL` + `TALConfigs`

**Upstream**: `analyzer/trace-analyzer/.../v2/dsl/TalDSL.java`, `.../v2/provider/TALConfigs.java`
**Replacement**: `oap-libs-for-graalvm/trace-analyzer-for-graalvm/`

`TALConfigs.load` / `loadInOrder` take the rule files from the registry, which carries what upstream's loader returned at build time (the source names, lines and metric prefixes it stamped included, so the upstream `SpanFilterListener.Factory` builds the same `DslSourceRef`) in the order it returned the `talFiles`, and skip a file whose `requiredModules` are not all loaded before its inline layers register; `loadInOrder` (the `nativeTalFiles` list) returns the files as listed and fails on one that was not compiled, and `requiredModulesOf` reads the same registry, so the trace analyzer still starts the modules the rules need first. `TalDSL.of(...)` looks the rule up by those coordinates (`tal/<file>.yaml:<line>`, never by name alone) in the registry, fails if the rule name, DSL, input type, input alias or metric prefix differs from those the class was compiled for, instantiates the class, sets each function-object field from the function registry it is given by the recorded key (failing the boot when the function registry holds another class there, or nothing because the module providing those functions is not loaded, which the message names), then claims and creates the recorded metric declarations (`meterSystem.create` + `limitLabels`), exactly the calls upstream's `createMetrics` makes; a failure there is reported as upstream reports it, `TAL rule <name> (<file>:<line>) does not compile: ...`. `TALRecordedTraceComparisonTest` runs the OTLP rules over upstream's recorded OTel Demo trace through a fresh compile and through the pre-compiled classes and requires identical metrics, traffic, alias rows and source records; `NativeTALComparisonTest` runs every `nativeTalFiles` rule over upstream's native-trace checker fixtures (copied from the submodule at build time) through upstream's `SwNativeRuleRunner`, once with the classes the factory loads and once with fresh compiles against the same function registry, in a cold and a warm pass, and requires identical records, alias rows and failures.

### Hierarchy: `HierarchyDefinitionService`

**Upstream**: `server-core/.../core/config/HierarchyDefinitionService.java`
**Replacement**: `oap-libs-for-graalvm/server-core-for-graalvm/`

Skips the upstream `HierarchyRuleProvider` SPI and the YAML: takes the hierarchy, the layer levels and the
pre-compiled `BiFunction` of each matching rule from the registry, failing the boot when a rule has none, builds
upstream's `MatchingRule`s from them, then validates the layers as upstream does. Upstream's `HierarchyService` runs
unchanged on those rules. `RegistryParityTest` builds upstream's `HierarchyDefinitionService` over the staged file with
upstream's `CompiledHierarchyRuleProvider` and requires the registry to hold its layer pairs, rules, layer levels and
class names, and runs every compiled matcher against a fresh compile over upstream's hierarchy test pairs.

### Layers and Zabbix: `LayerExtensionLoader`, `ZabbixConfigs`

**Replacement**: `oap-libs-for-graalvm/server-core-for-graalvm/`, `oap-libs-for-graalvm/zabbix-receiver-for-graalvm/`

`LayerExtensionLoader.load()` registers the `layer-extensions.yml` entries from the registry, then runs the
`LayerExtension` SPI as upstream does (it is compiled code). `ZabbixConfigs.loadConfigs` returns the configs upstream's
loader read from `zabbix-rules/`; the precompiler compiled their expressions with MAL from those configs through
upstream's `ZabbixMetrics`, as the Zabbix receiver does at boot.

---

## Classpath Scanning Replacements

Guava `ClassPath.from()` is used in several places. All replaced with manifest-based loading:

| Upstream Class | Manifest | Purpose |
|---|---|---|
| `AnnotationScan` | `META-INF/annotation-scan/{Annotation}.txt` | One manifest per annotation an `AnnotationListener` listens for |
| `SourceReceiverImpl` | `META-INF/annotation-scan/SourceDispatcher.txt` | Dispatcher/decorator discovery |
| `MeterSystem` | the rule registry (`CompiledRules.meterFunctions()`, from `annotation-scan/MeterFunction.txt`) | 18 meter function classes |

---

## MeterSystem Javassist (Unchanged)

`MeterSystem.create()` generates one dynamic meter subclass per metric rule (~1530 for MAL, 28 for TAL) via Javassist. This is separate from MAL DSL compilation and is handled the same as before: run at build time, export `.class` files; at runtime the `MeterSystem` replacement runs upstream's boot `create` with the class of a metric taken from the registry instead of generated: the same duplicate checks, and the metric left uncreated when the storage refuses its shape or its streams fail.

---

## Build Manifests

The engine passes write these under `META-INF/` of the precompiler output directory. The registry and
`reflect-config.json` are generated from them; the generated jar leaves them out (`maven-jar-plugin` excludes), except
the `annotation-scan/` manifests the boot reads (one per `AnnotationListener` annotation, `SourceDispatcher.txt`,
`ISourceDecorator.txt`), `rule-source/` and the `oal/*.oal` scripts.

| Manifest | Format | Purpose |
|---|---|---|
| `oal-metrics-classes.txt` | FQCN per line | OAL metrics classes |
| `oal-dispatcher-classes.txt` | FQCN per line | OAL dispatcher classes |
| `oal-builder-classes.txt` | FQCN per line | OAL storage builder classes (each metrics class's `@Stream` builder) |
| `oal-disabled-sources.txt` | Source name per line | OAL disabled sources |
| `oal-scripts.txt` | Escaped UTF-8 properties (`script.N.define/file/sourcePackage/dispatcherPackage/metrics/dispatchers/disabled`, `excluded`) | What each OAL script generated, and the scripts the rules overlay excluded, for the per-script registry classes |
| `mal-v2-rules.txt` | Escaped UTF-8 properties (`expression.N.source/line/name/text/class`, `filter.N.source/line/text/class`) | MAL expressions and filters by the coordinates, metric name and text `MetricConvert` passed |
| `mal-v2-classes.txt` | FQCN per line | MAL expression classes |
| `lal-v2-classes.txt` | FQCN per line | LAL expression classes |
| `lal-v2-rules.txt` | Properties (`rule.N.source/file/line/name/class/inputType/dslSha256/requestedInputType/requestedOutputType`) | LAL rules by `DslSourceRef` coordinates: class, effective input type, and what the class was compiled from |
| `lal-io-classes.txt` | FQCN per line | `LALSourceTypeProvider`, `LALOutputBuilder` and `LalInputDebugRenderer` implementations, which ServiceLoader instantiates, for reflection |
| `lal-input-types.txt` | FQCN per line | Every type a LAL rule reads values from (rule input types, each `LALSourceTypeProvider` and `LalInputDebugRenderer` input type, `LogData`), which the reflection metadata walks |
| `tal-v2-classes.txt` | FQCN per line | TAL expression classes |
| `tal-v2-rules.txt` | Escaped UTF-8 properties (`rule.N.source/line/name/class/inputType/inputAlias/metricPrefix/dslSha256`, `rule.N.function.K.field/key/type`, `rule.N.metric.M.fullName/function/scopeType/declaredName/buckets/ranks/labelLimit`) | TAL rules by the inputs each class was compiled from, the function objects it is wired with + the metrics each rule creates |
| `tal-meter-classes.txt` | `name=FQCN` | Meter classes generated for TAL metrics |
| `tal-io-classes.txt` | FQCN per line | TAL input types, `TALSourceTypeProvider` / `TALFunctionProvider` implementations and helper function classes, for reflection (their methods are listed when the function registry binds) |
| `hierarchy-v2-classes.txt` | FQCN per line | Hierarchy rule classes |
| `hierarchy-v2-rules.txt` | Escaped UTF-8 properties (`ruleName=FQCN`) | Hierarchy rules by name |
| `mal-meter-classes.txt` | `name=FQCN` | MeterSystem pre-generated classes |
| `config-data/*.json` | JSON | The rule configs upstream's loaders returned |
| `annotation-scan/*.txt` | FQCN per line or `key=FQCN` | Annotation/interface scan replacements (packaged, read at runtime); `MeterFunction.txt` (an intermediate of the registry) and `StorageBuilders.txt`, `StreamData.txt`, `ArmeriaHandlers.txt`, `HttpBodyTypes.txt`, `GraphQLResolvers.txt`, `GraphQLTypes.txt`, `QueryEntityClasses.txt` (intermediates of `reflect-config.json`) are left out of the jar |
| `rule-source/**` | raw rule files of every catalog a loader passes through `RuleSetMerger` (MAL but Zabbix, LAL, TAL), every staged file (one yielding no rule too; of TAL, the carried ones) + `index.txt` (`catalog\|path`) | What the replacement loaders pass through upstream's `RuleSetMerger` (`RuleSources.merge`), which records them in `StaticRuleRegistry`, from which `BundledRuleCatalogHandler` serves the read-only `/runtime/rule` catalog, deciding per request, against the layers registered, which files upstream's runtime-rule lists (packaged) |
| `oal/*.oal` (jar root) | raw OAL scripts | `GET /runtime/oal/files/{name}` (upstream dsl-debugging handler, packaged) |

---

## Reflection Config Generation

The precompiler auto-generates `reflect-config.json` from the build manifests:
- OAL metrics/dispatchers: constructor-only access
- MAL/LAL/TAL/Hierarchy expression classes and MAL/TAL meter classes: constructor-only access
- Every concrete `StreamData` class: constructor-only access (`RemoteHandleWorker` rebuilds the stream data another OAP node sends with `newInstance()`, of a non-`@Stream` class such as TAL's `PairingFact` too)
- TAL `PairingFact` and `SpanMetadata`: full access (what a rule's `pairing` / `meta` hand to `toJson()`, which serializes objects through Gson's reflective adapters)
- The protobuf runtime classes Gson walks when `toJson()` is given a protobuf input (`ByteString` and its subclasses, `AbstractMessage(Lite)`, `GeneratedMessage`, `UnknownFieldSet`, the primitive and lazy lists, `MapField`), so `toJson(span)` returns natively what it returns on the JVM
- Annotation-scanned classes: full method/field access
- Armeria HTTP handlers, the classes their `@ExceptionHandler`, `@Decorator`, `@RequestConverter` and `@ResponseConverter` name, GraphQL resolvers/types: full access
- The bodies a route's signature names (parameters that are no `@Param` / `@Header`, the return type, and the classes their fields hold): full access, a protobuf message its descriptor closure (Armeria converts it through `JsonFormat`)
- LAL input types (Envoy `HTTPAccessLogEntry` / `TCPAccessLogEntry`, each `LALSourceTypeProvider` input type, `LogData`) and every message, Builder and enum type reachable through their proto descriptors: full access. Upstream JSON-prints these entries with protobuf's `JsonFormat` (the `EnvoyAccessLog` content and the DSL debug captures), whose accessor table finds the generated getters by reflection. `LalInputTypeReflectionTest` walks the descriptors and fails on any gap.
- The LAL values `LalRuntimeHelper` reads a property of when the compiler could not type it (`parsed.<key>` no parser set, read from the input, or `value.<name>`): it lists the value's public methods, checks the getter against `LALCallables` and calls it. The precompiler walks them from `lal-input-types.txt` and from the values LAL gives every rule whatever its input (`LogMetadata`, `LogData`, `ExecutionContext.Parsed`): a protobuf message joins the descriptor closure above; another type gets its public methods, and the walk follows every getter `LALCallables` allows into the messages, `ToJson` types and SkyWalking types declaring such getters it returns (so `log.traceContext` brings `LogMetadata.TraceContext`). Every class `LALCallables` lists gets its public methods too, since the list is first built in the image.
- `LALSourceTypeProvider` / `LALOutputBuilder` / `LalInputDebugRenderer` implementations (`lal-io-classes.txt`, from the `ServiceLoader` scan of the LAL pass): full access; each renderer's input type joins the LAL input types, whichever rules exist.

MAL v2 closures (tag/forEach/decorate functions) are compiled into companion classes next to the expression class, so no runtime lambda wiring is needed; the static analysis sees them as ordinary classes.

---

## Same-FQCN Replacement Summary

| Upstream Class | Module | What Changed |
|---|---|---|
| `OALEngineLoaderService` | `server-core-for-graalvm` | Register the classes and disables of a define's compiled script from the rule registry when its provider loads it |
| `AnnotationScan` | `server-core-for-graalvm` | Read annotation manifests instead of Guava scan |
| `SourceReceiverImpl` | `server-core-for-graalvm` | Read dispatcher manifests instead of Guava scan |
| `MeterSystem` | `server-core-for-graalvm` | Meter functions and pre-generated meter classes from the rule registry |
| `HierarchyDefinitionService` | `server-core-for-graalvm` | Load pre-compiled hierarchy rule classes by name |
| `DSL` (MAL v2), `FilterExpression` | `meter-analyzer-for-graalvm` | Load pre-compiled MalExpression / MalFilter classes by rule coordinates |
| `DSL` (LAL v2) | `log-analyzer-for-graalvm` | Load pre-compiled LalExpression classes from the rule registry by rule coordinates and compile inputs |
| `Rules` | `meter-analyzer-for-graalvm` | MAL rule files from the rule registry, selected with upstream's patterns, passed through `RuleSetMerger` |
| `LALConfigs` | `log-analyzer-for-graalvm` | LAL rule files from the rule registry, passed through `RuleSetMerger` |
| `TalDSL` | `trace-analyzer-for-graalvm` | Load pre-compiled TalExpression classes from the rule registry, wire their function fields and create their recorded metrics |
| `TALConfigs` | `trace-analyzer-for-graalvm` | TAL rule files from the rule registry, in the order upstream's loader returned them, passed through `RuleSetMerger` |

---

## Verification

```bash
# 1. Build precompiler and verify class generation
mvn -pl build-tools/precompiler -am install -DskipTests

# 2. Check generated v2 classes exist in output JAR
jar tf build-tools/precompiler/target/precompiler-*-generated.jar | grep "v2/compiler/rt"

# 3. Run tests
mvn -pl oap-graalvm-server test

# 4. Full distro build
make build-distro

# 5. Native image
make native-image
```
