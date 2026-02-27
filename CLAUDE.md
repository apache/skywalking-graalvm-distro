# SkyWalking GraalVM Distro - Development Guide

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `build-tools/precompiler/` — Build-time precompiler: runs OAL + MAL + LAL engines at Maven compile time, exports `.class` files, manifests, and auto-generated `reflect-config.json` into `precompiler-*-generated.jar`.
- `build-tools/config-generator/` — Build-time config code generator: scans `ModuleConfig` subclasses and generates `YamlConfigLoaderUtils` replacement.
- `oap-libs-for-graalvm/` — Per-JAR repackaged modules using `maven-shade-plugin` for same-FQCN class replacements.
- `oap-graalvm-server/` — GraalVM-ready OAP server module (JVM distro) with same-FQCN replacement classes and comprehensive test suites.
- `oap-graalvm-native/` — Native image module: `native-maven-plugin` configuration, native-specific `log4j2.xml`, `log4j2-reflect-config.json`, and native distribution assembly.
- `docker/` — `Dockerfile.native` (runtime image) and `docker-compose.yml` (BanyanDB + OAP native).
- `docs/` — Documentation: distro-policy, configuration, OAL/MAL/LAL immigration, config initialization.
- Root-level Maven + Makefile — Orchestrates building on top of the submodule.

## Key Principles
1. **Minimize upstream changes.** SkyWalking is a submodule. Changes to it require separate upstream PRs and syncing back.
2. **Build-time class export.** All runtime code generation (OAL via Javassist, MAL/LAL via Groovy) runs at build time. Export `.class` files into native-image classpath.
3. **Fixed module wiring.** Module/provider selection is hardcoded in this distro — no SPI discovery. See docs/distro-policy.md for the full module table.
4. **JDK 25.** Already compiles and runs.

## Technical Notes
- **OAL engine**: Generates metrics classes via Javassist at startup. For native image, run OAL engine at build time, export `.class` files. OAL uses ANTLR4 + FreeMarker + Javassist (not Groovy).
- **MAL transpiler**: Transpiles all 71 MAL YAML rule files (1250+ expressions) from Groovy AST to pure Java `MalExpression` classes at build time. Zero Groovy at runtime. Produces `META-INF/mal-expressions.txt` and `META-INF/mal-groovy-expression-hashes.txt` (SHA-256 hashes for combination pattern resolution).
- **LAL transpiler**: Transpiles 10 LAL scripts (6 unique) from Groovy AST to pure Java `LalExpression` classes. SHA-256 deduplication for identical DSL bodies.
- **Combination pattern**: Multiple YAML files from different data sources (otel, telegraf, zabbix) may define metrics with the same name. The precompiler assigns deterministic suffixes (`_1`, `_2`, etc.) and tracks expression hashes for unambiguous resolution.
- **Same-FQCN replacement**: Classes in `oap-libs-for-graalvm/*/src/main/java/` with the same fully-qualified class name as upstream classes are repackaged via `maven-shade-plugin` (original `.class` excluded). Used for `DSL.java`, `SampleFamily.java`, `MeterSystem.java`, etc.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` replaced with same-FQCN version that uses Lombok setters instead of `Field.setAccessible()`. See [docs/config-init-immigration.md](docs/config-init-immigration.md).
- **Reflection metadata**: Precompiler auto-generates `reflect-config.json` by scanning Armeria HTTP handlers, GraphQL resolvers/types, config POJOs, and OAL/MAL/LAL manifests. `log4j2-reflect-config.json` is manually maintained for Log4j2 plugin classes.
- **Native image**: `oap-graalvm-native` uses `native-maven-plugin` with `-Pnative` profile. Console-only `log4j2.xml` avoids RollingFile reflection chain. ~203MB binary, boots to full module init.

## Test Suites
- **MAL**: 71 YAML files covered by 73 test classes (1,281 assertions). See `oap-graalvm-server/src/test/CLAUDE.md` for test generation instructions and `oap-graalvm-server/src/test/MAL-COVERAGE.md` for coverage tracking.
- **LAL**: 8 YAML files covered by 5 test classes (19 assertions). See [docs/lal-immigration.md](docs/lal-immigration.md) for details.

Both suites use dual-path comparison: Path A (fresh GroovyShell compilation) vs Path B (pre-compiled class from build-time JAR). Both paths must produce identical results.

## Build Commands
```bash
# Full build (precompiler + tests + server)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro

# Precompiler only
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal mvn -pl build-tools/precompiler install -DskipTests

# Run MAL tests only
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal mvn -pl oap-graalvm-server test

# Native image (requires GraalVM with native-image)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make native-image

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