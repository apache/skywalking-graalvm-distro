# SkyWalking GraalVM Distro - Development Guide

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `build-tools/precompiler/` — Build-time precompiler: runs OAL + MAL + LAL + Hierarchy v2 engines at Maven compile time, exports `.class` files, manifests, and auto-generated `reflect-config.json` into `precompiler-*-generated.jar`.
- `build-tools/config-generator/` — Build-time config code generator: scans `ModuleConfig` subclasses and generates `YamlConfigLoaderUtils` replacement.
- `oap-libs-for-graalvm/` — Per-JAR repackaged modules using `maven-shade-plugin` for same-FQCN class replacements.
- `oap-graalvm-server/` — GraalVM-ready OAP server module (JVM distro) with same-FQCN replacement classes and comprehensive test suites.
- `oap-graalvm-native/` — Native image module: `native-maven-plugin` configuration, native-specific `log4j2.xml`, `log4j2-reflect-config.json`, and native distribution assembly.
- `docker/` — `Dockerfile.native` (runtime image) and `docker-compose.yml` (BanyanDB + OAP native).
- `docs/` — Documentation: distro-policy, configuration, DSL pre-compilation (OAL/MAL/LAL/Hierarchy), config initialization.
- Root-level Maven + Makefile — Orchestrates building on top of the submodule.

## Key Principles
1. **Minimize upstream changes.** SkyWalking is a submodule. Changes to it require separate upstream PRs and syncing back.
2. **Build-time class export.** All runtime code generation (OAL/MAL/LAL/Hierarchy via ANTLR4 + Javassist) runs at build time. Export `.class` files into native-image classpath.
3. **Fixed module wiring.** Module/provider selection is hardcoded in this distro — no SPI discovery. See docs/distro-policy.md for the full module table.
4. **JDK 25.** Already compiles and runs.

## Technical Notes
- **V2 DSL engines**: All four DSL compilers (OAL, MAL, LAL, Hierarchy) use the same pipeline: ANTLR4 parse → immutable AST → Javassist bytecode. Upstream PR #13723 removed Groovy from all production code. The precompiler runs these v2 engines at build time via `setClassOutputDir()` / `setClassNameHint()`, capturing `.class` files into the output JAR.
- **OAL engine**: Generates ~1285 metrics/builder/dispatcher classes via Javassist at startup. Run at build time via `OALEngineV2.start()` with `setGeneratedFilePath()`. Uses ANTLR4 + FreeMarker + Javassist.
- **MAL compiler**: Compiles ~1250 MAL expressions from 71 YAML rule files via `MALClassGenerator`. Each expression becomes a `MalExpression` implementation class. Deterministic class naming via `setClassNameHint(yamlSource + metricName)`. Closures (TagFunction, ForEachFunction, PropertiesExtractor, DecorateFunction) use `LambdaMetafactory` wiring (no separate .class files). **Per-file manifests**: `META-INF/mal-v2/` mirrors original YAML directory structure; each config file contains rule names, expressions, filter, and compiled class FQCNs. Runtime lookup by expression text.
- **LAL compiler**: Compiles ~10 LAL scripts from 8 YAML files via `LALClassGenerator`. Each script becomes a `LalExpression` implementation class. Deterministic class naming via `setClassNameHint(yamlSource + ruleName)`.
- **Hierarchy compiler**: Compiles ~4 hierarchy matching rules via `HierarchyRuleClassGenerator`. Each rule becomes a `BiFunction<Service, Service, Boolean>` implementation class.
- **MeterSystem**: `MeterSystem.create()` uses Javassist to generate ~1188 meter function subclasses. Run at build time, export `.class` files. Separate from MAL DSL compilation.
- **Same-FQCN replacement**: Classes in `oap-libs-for-graalvm/*/src/main/java/` with the same fully-qualified class name as upstream classes are repackaged via `maven-shade-plugin` (original `.class` excluded). Used for v2 `DSL.java` (MAL/LAL), `MeterSystem.java`, `CompiledHierarchyRuleProvider.java`, etc.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` replaced with same-FQCN version that uses Lombok setters instead of `Field.setAccessible()`. See [docs/config-init-immigration.md](docs/config-init-immigration.md).
- **Reflection metadata**: Precompiler auto-generates `reflect-config.json` by scanning Armeria HTTP handlers, GraphQL resolvers/types, config POJOs, and OAL/MAL/LAL/Hierarchy manifests. `log4j2-reflect-config.json` is manually maintained for Log4j2 plugin classes.
- **Native image**: `oap-graalvm-native` uses `native-maven-plugin` with `-Pnative` profile. Console-only `log4j2.xml` avoids RollingFile reflection chain. ~203MB binary, boots to full module init.

## Test Suites
- **MAL**: Verify pre-compiled `MalExpression` classes from build-time JAR produce identical results to fresh v2 compilation. Covers all 71 MAL YAML rule files.
- **LAL**: Verify pre-compiled `LalExpression` classes produce identical results. Covers all 8 LAL YAML files.
- **Hierarchy**: Verify pre-compiled hierarchy rule classes match fresh v2 compilation.

See [docs/dsl-immigration.md](docs/dsl-immigration.md) for details.

## Build Commands

The Makefile auto-detects GraalVM 25 JDK via sdkman (`~/.sdkman/candidates/java/*graal*25*`). If `JAVA_HOME` already points to a GraalVM installation, it is used as-is.

```bash
# Full build (precompiler + tests + server)
make build-distro

# Precompiler only
mvn -pl build-tools/precompiler install -DskipTests

# Run MAL tests only
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

## Selected Modules
- **Storage**: BanyanDB
- **Cluster**: Standalone, Kubernetes
- **Configuration**: Kubernetes
- **Receivers/Query/Analyzers/Alarm/Telemetry/Other**: Full feature set (see docs/distro-policy.md for details)