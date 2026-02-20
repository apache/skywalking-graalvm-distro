# SkyWalking GraalVM Distro - Development Guide

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `build-tools/precompiler/` — Build-time precompiler: runs OAL + MAL + LAL engines at Maven compile time, exports `.class` files and manifests into `precompiler-*-generated.jar`.
- `oap-graalvm-server/` — GraalVM-ready OAP server module with same-FQCN replacement classes and comprehensive test suites.
- `DISTRO-POLICY.md` — Current build plan (draft/temporary, subject to change).
- Root-level Maven + Makefile — Orchestrates building on top of the submodule.

## Key Principles
1. **Minimize upstream changes.** SkyWalking is a submodule. Changes to it require separate upstream PRs and syncing back.
2. **Build-time class export.** All runtime code generation (OAL via Javassist, MAL/LAL via Groovy) runs at build time. Export `.class` files into native-image classpath.
3. **Fixed module wiring.** Module/provider selection is hardcoded in this distro — no SPI discovery. See DISTRO-POLICY.md for the full module table.
4. **JDK 25.** Already compiles and runs.

## Technical Notes
- **OAL engine**: Generates metrics classes via Javassist at startup. For native image, run OAL engine at build time, export `.class` files. OAL uses ANTLR4 + FreeMarker + Javassist (not Groovy).
- **MAL precompiler**: Compiles all 71 MAL YAML rule files (meter-analyzer-config, otel-rules, log-mal-rules, envoy-metrics-rules, telegraf-rules, zabbix-rules) into 1250 Groovy scripts at build time. Produces `META-INF/mal-groovy-manifest.txt` (script→class mapping) and `META-INF/mal-groovy-expression-hashes.txt` (expression SHA-256 hashes for combination pattern resolution).
- **Combination pattern**: Multiple YAML files from different data sources (otel, telegraf, zabbix) may define metrics with the same name. The precompiler assigns deterministic suffixes (`_1`, `_2`, etc.) and tracks expression hashes for unambiguous resolution.
- **Same-FQCN replacement**: Classes in `oap-graalvm-server/src/main/java/` with the same fully-qualified class name as upstream classes override them via Maven classpath ordering. Used for `DSL.java`, `LogFilterExpression.java`, etc.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` replaced with same-FQCN version that uses Lombok setters + VarHandle instead of `Field.setAccessible()`. See [CONFIG-INIT-IMMIGRATION.md](CONFIG-INIT-IMMIGRATION.md).

## Test Suites
- **MAL**: 71 YAML files covered by 73 test classes (1,281 assertions). See `oap-graalvm-server/src/test/CLAUDE.md` for test generation instructions and `oap-graalvm-server/src/test/MAL-COVERAGE.md` for coverage tracking.
- **LAL**: 8 YAML files covered by 5 test classes (19 assertions). See [LAL-IMMIGRATION.md](LAL-IMMIGRATION.md) for details.

Both suites use dual-path comparison: Path A (fresh GroovyShell compilation) vs Path B (pre-compiled class from build-time JAR). Both paths must produce identical results.

## Build Commands
```bash
# Full build (precompiler + tests + server)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro

# Precompiler only
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal mvn -pl build-tools/precompiler install -DskipTests

# Run MAL tests only
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal mvn -pl oap-graalvm-server test
```

## Selected Modules
- **Storage**: BanyanDB
- **Cluster**: Standalone, Kubernetes
- **Configuration**: Kubernetes
- **Receivers/Query/Analyzers/Alarm/Telemetry/Other**: Full feature set (see DISTRO-POLICY.md for details)