# SkyWalking GraalVM Distro - Development Guide

## Project Structure
- `skywalking/` — Git submodule of `apache/skywalking.git`. **Do not modify directly.** All SkyWalking source changes go through upstream PRs.
- `PLAN.md` — Current build plan (draft/temporary, subject to change).
- Root-level Maven + Makefile — Orchestrates building on top of the submodule.

## Key Principles
1. **Minimize upstream changes.** SkyWalking is a submodule. Changes to it require separate upstream PRs and syncing back.
2. **Build-time class export.** All runtime code generation (OAL via Javassist, MAL/LAL via Groovy) runs at build time. Export `.class` files into native-image classpath.
3. **Fixed module wiring.** Module/provider selection is hardcoded in this distro — no SPI discovery. See PLAN.md for the full module table.
4. **JDK 25.** Already compiles and runs.

## Technical Notes
- **OAL engine**: Generates metrics classes via Javassist at startup. For native image, run OAL engine at build time, export `.class` files.
- **MAL/LAL**: Use Groovy `GroovyShell` for runtime script evaluation. For native image, use Groovy static compilation (`@CompileStatic`), export `.class` files.
- **OAL has never relied on Groovy.** OAL uses ANTLR4 + FreeMarker + Javassist.
- **Classpath scanning**: Guava `ClassPath.from()` used in multiple places. Run at build-time pre-compilation as verification gate, export static class index.
- **Config loading**: `YamlConfigLoaderUtils.copyProperties()` uses `Field.setAccessible()` + `field.set()`. Register known `ModuleConfig` classes for GraalVM reflection.

## Selected Modules
- **Storage**: BanyanDB
- **Cluster**: Kubernetes
- **Configuration**: Kubernetes
- **Receivers/Query/Analyzers/Alarm/Telemetry/Other**: Full feature set (see PLAN.md for details)