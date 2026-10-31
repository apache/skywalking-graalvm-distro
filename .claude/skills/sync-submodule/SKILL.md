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
| **TAL engine** (`trace-analyzer/`) | API changes to `TALClassGenerator`, `TalDSL.of`, `TALConfigs.load`, metric declaration fields |
| **New modules** | New `ModuleDefine`/`ModuleProvider` classes |
| **New OAL files** | `oal/*.oal` |
| **New MAL rules** | `otel-rules/`, `meter-analyzer-config/`, `log-mal-rules/`, `envoy-metrics-rules/`, `telegraf-rules/`, `zabbix-rules/` |
| **New LAL rules** | `lal/*.yaml` |
| **New TAL rules** | `tal/*.yaml` |
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

### Rule adoption policy (OAL / MAL / LAL / TAL)

**Every rule file upstream adds or changes is adopted, without exception.** The distro must
never trail upstream on bundled rules. Concretely, for each new or changed file:

- [ ] It is pre-compiled: `otel-rules/**`, `meter-analyzer-config/*`, `log-mal-rules/*`,
      `envoy-metrics-rules/*`, `telegraf-rules/*`, `zabbix-rules/*`, `lal/*`, `tal/*`, `oal/*.oal` are all
      auto-discovered by the precompiler; verify the counts in its log (`MAL: compiled N rules from <dir>`,
      `LAL pre-compilation`, `TAL pre-compilation`, `Precompiler: N metrics`).
- [ ] It is enabled by default exactly as upstream: mirror upstream `application.yml`
      (`enabledOtelMetricsRules`, `meterAnalyzerActiveFiles`, `lalFiles`, `malFiles`, ...).
- [ ] `rule-file-inventory.properties` (+ section counts) and `precompiled-yaml-sha256.properties` cover it.
- [ ] A MAL comparison test exists for every new MAL file (auto-discovery mode:
      `generateComparisonTests("<dir>/<file>.yaml")`); LAL tests for new LAL files.
- [ ] `docs/distro-policy.md` counts are refreshed.
- [ ] It is browsable: the precompiler exports raw rule files to `META-INF/rule-source/` for the
      read-only `/runtime/rule` + `/runtime/oal` catalogs — nothing to do per file, but keep
      `BundledRuleCatalogHandlerTest` expectations current if a file is renamed.

### E2E adoption

When upstream adds an e2e case for a new rule set, adopt it if it is compose-only:

- [ ] Prefer mock/replay style cases (e.g. `airflow/mock`, `banyandb`) over ones that need a real
      product cluster or build an agent from source; those go to the CI matrix only if cheap.
- [ ] Copy the case to `test/e2e/cases/<name>/` with the distro `oap` service (image
      `skywalking-oap-native:latest`, `SW_HEALTH_CHECKER/SW_STORAGE_BANYANDB_TARGETS/SW_CONFIGURATION`,
      the `nc 11800` healthcheck) and reference the upstream case files by relative path
      (`../../../../skywalking/test/e2e-v2/cases/<case>/...`) for build contexts, mounted configs and
      `verify.cases.includes` so expectations track upstream automatically.
- [ ] Add a `.github/workflows/ci.yml` e2e matrix entry.
- [ ] Not adoptable: cases that mount rule YAML at runtime (needs runtime compile), `runtime-rule/*`,
      `dsl-debugging/{mal,lal-*}` (seed rules via runtime-rule), ZooKeeper/etcd cluster cases.
      `dsl-debugging/oal` IS adopted (`test/e2e/cases/dsl-debugging-oal`): it debugs a bundled OAL rule.
- [ ] DSL debugging stays live: the precompiler calls `DSLDebugCodegenSwitch.enableInjection()` before
      generating and sets `setContent(...)` on the MAL/LAL generators; if upstream changes the probe
      codegen or `GateHolder`, re-check the `dsl-management`, `dsl-debugging` and `dsl-debugging-oal` e2e cases.

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

- **New module = three lists**: `GraalVMOAPServerStartUp` registration, `provider-inventory.properties`, AND
  `build-tools/build-common/.../AcceptedModules.java`. The config-generator discovers providers only through
  `AcceptedModules`, so a module missing there gets no `copyTo<Config>` branch in `YamlConfigLoaderUtils` and the
  OAP dies at boot with `Unknown config type` — after the JVM unit tests passed.
- **Always `./mvnw clean install` before a native build after a submodule bump**: a resumed build
  (`-rf :precompiler`, no clean) reuses the old `oap-libs-for-graalvm/*/target/*.jar`, and
  maven-shade merges that stale shaded jar with the fresh upstream jar; the stale copy wins, JVM
  unit tests still pass, and the native OAP dies at boot with `NoClassDefFoundError` on a moved class.
- **Same-name MAL counter windows**: since 11.0.0 `CounterWindow` is keyed by sample name, not
  metric name, so the comparison harness runs the fresh and pre-compiled paths back to back with a
  `CounterWindow.INSTANCE.reset()` in between.
- **Diff the public API of every same-FQCN replacement, not only the constructors**: a Lombok
  `@Getter` upstream adds a method the replacement must keep (11.0.0: `FilterExpression#getLiteral()`,
  read by `Analyzer` only inside the debug-gated filter probe). The comparison tests never take that
  branch, so the miss surfaces as `NoSuchMethodError` on the ingestion thread the moment an operator
  starts a session. `FilteredRuleDebugCaptureTest` covers the MAL filter probe; add the equivalent
  when a new probe reads a replaced class.
- **Protobuf `JsonFormat` needs reflection metadata**: it prints/parses generated messages through
  the accessor table, which looks getters up with `Class.getMethod`. The precompiler registers the
  descriptor closure of every LAL input type plus the OTLP/HTTP JSON receivers' `Export*ServiceRequest`
  and `LogData` (`protoJsonRoots` → `addProtoMessageEntries`); when upstream starts JSON-parsing or
  printing another generated type (`git grep "JsonFormat\.(parser|printer)"`), extend the root set or
  the native image answers `Generated message class ... missing method ...` (11.1: the ai-agent
  case's OTLP/JSON log posts exposed the missing OTLP roots).
- **`LALOutputBuilder` implementations are a hand list**: the precompiler resolves `outputType` short
  names through the SPI at build time, but their reflection entries sit in `Precompiler`'s
  `configPojos` list. A new builder (11.1: `ConversationFile` from `ai-agent-conversation`) goes on
  that list, and its module must be a precompiler dependency or the LAL rule fails to compile.
- **Explicit-input MAL tests go stale when upstream adds a rule to an existing file**: `OapTest`-style
  tests (hand-built `buildInput`) fail with "both paths returned EMPTY" on the new rule until its
  sample family is added to the input; auto-discovery tests pick new rules up by themselves.
- **Classpath resources must be globbed into the native image**: `oap-graalvm-native`'s
  `reachability-metadata.json` lists the resources the binary embeds. JVM unit tests never notice a
  missing one; the native OAP dies at boot (11.1: `classpath:query-protocol/gen-ai-evaluation-record.graphqls`,
  now covered by the `query-protocol/*.graphqls` glob). When upstream starts reading a new
  classpath resource (not a `config/` file), add a glob there and boot the native image.
- **Armeria instantiates handler-referenced classes reflectively**: `@ExceptionHandler`,
  `@RequestConverter` and `@Decorator` targets need constructor metadata. The precompiler's
  `scanArmeriaHandlers` collects all three (11.1 added `@Decorator` for the AI agent conversation
  view's `CompressResponse`; without it the native OAP dies at boot with "cannot inject the
  dependency for ..."). A new Armeria annotation that names a class needs the same treatment.
- **Jackson response POJOs need reflection metadata**: Armeria `HttpResponse.ofJson(pojo)` /
  `@ProducesJson` serialize through Jackson, which finds no properties in the native image without
  metadata and answers HTTP 400 ("No serializer found for class ..."). The precompiler's
  `scanQueryEntityClasses` covers `org.apache.skywalking.oap.query.*.entity.*` and
  `org.apache.skywalking.oap.server.admin.*.response.*` (the latter added in 11.1 after the storage
  suite's `admin inspect` cases exposed it); a POJO outside those packages must be registered by hand.
  The same holds for request bodies (`@ConsumesJson`) and for Gson-printed answers, which silently come out as
  `{}` instead of failing. 11.1 found three that no e2e case reached: `/status/cluster/nodes` (core `Address`,
  HTTP 500), `/status/alarm/*` (`core.alarm.provider.status`, `{}`) and the AWS Firehose receiver (`FirehoseReq`,
  HTTP 400 on every post). Audit with `git grep -n "@ProducesJson\|@ConsumesJson\|ofJson(\|GSON.toJson"` over
  the handlers (and `@RequestObject` parameters) and boot the native image against each route.
- **Stale upstream build output hides missing metadata**: `make init-skywalking` does not clean, so a resource
  upstream deleted survives in `skywalking/**/target/classes` and the installed jars, and the precompiler still
  scans it. 11.0.0 deleted `query-protocol/ui-configuration.graphqls`; a local tree kept it, so the GraphQL type
  scan kept registering `DashboardSetting` and `ui-management` passed locally while CI's clean build failed it.
  Compare the precompiler's `Scanned GraphQL types: N matched from M schema types` and `Generated reflect-config.json
  with N entries` lines with CI's, and clean-install any upstream module whose `target/classes` holds files its
  `src/main/resources` no longer has.
- **Native OAP boots faster than upstream assumes**: a case whose BanyanDB has no healthcheck
  (`service_started` only, e.g. the distroless `-plugins` image) lets the native OAP connect before
  BanyanDB listens, and it exits on `UNAVAILABLE`. Wrap the OAP entrypoint in `until nc -z ...`.
- **Upstream e2e cases that bind-mount `java-test-service` jars**: don't build them in CI; every module
  is published as `ghcr.io/apache/skywalking/<module>:${SW_E2E_SERVICE_COMMIT}` with the jar at
  `/app.jar` — run the image directly, or copy the jar out through an init container when an agent
  image must run it (`virtual-genai` spring-ai-service, `banyandb-trace-sampling` trace-mocker).

- **A DSL that creates metrics needs both `MeterSystem` replacements to track upstream's API**: the
  precompiler's build-time `MeterSystem` (`build-tools/precompiler/...`) and the runtime one
  (`server-core-for-graalvm`). 11.1 TAL calls `createMetrics` → `MeterSystem#limitLabels`, which
  neither replacement had; the build-time call fails the precompiler, the runtime one fails boot. TAL
  creates its metrics through the shared build-time `MeterSystem`, so its meter classes land in
  `tal-meter-classes.txt`, and the runtime `TalDSL` replays `create` + `limitLabels` from the manifest.
- **Retire a replacement when upstream removes the reason for it**: 11.1 `HttpAlarmCallback` moved its
  `HttpClient` from a static field to a constructor argument; keeping the old lazy-static replacement
  would have dropped the constructor every hook subclass calls. Diff each changed replacement's upstream
  source against the *reason* in its header, not only its API.
- **Stream data registered by hand needs constructor metadata**: `IWorkerInstanceSetter.put(..., Class)`
  of a non-`@Stream` class (11.1: TAL `PairingFact`) is rebuilt by `RemoteHandleWorker` with
  `newInstance()` on the receiving OAP node. The StorageBuilders scan misses it, and only a cluster
  shows the failure. Add a constructor entry in `Precompiler.generateReflectConfig`.
- **Jackson YAML/JSON DTOs outside `*.entity.*` / `*.response.*`**: the status module's `/debugging/*`
  bodies (`Debugging*Rsp`) are serialized by Jackson; the entity scan covers them since 11.1. A new
  handler that returns POJOs from another package must be added to `scanQueryEntityClasses`.
- **Upstream default switches can silently empty an e2e case**: 11.1 made native OTLP span storage the
  default, so `otlp-traces` (Zipkin API assertions) needs `SW_OTEL_TRACE_STORAGE: zipkin`.

- **Diff upstream `server-starter`'s dependencies against the distro poms on every sync**: a module that
  ships only SPI implementations, no `ModuleDefine`, is invisible to `ImmigrationScopeTest`. `ios-analyzer`
  (iOS `SpanListener`s, 11.0.0) was never bundled, and the iOS MAL/LAL rules kept the gap hidden until the
  `ios` e2e case was adopted in 11.1. Anything missing must be policy-excluded (cluster/configuration/storage
  plugins, `runtime-rule`) or added.
- **TAL rules are user-editable**: keep the precompiler pass and the `TalDSL` / `TALConfigs` replacements free of
  shipped-rule assumptions. `TALPrecompileTest` (fixtures) and `TALDocScriptsTest` (upstream's documented scripts,
  extracted from the docs and spliced where the practices page says) guard that; when upstream's docs move a
  snippet or the shipped rule loses an anchor line, the test names it.

- **Reflection errors at native image runtime**: New classes instantiated via `Class.forName().newInstance()` need entries in `reflect-config.json` or `reachability-metadata.json`
- **Config loading failures**: New `ModuleConfig` subclasses need config-generator regeneration AND may need `@Setter` same-FQCN replacement
- **Missing config files**: Runtime config files (non-DSL) must be in distribution assembly descriptors
- **OAL builder registration**: OAL-generated builder classes need constructor entries in `reachability-metadata.json`
- **Config-generator runs against upstream classpath**: Setter checks see upstream classes (no `@Setter`), not our for-graalvm replacements — the generator trusts that for-graalvm modules will provide setters