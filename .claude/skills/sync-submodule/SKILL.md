---
name: sync-submodule
description: Checklist and steps for syncing the skywalking submodule to a new upstream tag or commit. Covers all required checks and updates.
---

# Sync SkyWalking Submodule

Checklist for updating the `skywalking/` submodule to a new upstream tag/commit.

## 1. Update Submodule

```bash
cd skywalking && git fetch origin --tags && git checkout <tag-or-commit>
```

Verify the upstream `<revision>` in `skywalking/pom.xml` — the Makefile extracts it for `-Dskywalking.version`.

## 2. Install Upstream to Local Maven Repo

```bash
make init-skywalking
```

## 3. Analyze Changes

Compare the old and new commits to identify what changed:

```bash
cd skywalking && git log --oneline <old-commit>..<new-commit>
```

### Key areas to check for impact

| Area | What to look for |
|------|-----------------|
| **OAL engine** (`oal-rt/`) | API changes to `OALEngineV2` |
| **MAL engine** (`meter-analyzer/`) | API changes to `MALClassGenerator`, new grammar tokens |
| **LAL engine** (`log-analyzer/`) | API changes to `LALClassGenerator` |
| **New modules** | New `ModuleDefine`/`ModuleProvider` classes |
| **New OAL files** | `oal/*.oal` |
| **New MAL rules** | `otel-rules/`, `meter-analyzer-config/`, `log-mal-rules/`, `envoy-metrics-rules/`, `telegraf-rules/`, `zabbix-rules/` |
| **New LAL rules** | `lal/*.yaml` |
| **Same-FQCN files changed** | Upstream versions of files we replace in `oap-libs-for-graalvm/` |
| **New config fields** | Changes to `ModuleConfig` subclasses |
| **application.yml** | New module sections, new config properties |
| **pom.xml deps** | New module artifacts, version bumps |

## 4. Required Updates (Checklist)

### Module wiring (if new modules added)

- [ ] Add dependency to root `pom.xml` `<dependencyManagement>`
- [ ] Add dependency to `oap-graalvm-server/pom.xml` `<dependencies>`
- [ ] Register module+provider in `GraalVMOAPServerStartUp.java`
- [ ] Add `(moduleName, providerName)` pair to `AcceptedModules.java`
- [ ] Add module config section to `application.yml`

### Config handling (if new ModuleConfig classes)

- [ ] Re-run config-generator to regenerate `YamlConfigLoaderUtils.java` and `module-config-classes.txt`:
  ```bash
  ./mvnw -pl build-tools/build-common,build-tools/config-generator install -DskipTests -Dskywalking.version=<version>
  ./mvnw -pl build-tools/config-generator exec:java \
    -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt" \
    -Dskywalking.version=<version>
  ```
- [ ] If config class has no `@Setter` at class level, create same-FQCN replacement in `oap-libs-for-graalvm/` with `@Setter` added
- [ ] Add shade exclusion for replaced config class

### OAL changes (if new .oal files or scopes)

- [ ] New OAL files are auto-discovered via `OALDefine` SPI — verify in precompiler output
- [ ] Add generated metrics + builder classes to `reachability-metadata.json` in `oap-graalvm-native`
- [ ] Verify with: `jar tf build-tools/precompiler/target/precompiler-*-generated.jar | grep "oal/rt"`

### MAL/LAL changes (if new rule files)

- [ ] New MAL rules under `otel-rules/` with glob `**/*` are auto-compiled by precompiler
- [ ] New LAL rules must be added to `lalFiles` in `application.yml`
- [ ] Update `enabledOtelMetricsRules` in `application.yml` for new otel rule directories
- [ ] Create MAL comparison tests in `oap-graalvm-server/src/test/.../mal/`

### Inventory updates

- [ ] Add new provider to `provider-inventory.properties`
- [ ] Add new rule files to `rule-file-inventory.properties`

### Distribution packaging

- [ ] Add runtime config files (not DSL scripts) to `distribution.xml` and `native-distribution.xml`
- [ ] Update `docs/distro-policy.md` module table
- [ ] Update `docs/version-mapping.md`

### E2E test environment

- [ ] Update `test/e2e/script/env`:
  - `SW_UPSTREAM_COMMIT` — submodule HEAD
  - `SW_E2E_SERVICE_COMMIT` — last commit touching `test/e2e-v2/java-test-service/`
  - `SW_BANYANDB_COMMIT` — match upstream `test/e2e-v2/script/env`
  - `SW_AGENT_JAVA_COMMIT` — match upstream
- [ ] Add new e2e test cases if upstream added new features with e2e tests
- [ ] Add new test entries to `.github/workflows/ci.yml` matrix

### Same-FQCN replacement staleness

- [ ] Run `ReplacementClassStalenessTest` — if upstream changed files we replace, update replacements
- [ ] Run `PrecompiledYamlStalenessTest` — update SHA-256 hashes for changed YAML files

## 5. Build & Test

```bash
make compile          # Compile everything
make test             # Run all 1300+ tests
```

## 6. Native Image Verification

```bash
make native-image-macos   # Cross-compile for Linux (on macOS)
make docker-native        # Build Docker image
docker tag skywalking-oap-native skywalking-oap-native:latest

# Run e2e smoke test
e2e run -c test/e2e/cases/simple-java-agent/e2e.yaml
```

Check OAP logs for reflection errors:
```bash
docker logs <container> 2>&1 | grep "ERROR\|NoSuchMethodException\|ClassNotFoundException"
```

## 7. Common Pitfalls

- **Reflection errors at native image runtime**: New classes instantiated via `Class.forName().newInstance()` need entries in `reflect-config.json` or `reachability-metadata.json`
- **Config loading failures**: New `ModuleConfig` subclasses need config-generator regeneration AND may need `@Setter` same-FQCN replacement
- **Missing config files**: Runtime config files (non-DSL) must be in distribution assembly descriptors
- **OAL builder registration**: OAL-generated builder classes need constructor entries in `reachability-metadata.json`
- **Config-generator runs against upstream classpath**: Setter checks see upstream classes (no `@Setter`), not our for-graalvm replacements — the generator trusts that for-graalvm modules will provide setters