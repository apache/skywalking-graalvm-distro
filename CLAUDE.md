# SkyWalking GraalVM Distro - Development Guide

**Every `CLAUDE.md` in this repo has an `AGENTS.md` symlink next to it** (`ln -s CLAUDE.md AGENTS.md`, committed as a
git symlink), as upstream does, so agents that read `AGENTS.md` get the same guide. Edit the `CLAUDE.md`, never the
link, and add the link in the same change whenever you add a `CLAUDE.md`. Keep `CLAUDE.md` files out of resource
directories: a jar would store the link as a symlink to the absolute build path. The skills live in `.claude/skills/`;
`.agents/skills` is a relative symlink to it (`ln -s ../.claude/skills .agents/skills`), the path other agents (Codex,
Antigravity) read skills from, so a new skill needs no link of its own. Edit skills under `.claude/skills/`.

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `build-tools/precompiler/` — Build-time precompiler: runs OAL + MAL + LAL + TAL + Hierarchy v2 engines at Maven compile time, exports `.class` files, manifests, and auto-generated `reflect-config.json` into `precompiler-*-generated.jar`. `Precompiler` only orders the passes: one class per engine (`OalPass`, `MalPass`, `LalPass`, `TalPass`, `HierarchyPass`), plus `ClasspathScans`, `RuleSourceExport`, `RegistryGenerator` and `NativeImageMetadata`.
- `build-tools/config-generator/` — Build-time config code generator: scans `ModuleConfig` subclasses and generates `YamlConfigLoaderUtils` replacement.
- `oap-libs-for-graalvm/` — Per-JAR repackaged modules using `maven-shade-plugin` for same-FQCN class replacements.
- `oap-graalvm-server/` — GraalVM-ready OAP server module (JVM distro) with same-FQCN replacement classes and comprehensive test suites.
- `oap-graalvm-native/` — Native image module: `native-maven-plugin` configuration, native-specific `log4j2.xml`, `log4j2-reflect-config.json`, and native distribution assembly.
- `docker/` — `Dockerfile.native` (runtime image) and `docker-compose.yml` (BanyanDB + OAP native).
- `docs/` — User-facing docs (quick-start, features, FAQ, configuration) and `docs/internals/` for build-time immigration details.
- Root-level Maven + Makefile — Orchestrates building on top of the submodule.

## Key Principles
1. **Minimize upstream changes.** SkyWalking is a submodule. Changes to it require separate upstream PRs and syncing back.
2. **Build-time class export.** All runtime code generation (OAL/MAL/LAL/TAL/Hierarchy via ANTLR4 + Javassist) runs at build time. Export `.class` files into native-image classpath.
3. **Shipped module set, booted as upstream.** The build decides which modules/providers the image carries (only theirs are on the classpath: `server-starter-for-graalvm` excludes all of `server-starter`'s dependencies); at boot upstream's `ModuleManager` loads the ones `application.yml` names through the module SPI, which native image supports. See docs/distro-policy.md for the full module table.
4. **JDK 25, GraalVM 25.5.** Already compiles and runs.
5. **Minimal comments.** Comment only what the code can't say for itself — a non-obvious invariant, the *why* behind a non-obvious choice, or cross-cutting context (e.g. "must match upstream X"). Don't restate the code or annotate the obvious. When a same-FQCN replacement diverges from upstream, one line on *why* is enough.

## Technical Notes
- **V2 DSL engines**: All five DSL compilers (OAL, MAL, LAL, TAL, Hierarchy) use the same pipeline: ANTLR4 parse → immutable AST → Javassist bytecode. Upstream PR #13723 removed Groovy from all production code. The precompiler runs these v2 engines at build time via `setClassOutputDir()` / `setClassNameHint()` / `setSourceRef()`, capturing `.class` files into the output JAR. Since 11.0.0 generated class names carry the YAML line (`{yaml}_L{line}_{hint}`, via `DslSourceRef` + `DslClassNaming`), so build-time names match the JVM distro.
- **OAL engine**: Generates ~1330 metrics/builder/dispatcher classes via Javassist at startup. Run at build time via `OALEngineV2.start()` with `setGeneratedFilePath()`, per `OALDefine` `INSTANCE` on the precompiler's class path; `notifyAllListeners()` hands each script's classes to recording listeners. At boot the `OALEngineLoaderService` replacement registers a define's script, its disables first, when its provider loads it, as upstream (the overlay's own scripts, which no provider loads, at the first load, after its define). Uses ANTLR4 + FreeMarker + Javassist.
- **MAL compiler**: Compiles ~1570 MAL expressions from 96 YAML rule files (incl. `meter-analyzer-config`, which upstream loads through `Rules` since 11.0.0) via `MALClassGenerator`. Each expression becomes a `MalExpression` implementation class. Closures (TagFunction, ForEachFunction, PropertiesExtractor, DecorateFunction) compile into companion classes next to the expression class. The precompiler runs upstream's `MetricConvert` over every rule file (Zabbix through upstream's `ZabbixConfigs` and `ZabbixMetrics`, which compiles a file per host pattern, as the receiver does); the build-time `DSL` / `FilterExpression` copies record each class with the coordinates, metric name and text it was compiled from, and the runtime replacements take it by the coordinates `MetricConvert` passes, failing the boot when the name or text differs.
- **LAL compiler**: Compiles 19 LAL rules from 12 YAML files via `LALClassGenerator`. Each rule becomes a `LalExpression` implementation class. Which rules upstream skips at boot and the input/output types it compiles them with come from upstream `LogFilterListener.Factory` itself, called through `LalStaticRuleResolver` (a precompiler class in its package). The rule registry maps `lal/<file>.yaml:<line>` (the `DslSourceRef` upstream passes) to the class compiled from the rule's name, DSL text and types, and records the effective input type, which upstream `LogFilterListener` uses to route Envoy HTTP vs TCP access logs. The registry lists the files in the order one upstream `LALConfigs.load` over them all returned at build time (the order their rules run on a log), which the runtime `LALConfigs` returns them in. An ad-hoc rule (the LAL log test tool) cannot run in the image.
- **TAL compiler**: Compiles every `tal/*.yaml` whose `requiredModules` the image ships (`AcceptedModules.shippedModuleNames()`; shipped: 9 files, 14 rules — `otlp-general` + `otlp-ai-evaluation` over OTLP spans, seven `native-*` files over SkyWalking agent segments since SWIP-17) via `TALClassGenerator` (upstream `trace-analyzer` module). Each rule becomes a `TalExpression` class; the precompiler also runs `createMetrics` on the build-time `MeterSystem` it shares with MAL, so the 28 `otlp_*` meter classes exist (`tal-meter-classes.txt`). Generated classes call functions through private function-object fields that upstream sets from a `TALFunctionRegistry` after `newInstance()`; the precompiler binds a registry as `SpanFilterListener.Factory` does (ServiceLoader order) and records each field's registry key. The rule registry maps `tal/<file>.yaml:<line>` to the class, its input type and alias, its function fields and its metric declarations, which the same-FQCN `TalDSL` wires, claims and creates at boot (`create` + `limitLabels`); `TALConfigs` takes the rule files from it (incl. `requiredModules`, `loadInOrder` for `nativeTalFiles`), the `talFiles` in the order one upstream `TALConfigs.load` over them returned at build time. With injection on, each class carries upstream's debug holder (built by its no-arg constructor) and probes; upstream's `SpanFilterListener` publishes the holders to `dsl-debugging` through `TalStaticBindingHook`.
- **Hierarchy compiler**: Compiles 4 hierarchy matching rules via `HierarchyRuleClassGenerator`. Each rule becomes a `BiFunction<Service, Service, Boolean>` implementation class, in the rule registry by name with the rest of `hierarchy-definition.yml`. The same-FQCN `HierarchyDefinitionService` builds upstream's `MatchingRule`s from it, so upstream's `HierarchyService` runs unchanged.
- **Static rule registry**: after the engine passes, the precompiler (`RegistryGenerator`) generates Java source — a class per rule file holding, as code, what upstream's loader read from it (the MAL `Rule`, `LALConfigs`, `TALConfigs`, `ZabbixConfig`s, the hierarchy, `layer-extensions.yml`) and constructing the classes compiled from it — plus the `CompiledRules` index, compiles it in process (`javax.tools`) into the generated jar next to the hand-written holder types (`org.apache.skywalking.oap.server.graalvm.rules`). The rules run from `CompiledRules` alone: no rule YAML, JSON or manifest is compiled or bound at runtime, and a rule file in the directory OAP reads its configuration from (the native image's `config/`, the JVM's `application.yml` directory) fails the boot (`CompiledRulesCheck`): rule files enter the image only at build time. The replacement loaders (`Rules`, `LALConfigs`, `TALConfigs`) also read the packaged rule sources (`META-INF/rule-source/`, `RuleSources`) of the files they load, only to pass them through upstream's `RuleSetMerger`, which records them in `StaticRuleRegistry` for the read-only rule catalog, as upstream's loaders do. The engine passes still write their `META-INF` manifests and `config-data/*.json`, as build intermediates the registry and `reflect-config.json` are generated from; the generated jar leaves them out. The `*-for-graalvm` libs compile against the generated jar (`provided`), so `build-distro` installs the precompiler first. `RegistryParityTest` pins the registry to upstream's loaders for every bundled file.
- **Rules overlay + compiled activation**: `RulesStager` (precompiler, `process-classes` before `precompile`) rebuilds `build-tools/precompiler/target/rule-resources` on every build — upstream's `server-starter` resources with `RULES_OVERLAY` (`-Drules.overlay`) merged in file by file per its `rules-overlay.yaml` (add / override / exclude, validated: undeclared, contradicting or stale entries fail the build) — and writes `target/rule-overlay/` (`overlay-inventory.txt`, the `compiled-rules/` record both assemblies ship). The precompiler, its tests and the server tests read rules from that staged tree, never from the submodule directly. Every compiled rule file is active: `RegistryGenerator` records, per rule-selecting setting (`module.provider.field`), every file compiled for it in `CompiledRules.activation()`, and `CompiledRuleActivation` (boot) rejects those settings in `application.yml` / `SW_*` env / system properties and fills them from the registry. An added `oal/*.oal` no define references gets a synthetic `OALDefine` (`core.source` only); an excluded script's define is skipped at build and left out of the OAL listing at runtime. `make verify-rules RULES_OVERLAY=<dir>` is the overlay gate; `M2_REPO=<dir>` builds with another Maven repository. See docs/rules-overlay.md.
- **MeterSystem**: `MeterSystem.create()` uses Javassist to generate ~1500 meter function subclasses. Run at build time, export `.class` files. Separate from MAL DSL compilation. The runtime replacement is upstream's boot `create` with the class taken from the registry (with the `@MeterFunction` classes) instead of generated.
- **Same-FQCN replacement**: Classes in `oap-libs-for-graalvm/*/src/main/java/` with the same fully-qualified class name as upstream classes are repackaged via `maven-shade-plugin` (original `.class` excluded). Used for v2 `DSL.java` (MAL/LAL), `TalDSL.java` (TAL), `MeterSystem.java`, `HierarchyDefinitionService.java`, `LayerExtensionLoader.java`, `ZabbixConfigs.java`, etc.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` replaced with a generated same-FQCN version that calls a field's setter where its class has one; a field no setter writes (none declared, or a final or static field) still goes through `Field.set` as upstream, registered in `reflect-config.json` (a final instance field with `allowWrite`; a constant refused with `IllegalAccessException`, as upstream). No config class is copied to add setters. See [docs/internals/config-init-immigration.md](docs/internals/config-init-immigration.md).
- **DSL live debugger**: the precompiler calls `DSLDebugCodegenSwitch.enableInjection()` before every generator pass (and `setContent(...)` on the MAL/LAL/TAL generators), so the debug probes are compiled into the pre-compiled OAL/MAL/LAL/TAL classes and upstream's `dsl-debugging` module (incl. the `/runtime/tal/*` listing) and `trace-pairing-debugging` module run as-is; runtime-rule stays a 501 stub. Adding any module needs a `build-tools/build-common` `AcceptedModules` entry too — the config-generator only emits config loaders for accepted providers; `ApplicationYmlModulesTest` requires `AcceptedModules` to equal the inventory's ACCEPTED providers, and the generator fails on an accepted provider it cannot find or load.
- **Reflection metadata**: Precompiler auto-generates `reflect-config.json` by scanning Armeria HTTP handlers (and the bodies their route signatures name), GraphQL resolvers/types, config POJOs, `StreamData` classes, the OAL/MAL/LAL/TAL/Hierarchy build manifests, the proto descriptor closure of the LAL input types and of the OTLP/Tempo messages printed as JSON (protobuf `JsonFormat` reflects on generated getters), the LAL values whose getters `LalRuntimeHelper` calls by reflection (walked from every type a LAL rule reads values from, through the getters `LALCallables` allows), and the LAL SPI implementations. `log4j2-reflect-config.json` is manually maintained for Log4j2 plugin classes.
- **Native image**: `oap-graalvm-native` uses `native-maven-plugin` with `-Pnative` profile. Console-only `log4j2.xml` avoids RollingFile reflection chain. ~203MB binary, boots to full module init.

## Test Suites
- **MAL**: Verify pre-compiled `MalExpression` classes from build-time JAR produce identical results to fresh v2 compilation. Covers all 96 MAL YAML rule files.
- **LAL**: Every rule of every LAL file has its class where the runtime `DSL` looks it up (`LAL*Test`, `LalRuntimeTypeParityTest`, which also holds the rule's input and output types to upstream's own resolution on the server classpath); the runtime `DSL` takes only the class compiled from exactly the name, DSL text and types upstream passes (`LalDSLSelectTest`), so both compile with the same engine from the same inputs. `LalInputTypeReflectionTest` checks the reflection metadata of the LAL inputs and of `LALCallables`.
- **TAL**: `TALRecordedTraceComparisonTest` runs the OTLP rules over upstream's recorded OTel Demo trace, plus added messaging / database / cache calls under test server clusters, through a fresh compile and the pre-compiled classes (identical metrics, traffic, aliases, source records); `NativeTALComparisonTest` runs every `nativeTalFiles` rule over upstream's native-trace checker fixtures (copied from the submodule at build time) through `SwNativeRuleRunner`, fresh vs pre-compiled, cold and warm; `TALPrecompiledRuleTest` checks coordinates, created metrics, `requiredModules` and the listed order. They iterate every file under `tal/`; `TALPrecompileTest` (precompiler) compiles operator-style fixtures and `TALDocScriptsTest` the scripts of upstream's TAL docs and practice pages. Keep the TAL code free of shipped-rule assumptions: TAL files are user-editable.
- **Hierarchy**: `RegistryParityTest` builds upstream's `HierarchyDefinitionService` over the staged `hierarchy-definition.yml` with upstream's `CompiledHierarchyRuleProvider` and requires the registry to hold its layer pairs, rules, layer levels and class names; every compiled matcher decides upstream's hierarchy test pairs (`test-hierarchy-definition.data.yaml`) as a fresh compile of its rule does. `HierarchyPrecompileTest` compiles rules under names that need escaping.

See [docs/internals/dsl-immigration.md](docs/internals/dsl-immigration.md) for details.

## Build Commands

The native image is built with GraalVM 25.5 (`GRAALVM_VERSION` in the Makefile, `version: '25.5'` on `setup-graalvm` in CI). The Makefile picks the newest sdkman `25.5*-graal` (Oracle before `-graalce`), else a GraalVM `JAVA_HOME`, else the newest sdkman GraalVM 25, and warns when the pick is not 25.5; `make GRAAL_HOME=<dir>` overrides it. `make native-image-macos` runs `$(GRAALVM_IMAGE)` (`native-image-community:25i5`).

```bash
# Full build (precompiler + tests + server)
make build-distro

# Precompiler only
mvn -pl build-tools/precompiler -am install -DskipTests

# Run tests
mvn -pl oap-graalvm-server test

# Native image (requires GraalVM with native-image)
make native-image

# Native image for Docker on macOS (cross-compiles via Docker container)
make native-image-macos

# Package native binary into Docker image
make docker-native

# Run with docker-compose (BanyanDB + OAP native)
docker compose -f docker/docker-compose.yml up
```

## Git Commit Rules
- **No Co-Authored-By**: Do not add `Co-Authored-By` lines to commit messages.
- **No auto commit or push**: Do not commit or push unless explicitly asked by the user.

## CI Rules
- **Docker tags must use full commit SHA** (`git rev-parse HEAD`), never short SHA (`--short`). This applies to the `commit-sha` output in CI workflows.

## Selected Modules
- **Storage**: BanyanDB
- **Cluster**: Standalone, Kubernetes
- **Configuration**: Kubernetes
- **Receivers/Query/Analyzers/Alarm/Telemetry/Other**: Full feature set (see docs/distro-policy.md for details)
