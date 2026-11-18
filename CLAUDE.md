# SkyWalking GraalVM Distro - Development Guide

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `build-tools/precompiler/` — Build-time precompiler: runs OAL + MAL + LAL + TAL + Hierarchy v2 engines at Maven compile time, exports `.class` files, manifests, and auto-generated `reflect-config.json` into `precompiler-*-generated.jar`.
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
3. **Fixed module wiring.** Module/provider selection is hardcoded in this distro — no SPI discovery. See docs/distro-policy.md for the full module table.
4. **JDK 25, GraalVM 25.5.** Already compiles and runs.
5. **Minimal comments.** Comment only what the code can't say for itself — a non-obvious invariant, the *why* behind a non-obvious choice, or cross-cutting context (e.g. "must match upstream X"). Don't restate the code or annotate the obvious. When a same-FQCN replacement diverges from upstream, one line on *why* is enough.

## Technical Notes
- **V2 DSL engines**: All five DSL compilers (OAL, MAL, LAL, TAL, Hierarchy) use the same pipeline: ANTLR4 parse → immutable AST → Javassist bytecode. Upstream PR #13723 removed Groovy from all production code. The precompiler runs these v2 engines at build time via `setClassOutputDir()` / `setClassNameHint()` / `setSourceRef()`, capturing `.class` files into the output JAR. Since 11.0.0 generated class names carry the YAML line (`{yaml}_L{line}_{hint}`, via `DslSourceRef` + `DslClassNaming`), so build-time names match the JVM distro.
- **OAL engine**: Generates ~1330 metrics/builder/dispatcher classes via Javassist at startup. Run at build time via `OALEngineV2.start()` with `setGeneratedFilePath()`. Uses ANTLR4 + FreeMarker + Javassist.
- **MAL compiler**: Compiles ~1570 MAL expressions from 96 YAML rule files (incl. `meter-analyzer-config`, which upstream loads through `Rules` since 11.0.0) via `MALClassGenerator`. Each expression becomes a `MalExpression` implementation class. Closures (TagFunction, ForEachFunction, PropertiesExtractor, DecorateFunction) compile into companion classes next to the expression class. Runtime lookup by expression text (and filter literal), in the rule registry.
- **LAL compiler**: Compiles 19 LAL rules from 12 YAML files via `LALClassGenerator`. Each rule becomes a `LalExpression` implementation class. The rule registry maps `lal/<file>.yaml:<line>` (the `DslSourceRef` upstream passes) to the class and records the effective input type, which upstream `LogFilterListener` uses to route Envoy HTTP vs TCP access logs.
- **TAL compiler**: Compiles every `tal/*.yaml` (shipped: 9 files, 14 rules — `otlp-general` + `otlp-ai-evaluation` over OTLP spans, seven `native-*` files over SkyWalking agent segments since SWIP-17) via `TALClassGenerator` (upstream `trace-analyzer` module). Each rule becomes a `TalExpression` class; the precompiler also runs `createMetrics` on the build-time `MeterSystem` it shares with MAL, so the 28 `otlp_*` meter classes exist (`tal-meter-classes.txt`). Generated classes call functions through private function-object fields that upstream sets from a `TALFunctionRegistry` after `newInstance()`; the precompiler binds a registry as `SpanFilterListener.Factory` does (ServiceLoader order) and records each field's registry key. The rule registry maps `tal/<file>.yaml:<line>` to the class, its input type and alias, its function fields and its metric declarations, which the same-FQCN `TalDSL` wires, claims and creates at boot (`create` + `limitLabels`); `TALConfigs` takes the rule files from it (incl. `requiredModules`, `loadInOrder` for `nativeTalFiles`). TAL has no debug probes upstream.
- **Hierarchy compiler**: Compiles 4 hierarchy matching rules via `HierarchyRuleClassGenerator`. Each rule becomes a `BiFunction<Service, Service, Boolean>` implementation class, in the rule registry by name with the rest of `hierarchy-definition.yml`.
- **Static rule registry**: after the engine passes, the precompiler (`RegistryGenerator`) generates Java source — a class per rule file holding, as code, what upstream's loader read from it (the MAL `Rule`, `LALConfigs`, `TALConfigs`, `ZabbixConfig`s, the hierarchy, `layer-extensions.yml`) and constructing the classes compiled from it — plus the `CompiledRules` index, compiles it in process (`javax.tools`) into the generated jar next to the hand-written holder types (`org.apache.skywalking.oap.server.graalvm.rules`). Every runtime replacement reads only `CompiledRules`: no rule YAML, JSON or manifest is read at runtime, and a rule file under `config/` fails the boot (`CompiledRulesCheck`). The engine passes still write their `META-INF` manifests and `config-data/*.json`, as build intermediates the registry and `reflect-config.json` are generated from; the generated jar leaves them out. The `*-for-graalvm` libs compile against the generated jar (`provided`), so `build-distro` installs the precompiler first. `RegistryParityTest` pins the registry to upstream's loaders for every bundled file.
- **Rules overlay + compiled activation**: `RulesStager` (precompiler, `process-classes` before `precompile`) rebuilds `build-tools/precompiler/target/rule-resources` on every build — upstream's `server-starter` resources with `RULES_OVERLAY` (`-Drules.overlay`) merged in file by file per its `rules-overlay.yaml` (add / override / exclude, validated: undeclared, contradicting or stale entries fail the build) — and writes `target/rule-overlay/` (`overlay-inventory.txt`, the `compiled-rules/` record both assemblies ship). The precompiler, its tests and the server tests read rules from that staged tree, never from the submodule directly. Every compiled rule file is active: `RegistryGenerator` records, per rule-selecting setting (`module.provider.field`), every file compiled for it in `CompiledRules.activation()`, and `CompiledRuleActivation` (boot) rejects those settings in `application.yml` / `SW_*` env / system properties and fills them from the registry. An added `oal/*.oal` no define references gets a synthetic `OALDefine` (`core.source` only); an excluded script's define is skipped at build and left out of the OAL listing at runtime. `make verify-rules RULES_OVERLAY=<dir>` is the overlay gate; `M2_REPO=<dir>` builds with another Maven repository. See docs/rules-overlay.md.
- **MeterSystem**: `MeterSystem.create()` uses Javassist to generate ~1500 meter function subclasses. Run at build time, export `.class` files. Separate from MAL DSL compilation.
- **Same-FQCN replacement**: Classes in `oap-libs-for-graalvm/*/src/main/java/` with the same fully-qualified class name as upstream classes are repackaged via `maven-shade-plugin` (original `.class` excluded). Used for v2 `DSL.java` (MAL/LAL), `TalDSL.java` (TAL), `MeterSystem.java`, `HierarchyDefinitionService.java`, `LayerExtensionLoader.java`, `ZabbixConfigs.java`, etc.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` replaced with same-FQCN version that uses Lombok setters instead of `Field.setAccessible()`. See [docs/internals/config-init-immigration.md](docs/internals/config-init-immigration.md).
- **DSL live debugger**: the precompiler calls `DSLDebugCodegenSwitch.enableInjection()` before every generator pass (and `setContent(...)` on the MAL/LAL generators), so the debug probes are compiled into the pre-compiled classes and upstream's `dsl-debugging` module runs as-is; runtime-rule stays a 501 stub. Adding any module needs a `build-tools/build-common` `AcceptedModules` entry too — the config-generator only emits config loaders for accepted providers, and a missing one fails boot with "Unknown config type".
- **Reflection metadata**: Precompiler auto-generates `reflect-config.json` by scanning Armeria HTTP handlers, GraphQL resolvers/types, config POJOs, the OAL/MAL/LAL/TAL/Hierarchy build manifests, and the proto descriptor closure of the LAL input types and of the OTLP/Tempo messages printed as JSON (protobuf `JsonFormat` reflects on generated getters). `log4j2-reflect-config.json` is manually maintained for Log4j2 plugin classes.
- **Native image**: `oap-graalvm-native` uses `native-maven-plugin` with `-Pnative` profile. Console-only `log4j2.xml` avoids RollingFile reflection chain. ~203MB binary, boots to full module init.

## Test Suites
- **MAL**: Verify pre-compiled `MalExpression` classes from build-time JAR produce identical results to fresh v2 compilation. Covers all 96 MAL YAML rule files.
- **LAL**: Verify pre-compiled `LalExpression` classes produce identical results. Covers all 12 LAL YAML files.
- **TAL**: `TALRecordedTraceComparisonTest` runs the OTLP rules over upstream's recorded OTel Demo trace, plus added messaging / database / cache calls under test server clusters, through a fresh compile and the pre-compiled classes (identical metrics, traffic, aliases, source records); `NativeTALComparisonTest` runs every `nativeTalFiles` rule over upstream's native-trace checker fixtures (copied from the submodule at build time) through `SwNativeRuleRunner`, fresh vs pre-compiled, cold and warm; `TALPrecompiledRuleTest` checks coordinates, created metrics, `requiredModules` and the listed order. They iterate every file under `tal/`; `TALPrecompileTest` (precompiler) compiles operator-style fixtures and `TALDocScriptsTest` the scripts of upstream's TAL docs and practice pages. Keep the TAL code free of shipped-rule assumptions: TAL files are user-editable.
- **Hierarchy**: Verify pre-compiled hierarchy rule classes match fresh v2 compilation.

See [docs/internals/dsl-immigration.md](docs/internals/dsl-immigration.md) for details.

## Build Commands

The native image is built with GraalVM 25.5 (`GRAALVM_VERSION` in the Makefile, `version: '25.5'` on `setup-graalvm` in CI). The Makefile picks the newest sdkman `25.5*-graal` (Oracle before `-graalce`), else a GraalVM `JAVA_HOME`, else the newest sdkman GraalVM 25, and warns when the pick is not 25.5; `make GRAAL_HOME=<dir>` overrides it. `make native-image-macos` runs `$(GRAALVM_IMAGE)` (`native-image-community:25i5`).

```bash
# Full build (precompiler + tests + server)
make build-distro

# Precompiler only
mvn -pl build-tools/precompiler install -DskipTests

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
