# SkyWalking GraalVM Distro - Distribution Policy

## Goal
Build and package Apache SkyWalking OAP server as a GraalVM native image on JDK 25.

## Architecture Constraints
- **Submodule**: `skywalking/` is a git submodule of `apache/skywalking.git`. All SkyWalking source changes go through upstream PRs. **Minimize upstream changes.**
- **This repo**: Maven + Makefile to orchestrate building on top of the submodule. Pre-compilation, GraalVM config, native-image wiring, and the boot entry point live here.
- **JDK 25**: Already compiles and runs. Not an issue.

## Module Selection

The image ships these modules and providers, and only these: they are the module SPI entries on its classpath. At boot,
`application.yml` decides which of them run and picks each one's provider, as upstream does.

| Category | Module | Provider |
|----------|--------|----------|
| **Core** | CoreModule | default |
| **Storage** | StorageModule | BanyanDB |
| **Cluster** | ClusterModule | Standalone, Kubernetes |
| **Configuration** | ConfigurationModule | Kubernetes ConfigMap, none |
| **Receivers** | SharingServerModule, TraceModule, JVMModule, MeterReceiverModule, LogModule, RegisterModule, ProfileModule, BrowserModule, EventModule, OtelMetricReceiverModule, MeshReceiverModule, EnvoyMetricReceiverModule, ZipkinReceiverModule, ZabbixReceiverModule, TelegrafReceiverModule, AWSFirehoseReceiverModule, CiliumFetcherModule, EBPFReceiverModule, AsyncProfilerModule, PprofModule, CLRModule, ConfigurationDiscoveryModule, KafkaFetcherModule | default providers |
| **Analyzers** | AnalyzerModule, LogAnalyzerModule, TraceAnalyzerModule, EventAnalyzerModule, GenAIAnalyzerModule, AIEvaluationModule, AIAgentConversationModule | default providers (AIEvaluationModule disabled by default; AIAgentConversationModule also offers `none`; TraceAnalyzerModule analyzes both OTLP spans and agent segments, and the native trace receiver and Kafka fetcher require it) |
| **Query** | QueryServerModule, QueryModule (GraphQL), PromQLModule, LogQLModule, TraceQLModule, ZipkinQueryModule | default providers (QueryServerModule serves GraphQL, the AI agent conversation API and `/status/config/ttl` on the core REST port unless its `restPort` is set) |
| **Admin** | AdminServerModule, StatusModule, InspectModule, UIManagementModule, DSLDebuggingModule, UnsupportedAdminFeatureModule (distro only) | default; runtime-rule excluded (`graalvm-unsupported-admin` answers its mutations with 501) |
| **Alarm** | AlarmModule | default |
| **Telemetry** | TelemetryModule | Prometheus, none |
| **Other** | ExporterModule, HealthCheckerModule, AIPipelineModule | default providers |

**Full feature set.** Work around issues as they arise.

> **Admin server family (11.0.0+).** `StatusModule` — cluster / alarm-runtime / TTL-config
> status plus the `/debugging/*` debug-query trace endpoints — was relocated from the 10.x
> `status-query` query plugin onto the admin-server host (default `:17128`); URIs and payloads
> are unchanged. `InspectModule` (SWIP-14 metric catalog + entity enumeration, `/inspect/*`)
> and `UIManagementModule` (dashboard-template REST consumed by the Horizon UI,
> `/ui-management/*`) are hosted there as well, and so is `DSLDebuggingModule` (SWIP-13 DSL live
> debugger, `/dsl-debugging/*` + `/runtime/oal/*`): the precompiler flips upstream's
> `DSLDebugCodegenSwitch` before generating, so every pre-compiled OAL/MAL/LAL rule class carries
> the debug probes and the module only flips gates at runtime (upstream's TAL compiler emits no
> probes yet, so TAL rules are not debuggable on either distro). `RuntimeRuleModule` (MAL/LAL hot-update) is
> **not supported**: it needs runtime Javassist code generation, which a closed-world native
> image cannot do. Its mutating endpoints return a friendly HTTP 501 via a stub module provider,
> while the read-only rule catalog (`/runtime/rule/list|bundled|status`, `GET /runtime/rule`) is served,
> as upstream answers it on a node with no runtime rule (`BundledRuleCatalogParityTest` runs upstream's handler beside
> it), from upstream's `StaticRuleRegistry`, which records the files the rule loaders loaded at boot, as upstream's
> read routes do: the replacement loaders pass the files they load, the raw rule files the precompiler exports under
> `META-INF/rule-source/`, through upstream's `RuleSetMerger`, as upstream's loaders do, so Horizon can list and
> review every rule of upstream's runtime-rule catalogs the boot loaded (MAL and LAL; a module turned off loads no
> rule; upstream has no TAL catalog, so TAL rules are not listed there either). A `RuntimeRuleOverrideResolver` that
> would change or drop a compiled file fails the boot: the image runs the classes compiled from those bytes.

---

## Core Strategy

1. **Build-Time Class Export**: All runtime code generation (OAL/MAL/LAL/TAL/Hierarchy via ANTLR4 + Javassist) runs at build time. Export `.class` files and package into native-image classpath. Classpath scanning also runs here as a verification gate.

2. **Shipped Module Set, Booted as Upstream**: The build decides which modules and providers the image carries; only theirs are on the classpath. At boot, upstream's `ModuleManager` loads the modules `application.yml` names through the module SPI, which native image supports (the providers in `META-INF/services` are registered at build time). Simplified config file for the shipped providers only.

3. **Separation**: SkyWalking upstream changes tracked separately, go through upstream PRs.

---

## DSL Runtime Class Generation (Unified V2 Engines)

Since upstream PR #13723, the SkyWalking DSL compilers (OAL, MAL, LAL, Hierarchy, and since 11.1 TAL) use a unified pipeline: ANTLR4 parse → immutable AST → Javassist bytecode. This eliminated the need for the complex, 10,000-line custom Groovy-to-Java transpiler previously maintained in this distro.

### Approach (Simplified)
Run all five v2 compilers at build time via their native exposed APIs (`setClassOutputDir()`, `setClassNameHint()`, `setGeneratedFilePath()`). Capture generated `.class` files into the precompiler output JAR. At runtime, same-FQCN replacement loaders find these classes by name instead of compiling them.

**Details**: [dsl-immigration.md](internals/dsl-immigration.md) | [oal-immigration.md](internals/oal-immigration.md)

### OAL
- `OALEngineV2.start()` processes every OAL define (the 10 bundled ones, plus one per script a rules overlay adds) at
  build time.
- Exports ~640 metrics classes, ~640 builder classes, ~47 dispatchers.
- Same-FQCN `OALEngineLoaderService` registers a define's pre-compiled script, as the rule registry lists it, when its
  provider loads the define, its disables first, as upstream; the overlay's own scripts, which no provider loads, at
  the first load, after its define (`oal/disable.oal` at boot, so its disables reach them).

### MAL
- `MALClassGenerator` compiles ~1570 MAL expressions from 96 YAML rule files at build time.
- Deterministic class naming via `DslSourceRef` (`{yaml}_L{line}_{metricName}`), the same names the JVM distro generates.
- Same-FQCN v2 `DSL.java` and `FilterExpression.java` take the pre-compiled `MalExpression` / `MalFilter` of a rule from the rule registry by the coordinates `MetricConvert` passes; Zabbix rules are compiled from upstream's `ZabbixConfigs`, as the Zabbix receiver compiles them.
- `MeterSystem.create()` also runs at build time → exports ~1500 Javassist meter classes.

### LAL
- `LALClassGenerator` compiles 19 LAL rules (12 YAML files) at build time.
- Deterministic class naming via `DslSourceRef` (`{yaml}_L{line}_{ruleName}`).
- Same-FQCN v2 `DSL.java` takes the pre-compiled `LalExpression` by source coordinates from the rule registry, which also records each rule's effective input type (upstream routes Envoy HTTP vs TCP access logs by it).

### TAL
- `TALClassGenerator` compiles every `tal/*.yaml` whose `requiredModules` the image ships at build time (shipped: 9 files, 14 rules — `otlp-general.yaml` and `otlp-ai-evaluation.yaml` for OTLP spans, the seven `native-*.yaml` files that analyze SkyWalking agent segments since SWIP-17), resolving input / layer exactly as upstream `SpanFilterListener.Factory` does, against a function registry bound as the factory binds its own; a rule that does not compile fails the build. Nothing is specific to the shipped rules: an operator's own TAL files, and the scripts in upstream's TAL docs, go through the same pass (`TALPrecompileTest`, `TALDocScriptsTest`).
- Deterministic class naming via `DslSourceRef` (`{yaml}_L{line}_{ruleName}`).
- The precompiler runs `createMetrics` on the build-time `MeterSystem` it shares with MAL, so the 28 `otlp_*` meter classes are generated.
- Same-FQCN `TalDSL` takes the pre-compiled `TalExpression` by source coordinates from the rule registry, which also carries each rule's input type and alias, the function-registry key of every function object the class holds and its metric declarations; it sets those function objects from the factory's function registry, then claims and creates the metrics (incl. `limitLabels`) as upstream does. Same-FQCN `TALConfigs` takes the rule files from the same registry, in the order one upstream `TALConfigs.load` over the `talFiles` returned them at build time (the order their rules run on a span), honoring `requiredModules` and the `nativeTalFiles` order.

### Hierarchy
- `HierarchyRuleClassGenerator` compiles 4 hierarchy matching rules at build time.
- Same-FQCN `HierarchyDefinitionService` takes the hierarchy, the layer levels and the pre-compiled `BiFunction` of each rule from the rule registry, and builds upstream's `MatchingRule`s from them, so upstream's `HierarchyService` runs unchanged; `hierarchy-definition.yml` is not in `config/` (only in the `compiled-rules/` record).

### Static rule registry
- After the engine passes, the precompiler generates a Java class per rule file — what upstream's loader read from it, as code, constructing the classes compiled from it — plus the `CompiledRules` index, and compiles them into the generated jar. The rules run from that alone (the runtime replacements, also `LayerExtensionLoader` and `ZabbixConfigs`): no rule YAML, JSON or manifest is compiled or bound at runtime. The replacement `Rules`, `LALConfigs` and `TALConfigs` also read the packaged sources of the files they load (`META-INF/rule-source/`), only to pass them through upstream's `RuleSetMerger`, which records them in `StaticRuleRegistry` for the read-only rule catalog, as upstream's loaders do.
- A rule file in the directory OAP reads its configuration from (the native image's `config/` next to its executable, on the JVM the directory `application.yml` is read from) fails the boot: rule files enter the image only at build time, and nothing would read it. Runtime configuration (`application.yml`, `alarm-settings.yml` …) is not a rule file and stays editable.
- Every compiled rule file is active. The settings upstream selects rule files with (`enabledOtelMetricsRules`, `meterAnalyzerActiveFiles`, `malFiles`, `lalFiles`, `talFiles`, `nativeTalFiles`, `enabledEnvoyMetricsRules`, the Telegraf and Zabbix `activeFiles`) are filled at boot from the registry (`CompiledRuleActivation`); one set in `application.yml`, by environment variable or by system property fails the boot. The image compiles every bundled rule file unless built with a rules overlay that adds, overrides or excludes files ([Compiling Your Own Rules](rules-overlay.md)); `compiled-rules/` in the distribution records what was compiled.

### Upstream Changes & Simplification
- **Groovy Removed**: Groovy was completely removed from upstream production code (PR #13723).
- **Transpiler Deleted**: The legacy Groovy-to-Java transpiler (~9,800 lines of complex AST walking logic) has been deleted from this distro.
- **Native Alignment**: We now use the exact same compilation pipeline as upstream, just executed at build-time, so the generated classes are the ones upstream's compilers emit. The loading, wiring and registration around them are the distro's same-FQCN replacements, which the MAL / LAL / TAL / Hierarchy comparison tests hold to upstream's behavior.

---

## Classpath Scanning (Guava ClassPath)

### What Happens
`ClassPath.from()` used in `SourceReceiverImpl.scan()`, `AnnotationScan`, `MeterSystem`, `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder`.

### What Was Solved
- `AnnotationScan` and `SourceReceiverImpl` replaced with same-FQCN classes that read from build-time manifests under `META-INF/annotation-scan/`: one per annotation an upstream `AnnotationListener` listens for (the precompiler asks each listener: today `ScopeDeclaration`, `Stream`, `Disable`, `MultipleDisable`), plus `SourceDispatcher` and `ISourceDecorator`. A missing manifest, or a class one lists that does not load, fails the boot, as upstream's scan fails on a class it cannot load.
- `DefaultMetricsFunctionRegistry`, `FilterMatchers`, `MetricsHolder` — these only run inside the OAL engine at build time, not at runtime. Automatically solved.
- `MeterSystem` replaced with a same-FQCN class that takes the meter function classes (18) and the pre-generated meter classes from the rule registry. Solved as part of MAL immigration.

---

## Module System & Configuration

### Current Behavior
`ModuleManager` uses `ServiceLoader` (SPI). `application.yml` selects providers. Config loaded via reflection (`Field.setAccessible` + `field.set` in `YamlConfigLoaderUtils.copyProperties`).

### Approach (this repo)
1. **Module loading as upstream**: `GraalVMOAPServerStartUp` boots through upstream's `ModuleManager`, which loads the modules `application.yml` names via `ServiceLoader`; a module whose selector is `-` is not loaded. Native image registers every provider listed in `META-INF/services` on the build classpath, so the classpath carries only the shipped modules: `server-starter-for-graalvm` excludes all of `server-starter`'s dependencies (every upstream plugin), and `oap-graalvm-server` declares the modules the distro ships. The distro's own `graalvm-unsupported-admin` module registers through the same SPI.
2. **Simplified config file**: Only knobs for the shipped providers
3. **Config loading**: Build-time tool scans all `ModuleConfig` subclass fields → generates same-FQCN replacement of `YamlConfigLoaderUtils` that sets each field through its class's setter where it has one, the value checked and converted as upstream's `Field.set` does (`ConfigFieldValues`), and writes each field no setter writes (none declared, or a final or static field) through `Field.set` itself as upstream does (`ConfigFieldValues.setByField`; a constant is refused with `IllegalAccessException`, as upstream). The precompiler generates the config classes' reflection metadata from `module-config-classes.txt` (upstream's `ModuleDefine.prepare` calls their no-arg constructors) and registers the fields set through `Field.set` (a final instance one writable, `allowWrite`) from `module-config-field-set.txt`, both written by the config-generator.

**Details**: [config-init-immigration.md](internals/config-init-immigration.md)

### What Was Built
- `GraalVMOAPServerStartUp` — entry point: the compiled-rule checks, then upstream's `ModuleManager.init`
- `ApplicationYmlModulesTest` — the classpath carries exactly the accepted providers, and every provider option of the shipped `application.yml` resolves to one of them
- `application.yml` — simplified config for selected providers
- `ConfigInitializerGenerator` — build-time tool that scans config classes and generates `YamlConfigLoaderUtils` replacement
- `YamlConfigLoaderUtils` — same-FQCN replacement using type-dispatch + setters, and `Field.set` for a field no setter writes, as upstream

---

## Same-FQCN Packaging (Repackaged Modules)

### Problem

Same-FQCN replacement classes need to shadow upstream originals. Classpath ordering tricks confuse developers and AI tools.

### Solution: Per-JAR Repackaged Modules (`oap-libs-for-graalvm`)

Each upstream JAR that has replacement classes gets a corresponding `*-for-graalvm` module under `oap-libs-for-graalvm/`. The module uses `maven-shade-plugin` to:
1. Include only the upstream JAR in the shade
2. Exclude the specific `.class` files being replaced
3. Produce a JAR containing: all upstream classes MINUS replaced ones PLUS our replacements

`oap-graalvm-server` depends on `*-for-graalvm` JARs instead of originals. Original upstream JARs are forced to `provided` scope via `<dependencyManagement>` to prevent transitive leakage.

### Same-FQCN Replacement Classes

**Non-trivial replacements (load pre-compiled assets from manifests or the rule registry):**

| Module | Replacement Classes | Purpose |
|---|---|---|
| `server-core-for-graalvm` | `OALEngineLoaderService`, `AnnotationScan`, `SourceReceiverImpl`, `MeterSystem`, `HierarchyDefinitionService`, `LayerExtensionLoader` | OAL / meter classes, hierarchy and layer extensions from the rule registry, annotation scans from manifests, instead of Javassist/ClassPath/YAML |
| `library-util-for-graalvm` | `YamlConfigLoaderUtils` | Set config fields via their setters, and `Field.set` where no setter writes one, as upstream |
| `meter-analyzer-for-graalvm` | `DSL` (v2), `FilterExpression`, `Rules` | Pre-compiled v2 `MalExpression` / `MalFilter` classes and the rule files from the rule registry |
| `log-analyzer-for-graalvm` | `DSL` (v2), `LALConfigs` | Pre-compiled v2 `LalExpression` classes and the rule files from the rule registry |
| `trace-analyzer-for-graalvm` | `TalDSL`, `TALConfigs` | Pre-compiled v2 `TalExpression` classes, their metric declarations and the rule files from the rule registry |
| `zabbix-receiver-for-graalvm` | `ZabbixConfigs` | The Zabbix configs from the rule registry instead of `zabbix-rules/*.yaml` |

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

## Additional GraalVM Risks

| Risk | Status | Mitigation |
|------|--------|------------|
| **Reflection** (annotations, OAL enricher, HTTP handlers, GraphQL types) | SOLVED | Auto-generated by precompiler from manifests; `log4j2-reflect-config.json` for Log4j2 plugins |
| **gRPC / Netty / Armeria** | SOLVED | GraalVM reachability metadata repo handles these automatically |
| **Resource loading** (`ResourceUtils`, config files) | SOLVED | The `ResourceUtils` replacement reads the native image's `config/` next to its executable (the JVM: the class path, as upstream); other resources are embedded by resource globs |
| **Log4j2** | SOLVED | Console-only `log4j2.xml` avoids RollingFile reflection chain; Log4j2 plugin classes in `log4j2-reflect-config.json` |
| **Kafka client** (for Kafka fetcher) | Untested | Known GraalVM support, may need config |
| **Kubernetes client 6.7.1** (for cluster + config) | Untested | Has GraalVM support, may need config at runtime |

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
| `server-clusters.yml` | 1 | `ServerClusterConfig` via `ResourceUtils.read()` | Brokers, databases and caches named as one cluster by the trace analyses (empty by default) |
| `metadata-service-mapping.yaml` | 1 | `ResourceUtils.read()` | Metadata service mapping |
| `service-apdex-threshold.yml` | 1 | `ApdexThresholdConfig` via `ResourceUtils.read()` | APDEX thresholds |
| `trace-sampling-policy-settings.yml` | 1 | `TraceSamplingPolicyWatcher` via `ResourceUtils.read()` | Trace sampling |
| `gen-ai-config.yml` | 1 | `GenAIConfigLoader` via `ResourceUtils.read()` | GenAI provider/model pricing |
| `ai-evaluation.yml` | 1 | `AIEvaluationConfigLoader` via `ResourceUtils.read()` | LLM-as-judge endpoint, prompt and tasks (read only when `ai-evaluation` is enabled) |
| `cilium-rules/**` | 2 | `CiliumFetcherProvider` via `ResourceUtils.getPathFiles()` | Cilium flow rules |
| `openapi-definitions/**` | 1 | `EndpointNameGrouping` via `ResourceUtils.getPathFiles()` | OpenAPI grouping definitions |

**Total: 17 files** included in the distro `config/` directory. No rule file is: the rules are compiled into the
image, and a rule file under `config/` fails the boot. (Upstream 11.0.0 removed the
bundled `ui-initialized-templates/` + `UITemplateInitializer`; dashboard templates are now managed
via the `ui-management` REST surface.)

### Pre-compiled at Build Time (NOT in distro)

These files are consumed by `build-tools/precompiler` during the build. Their
expressions, scripts, and metric definitions are compiled into `.class` files
packaged in JARs; no rule file is read as configuration at runtime. Their sources still ship, for two uses:

- the generated jar embeds the MAL, LAL and TAL rule sources (`META-INF/rule-source/`, 117 in the default build), which
  the replacement loaders read only to pass them through upstream's `RuleSetMerger`, recording them for the read-only
  rule catalog, as upstream's loaders do;
- both distributions ship `compiled-rules/`, a record of every staged rule file (130 in the default build, with its
  `BUILD-INFO`), a TAL file the image skips for a module it does not ship included.

| Category | Count | Pre-compiled Into | Tool |
|---|---|---|---|
| `oal/*.oal` | 10 | ~640 metrics + ~640 builders + ~47 dispatchers (Javassist) | OAL v2 engine |
| `meter-analyzer-config/*.yaml` | 14 | ~180 `MalExpression` classes (ANTLR4+Javassist) + meter classes | MAL v2 compiler |
| `otel-rules/**/*.yaml` | 75 | ~1320 `MalExpression` classes + meter classes | MAL v2 compiler |
| `log-mal-rules/*.yaml` | 4 | ~4 `MalExpression` classes | MAL v2 compiler |
| `envoy-metrics-rules/*.yaml` | 2 | ~26 `MalExpression` classes + meter classes | MAL v2 compiler |
| `telegraf-rules/*.yaml` | 1 | ~20 `MalExpression` classes + meter classes | MAL v2 compiler |
| `zabbix-rules/*.yaml` | 1 | ~15 `MalExpression` classes + meter classes | MAL v2 compiler |
| `lal/*.yaml` | 12 | 19 `LalExpression` classes (ANTLR4+Javassist) | LAL v2 compiler |
| `tal/*.yaml` | 9 | 14 `TalExpression` classes (ANTLR4+Javassist) + 28 meter classes | TAL v2 compiler |
| `hierarchy-definition.yml` | 1 | ~4 `BiFunction` hierarchy rule classes | Hierarchy v2 compiler |

**Total: 129 files** consumed at build time, producing ~1330 OAL classes, ~1570 MAL expression classes, ~1560 meter classes, 19 LAL expression classes, 14 TAL expression classes, and 4 hierarchy rule classes.

The rest of each rule file (metric prefixes, rule names, filters, the hierarchy, layer extensions, Zabbix entities)
is generated into the static rule registry (`CompiledRules`), the code the replacement loaders read instead of
filesystem YAML.

### Not Included (upstream-only)

| File | Reason |
|---|---|
| `application.yml` (upstream) | Replaced by distro's own simplified `application.yml` |

---

## Build Workflow

### Build System
- Maven + Makefile orchestrates building on top of the skywalking submodule
- Oracle GraalVM 25.5 in CI (`.github/workflows/ci.yml`), the same release the Makefile looks for locally
- JVM-mode starter `GraalVMOAPServerStartUp`, booting through upstream's `ModuleManager` like the native image
- Simplified config file for selected modules (`application.yml`)

### Build-Time Pre-Compilation

All five DSL compilers (OAL/MAL/LAL/TAL/Hierarchy) use ANTLR4 + Javassist v2 engines. The unified precompiler (`build-tools/precompiler`) runs them all at build time, capturing generated `.class` files into the output JAR.

**OAL**: OAL v2 engine exports `.class` files (10 defines, ~640 metrics, ~640 builders, ~47 dispatchers). 7 annotation/interface manifests. Same-FQCN `OALEngineLoaderService` registers the classes the rule registry lists.

**MAL**: MAL v2 compiler processes 96 YAML files → ~1570 `MalExpression` classes + ~1530 Javassist meter classes. Deterministic `DslSourceRef` class naming. Same-FQCN v2 `DSL.java` takes pre-compiled classes by source coordinates from the rule registry.

**LAL**: LAL v2 compiler processes 12 YAML files → 19 `LalExpression` classes. Deterministic `DslSourceRef` class naming. Same-FQCN v2 `DSL.java` takes pre-compiled classes by source coordinates from the rule registry.

**TAL**: TAL v2 compiler processes 9 YAML files → 14 `TalExpression` classes + 28 meter classes. Same-FQCN `TalDSL` loads pre-compiled classes by source coordinates, wires their function objects by the recorded function-registry keys and creates the recorded metrics.

**Hierarchy**: Hierarchy v2 compiler processes 4 rules → 4 `BiFunction` classes. Same-FQCN `HierarchyDefinitionService` takes the definition and the pre-compiled rules from the rule registry.

**Config initialization**: `ConfigInitializerGenerator` generates same-FQCN `YamlConfigLoaderUtils` calling a field's setter where its class has one; the fields no setter writes (`module-config-field-set.txt`) go through `Field.set`, as upstream, registered for the native image (a final instance one writable).

**Static rule registry**: the precompiler generates a Java class per rule file (what upstream's loader read from it, as code) plus the `CompiledRules` index and compiles them into the generated jar; the replacement loaders (`Rules`, `LALConfigs`, `TALConfigs`, `ZabbixConfigs`, `HierarchyDefinitionService`, `LayerExtensionLoader`) read it instead of filesystem YAML. The `config-data/*.json` and rule manifests the engine passes write are build intermediates, left out of the jar.

**Module system**: upstream's `ModuleManager` and `ModuleDefine`, unchanged: `application.yml` names the modules to load, found through the module SPI.

**Distro resource packaging**: 18 runtime files → distro `config/` (the runtime configuration table plus `version.properties`), 130 staged rule files compiled into the image, recorded in `compiled-rules/`, the MAL / LAL / TAL ones (117) also embedded as the sources the loaders pass through `RuleSetMerger`. Assembly descriptor (`distribution.xml`) packages runtime config files from upstream.

### Native Image Build

- `native-maven-plugin` (GraalVM buildtools 0.10.4) in `oap-graalvm-native` with `-Pnative` profile
- `reflect-config.json` auto-generated by precompiler from the build manifests (OAL, MAL, LAL, TAL, meter, HTTP handlers, GraphQL types)
- `log4j2-reflect-config.json` for Log4j2 plugin classes; console-only `log4j2.xml` with `SW_LOG_LEVEL` env var
- gRPC/Netty/Protobuf/Armeria via GraalVM reachability metadata repository
- Auto-scanned reflection metadata: Armeria HTTP handlers (~27, incl. admin-server/status/inspect/ui-management), GraphQL resolvers (~32), GraphQL types (~182), config POJOs (8)
- Native binary: ~203MB, boots to full module init with all HTTP endpoints functional

### Native Distro Packaging

- Assembly descriptor (`native-distribution.xml`) packages native binary + config files
- `Dockerfile.native` packages native distro into `debian:bookworm-slim`
- `docker-compose.yml` with BanyanDB + OAP native services
- CI pipeline: multi-arch native build (amd64 + arm64) with Docker manifest push to GHCR

### Remaining Verification
- Verify all receiver plugins work (gRPC + HTTP endpoints)
- Verify all query APIs work (GraphQL, PromQL, LogQL, TraceQL, Zipkin)
- Verify cluster mode (K8s)
- Verify alarm module
- Performance benchmarking vs JVM

---

## Upstream Changes Tracker

Upstream SkyWalking PR #13723 replaced Groovy with ANTLR4 + Javassist v2 engines for MAL/LAL/Hierarchy. Groovy is completely removed from all production dependencies. All four DSL compilers now share the same pipeline and expose build-time compilation APIs.

No further upstream changes needed. All GraalVM incompatibilities are resolved in this distro via same-FQCN replacement and build-time pre-compilation:
- OAL: build-time class export works via existing debug API (`setGeneratedFilePath()`)
- MAL: build-time compilation via `MALClassGenerator.setClassOutputDir()`
- LAL: build-time compilation via `LALClassGenerator.setClassOutputDir()`
- TAL: build-time compilation via `TALClassGenerator.setClassOutputDir()`; metric creation via `createMetrics()` on the build-time `MeterSystem`
- Hierarchy: build-time compilation via `HierarchyRuleClassGenerator.setClassOutputDir()`
