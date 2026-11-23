---
name: sync-submodule
description: Checklist and steps for syncing the skywalking submodule to a new upstream tag or commit. Runs upstream's graalvm-distro-impact checks over the range, collects each upstream PR's GraalVM distro impact item, and covers all required checks and updates, including keeping upstream's skill in step with the distro.
---

# Sync SkyWalking Submodule

Checklist for updating the `skywalking/` submodule to a new upstream tag/commit.

## 1. Update Submodule

```bash
cd skywalking && git fetch origin --tags && git checkout <tag-or-commit> && git submodule update --init --recursive
```

The nested `query-protocol` submodule (GraphQL schema) moves with the pin. Verify the upstream `<revision>` in
`skywalking/pom.xml` — the Makefile extracts it for `-Dskywalking.version`.

## 2. Install Upstream to Local Maven Repo

```bash
make init-skywalking
```

## 3. Analyze Changes

Compare the old and new commits to identify what changed:

```bash
cd skywalking && git log --oneline <old-commit>..<new-commit>
```

### Start from upstream's `graalvm-distro-impact` skill

Upstream keeps `skywalking/.claude/skills/graalvm-distro-impact/SKILL.md` (since apache/skywalking#14256): what the
distro depends on (the build-time API the precompiler calls, the replaced classes, what the native-image metadata
covers by itself), the native-image concerns of any class, and a "Deciding the impact" table mapping each kind of
change to distro work. Every upstream PR fills the template's "GraalVM distro impact" item from it. A sync is the
other end: read the skill as of the new pin (it changes with the pin), then run its review checks over the whole range:

```bash
.claude/skills/sync-submodule/impact-scan.sh <old-commit> <new-commit>
```

The script runs the checks of the skill's "When reviewing a change" against this repo's working tree (in place of a
clone's `origin/main`): replaced classes the range edits, deleted or renamed classes the distro still names, DSL
engine / `server-starter` / assembly files, module, provider and config classes, and native-image hazards on added
lines (the pattern is read from the skill at the new pin, so it tracks upstream). Last it prints the checked box of
each PR's impact item.

- **The PR items are hints.** PRs before #14256 have none, and an item can be wrong. An "Impacted" item names what to
  adapt; start there, but the scan and the diff decide.
- **Each hit is a question.** Judge it with the skill's "Deciding the impact" (which row applies) and "Native-image
  concerns in any class" (what a hazard needs). Section 4 below is the distro side of those rows.
- **The scan is heuristic.** It misses indirect inheritance, nested config classes and multi-line spellings; still
  read the range's diff for the areas in the table below.
- **For each replaced class the range edits,** diff the upstream change into the copy, and after `make compile` (or
  `make test`) run the skill's `javap` member check ("Checking a branch against the distro"), which lists upstream
  members the copy lacks (a caller of a missing one fails with `NoSuchMethodError` only at run time):

  ```bash
  members() { local out; out=$(javap -p -cp "$1" "$2") || return 1
              printf '%s\n' "$out" | grep -E '^\s' | grep -v -E '^\s*private |static \{\}' | sort -u; }
  C=<FQCN>
  U=$(members skywalking/oap-server/<upstream-module-path>/target/<upstream-artifact>-<upstream-version>.jar "$C") &&
  D=$(members oap-libs-for-graalvm/<distro-module>/target/classes "$C") &&
  comm -23 <(printf '%s\n' "$U") <(printf '%s\n' "$D")
  ```

  The two module names differ (e.g. `otel-receiver-plugin` and `otel-receiver-for-graalvm`); a `javap` error
  stops the check instead of comparing against nothing.

  Some members are dropped on purpose (run-time Javassist and runtime-rule members such as
  `MeterSystem#removeMetric`). For the four replacements in `oap-graalvm-server`, use its `target/classes`. MAL
  `DSL`, `FilterExpression` and `MeterSystem` also have build-time copies the precompiler compiles with: check
  `build-tools/precompiler/target/classes` too, or a build-time caller still meets `NoSuchMethodError`.

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
- [ ] Add dependency to `oap-graalvm-server/pom.xml` `<dependencies>` (the boot finds it through the module SPI, as
      upstream; nothing to register in code)
- [ ] Add it to `build-tools/precompiler/pom.xml` when it holds what the precompiler scans or compiles with (Armeria
      handlers, GraphQL resolvers, JSON DTOs, LAL/TAL SPIs); `LalRuntimeTypeParityTest` fails when a LAL SPI is only on
      the server side
- [ ] Add it to `build-tools/config-generator/pom.xml` when `server-starter` does not pull it in (as the admin modules),
      or its config class gets no loader branch
- [ ] Add `(moduleName, providerName)` pair to `AcceptedModules.java`
- [ ] Add module config section to `application.yml` (`ApplicationYmlModulesTest` requires every module on the
      classpath to have one, listing exactly its providers)

### Config handling (if new ModuleConfig classes)

- [ ] Re-run config-generator to regenerate `YamlConfigLoaderUtils.java`, `module-config-classes.txt` and, next to
      it, `module-config-field-set.txt` (the final and static fields the loader writes through `Field.set`, as upstream,
      which the precompiler registers for the native image):
  ```bash
  ./mvnw -pl build-tools/build-common,build-tools/config-generator install -DskipTests -Dskywalking.version=<version>
  ./mvnw -pl build-tools/config-generator exec:java \
    -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt" \
    -Dskywalking.version=<version>
  ```
- [ ] If config class has no `@Setter` at class level, create same-FQCN replacement in `oap-libs-for-graalvm/` with `@Setter` added
- [ ] Add shade exclusion for replaced config class

### OAL changes (if new .oal files or scopes)

- [ ] A new OAL file compiles through its `OALDefine` (`INSTANCE`), found on the precompiler's class path: a staged `oal/*.oal` no define names fails the build, so add the module declaring the define to `build-tools/precompiler/pom.xml`
- [ ] Nothing to register by hand: the precompiler generates the constructor entries of the metrics, builder and dispatcher classes into `reflect-config.json`
- [ ] Verify with: `jar tf build-tools/precompiler/target/precompiler-*-generated.jar | grep "oal/rt"`

### Rule adoption policy (OAL / MAL / LAL / TAL)

**Every rule file upstream adds or changes is adopted, without exception.** The distro must
never trail upstream on bundled rules. Concretely, for each new or changed file:

- [ ] It is pre-compiled: `otel-rules/**`, `meter-analyzer-config/*`, `log-mal-rules/*`,
      `envoy-metrics-rules/*`, `telegraf-rules/*`, `zabbix-rules/*`, `lal/*`, `tal/*`, `oal/*.oal` are all
      auto-discovered by the precompiler; verify the counts in its log (`MAL: compiled N rules from <dir>`,
      `LAL pre-compilation`, `TAL pre-compilation`, `Precompiler: N metrics`).
- [ ] It is active: every compiled rule file is. The rule-list settings (`enabledOtelMetricsRules`,
      `meterAnalyzerActiveFiles`, `lalFiles`, `malFiles`, `talFiles`, `nativeTalFiles`, ...) are NOT in the distro
      `application.yml` (setting one fails the boot); check the new file is in `CompiledRules.activation()`
      (`CompiledRuleActivationTest`, and the boot log line `Every compiled rule file is active`). A new
      `nativeTalFiles` entry upstream lists keeps its place: the registry orders native files as upstream's
      `application.yml` lists them.
- [ ] `rule-file-inventory.properties` (+ section counts) and `precompiled-yaml-sha256.properties` cover it.
- [ ] A MAL comparison test exists for every new MAL file (auto-discovery mode:
      `generateComparisonTests("<dir>/<file>.yaml")`); LAL tests for new LAL files.
- [ ] `docs/distro-policy.md` counts are refreshed.
- [ ] It is browsable when its catalog is one the read-only `/runtime/rule` + `/runtime/oal` endpoints serve
      (`otel-rules`, `log-mal-rules`, `telegraf-rules`, `meter-analyzer-config`, `lal`, `oal`; not
      `envoy-metrics-rules`, `zabbix-rules` or `tal`): the precompiler exports the raw rule files to
      `META-INF/rule-source/` — nothing to do per file, but keep `BundledRuleCatalogHandlerTest` expectations current
      if a file is renamed.

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
- [ ] Nothing to enable: new LAL and otel rule files are active once compiled (no `lalFiles` or
      `enabledOtelMetricsRules` in the distro `application.yml`)
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
  - `SW_UPSTREAM_COMMIT` — NOT the submodule HEAD: the UI image pin for istio-als, kept at the last commit whose
    `ghcr.io/apache/skywalking/ui` image exists (11.0.0 dropped the bundled UI); see the comment in the env file
  - `SW_E2E_SERVICE_COMMIT` — last commit touching `test/e2e-v2/java-test-service/`
  - `SW_BANYANDB_COMMIT` — match upstream `test/e2e-v2/script/env`
  - `SW_AGENT_JAVA_COMMIT` — match upstream
- [ ] Add new e2e test cases if upstream added new features with e2e tests
- [ ] Add new test entries to `.github/workflows/ci.yml` matrix

### Same-FQCN replacement staleness

- [ ] Run `ReplacementClassStalenessTest` — if upstream changed files we replace, update replacements, then run the
      `javap` member check (section 3) on each
- [ ] Run `PrecompiledYamlStalenessTest` — update SHA-256 hashes for changed YAML files

### Guards against a silent drop

The distro runs upstream's compilers, but it re-implements what happens around them: how a compiled class is found,
built and wired at boot, how rule data reaches the registry, how config is loaded. Each of these fails the build or
the boot when upstream changes something the re-implementation does not handle, instead of running without it:

| Guard | Fails when |
|---|---|
| `GeneratedInstanceCheck` (precompiler, every MAL / LAL / TAL / Hierarchy compile) | A generator sets state on the instance it returns after constructing it (deep, by `StateComparison`), outside TAL's recorded function fields; the runtime builds classes by their no-arg constructor |
| `DSL.of` (LAL) and `TalDSL.of` | No pre-compiled class matches the rule's coordinates, name, DSL text and input/output types upstream passes at boot |
| `LalRuntimeTypeParityTest` | Upstream's type resolution, on the server classpath, gives a compiled LAL rule other input/output types than the precompiler did (an SPI only one side ships), or a rule is compiled though upstream's boot skips it, or skipped though the boot runs it (a layer only one side registers; `UpstreamLalBoot` holds upstream's predicates) |
| `BuildIntermediates.writeConfigData` + `RegistryGenerator.requireCarried` | Rule data (`Rule`, `LALConfig`, `TALConfig`, ...) loses state on its way to the registry: through the JSON intermediate, or because no emitted getter/setter pair carries it |
| `TalMetrics` comparison (precompiler) | Upstream `createMetrics` makes other `MeterSystem` calls than the boot replays from the recorded declarations |
| `replacement-source-sha256.properties` | A replaced class changed, or `OALEngineV2` / `OALClassGeneratorV2`, whose registration steps (metrics then dispatchers; the disables' `isStreamRunning` guard) the OAL loader replacement replays |
| `GeneratedConfigLoaderIsCurrentTest` (config-generator) | The committed `YamlConfigLoaderUtils` is not what the generator writes for the current config classes |
| `ImmigrationScopeTest#everyUpstreamStarterResourceIsAccountedFor` | Upstream's server-starter resources gain an entry the distro neither compiles nor packages (a new rule kind) |

A guard that fires is the sync's to-do list, not a test to relax.

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

## 7. Keep Upstream's Skill in Step with the Distro

Upstream's `graalvm-distro-impact` skill describes the distro as a snapshot (its "distro `main` <sha>, <date>"
line): the replaced classes and their counts, the build-time API the precompiler calls, what the metadata covers by
itself, the native build arguments, the module set, and the files of upstream the distro's tests, e2e cases and
assemblies read. Upstream developers judge every PR with it, so a stale fact misleads each PR after it. When a sync,
or any distro PR, changes one of these, collect it for an upstream PR that updates the skill (an ordinary
apache/skywalking PR, batched with other pending upstream fixes; the submodule is never patched):

- a replacement added or retired, or the reason for one changed;
- a new precompiler scan, protobuf root or hand-kept metadata entry, especially for a pattern upstream code could avoid;
- a new build-time API call, private field reached by reflection, or state the distro replays after `newInstance()`;
- a module accepted or dropped, or a native build argument changed (`oap-graalvm-native/pom.xml`);
- an upstream file the distro's tests, e2e cases or assemblies newly read;
- a new Common Pitfall that upstream code could have avoided: a candidate for the skill's "When changing code".

Check the skill's replaced-class table and counts ("The replaced classes") against the distro's same-FQCN files:

```bash
for d in oap-libs-for-graalvm oap-graalvm-server build-tools/precompiler; do
  git ls-files "$d" | sed -n 's#.*/src/main/java/##p' \
    | grep -xF -f <(git -C skywalking ls-files '*/src/main/java/*.java' | sed 's#.*/src/main/java/##' | sort -u) \
    | sed "s#^#$d #"
done
```

Upstream's skill points to this file ("its sync checklist and Common Pitfalls") for what broke the distro on past
syncs: keep the path and the "Common Pitfalls" heading.

## 8. Common Pitfalls

- **New module = three places, at least** (the full list is §4 "Module wiring", incl. the precompiler and
  config-generator poms): the `oap-graalvm-server` dependency plus its `application.yml` section,
  `provider-inventory.properties`, AND `build-tools/build-common/.../AcceptedModules.java`. The config-generator
  discovers providers only through `AcceptedModules`, so a module missing there would get no `copyTo<Config>` branch
  in `YamlConfigLoaderUtils` and the OAP would die at boot with `Unknown config type`;
  `ApplicationYmlModulesTest` fails first, as it requires `AcceptedModules` to equal the inventory's ACCEPTED
  providers, and the generator fails on an accepted provider missing from its classpath (its pom).
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
  and `LogData` (`NativeImageMetadata`: `protoJsonRoots` → `addProtoMessageEntries`); when upstream starts
  JSON-parsing or printing another generated type (`git grep "JsonFormat\.(parser|printer)"`), extend the root set or
  the native image answers `Generated message class ... missing method ...` (11.1: the ai-agent
  case's OTLP/JSON log posts exposed the missing OTLP roots).
- **A new `LALOutputBuilder` or `LALSourceTypeProvider` module must be a precompiler dependency**:
  the LAL pass resolves `outputType` short names and layer types through upstream's
  `LogFilterListener.Factory` over the SPIs on its classpath, and records the implementations it finds
  in `lal-io-classes.txt` for reflection; a builder whose module is missing there (11.1:
  `ConversationFile` from `ai-agent-conversation`) fails the LAL rule's compile.
- **Explicit-input MAL tests go stale when upstream adds a rule to an existing file**: `OapTest`-style
  tests (hand-built `buildInput`) fail with "both paths returned EMPTY" on the new rule until its
  sample family is added to the input; auto-discovery tests pick new rules up by themselves.
- **Classpath resources must be globbed into the native image**: `oap-graalvm-native`'s
  `reachability-metadata.json` lists the resources the binary embeds. JVM unit tests never notice a
  missing one; the native OAP dies at boot (11.1: `classpath:query-protocol/gen-ai-evaluation-record.graphqls`,
  now covered by the `query-protocol/*.graphqls` glob). When upstream starts reading a new
  classpath resource (not a `config/` file), add a glob there and boot the native image.
- **Armeria instantiates handler-referenced classes reflectively**: `@ExceptionHandler`,
  `@RequestConverter`, `@ResponseConverter` and `@Decorator` targets need constructor metadata. The precompiler's
  `ClasspathScans.scanArmeriaHandlers` collects the classes those annotations name and every `ExceptionHandlerFunction`
  implementation (11.1 added `@Decorator` for the AI agent conversation view's `CompressResponse`; without it the
  native OAP dies at boot with "cannot inject the dependency for ..."). A new Armeria annotation that names a class
  needs the same treatment.
- **Jackson response POJOs need reflection metadata**: Armeria `HttpResponse.ofJson(pojo)` /
  `@ProducesJson` serialize through Jackson, which finds no properties in the native image without
  metadata and answers HTTP 400 ("No serializer found for class ..."). The precompiler's
  `ClasspathScans.scanQueryEntityClasses` covers `org.apache.skywalking.oap.query.*.entity.*`,
  `org.apache.skywalking.oap.server.admin.*.response.*` (added in 11.1 after the storage suite's `admin inspect` cases
  exposed it), the status module's `Debugging*Rsp` and
  `org.apache.skywalking.oap.server.core.alarm.provider.status.*`, and `scanHttpBodyTypes` the bodies a route's
  signature names (its parameters and return type, and the classes their fields hold; a protobuf message gets its
  descriptor closure); a POJO outside those packages that a route builds itself (`HttpResponse.of(...)` of an object)
  must be registered by hand, on `NativeImageMetadata`'s `configPojos` list.
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
  `tal-meter-classes.txt`, and the runtime `TalDSL` replays `create` + `limitLabels` from the rule registry.
- **The rule registry is generated from upstream's rule data classes**: `RegistryGenerator` writes each rule
  file's `Rule` / `LALConfigs` / `TALConfigs` / `ZabbixConfig` / `LayerDefinition` as setter calls, compiled
  against upstream's classes but run against the same-FQCN `LALConfigs` / `TALConfigs` replacements. When upstream
  adds a property to one of them, add it to the replacement too, or the generated code fails with
  `NoSuchMethodError` the first time the registry loads. `RegistryParityTest` (precompiler) fails when the
  registry no longer matches what upstream's loaders read; a new rule-data file upstream reads at runtime needs a
  replacement reading the registry, or it is silently ignored (the boot check `CompiledRulesCheck` only rejects
  rule files the operator adds).
- **Retire a replacement when upstream removes the reason for it**: 11.1 `HttpAlarmCallback` moved its
  `HttpClient` from a static field to a constructor argument; keeping the old lazy-static replacement
  would have dropped the constructor every hook subclass calls. Diff each changed replacement's upstream
  source against the *reason* in its header, not only its API.
- **Stream data registered by hand needs constructor metadata**: `IWorkerInstanceSetter.put(..., Class)`
  of a non-`@Stream` class (11.1: TAL `PairingFact`) is rebuilt by `RemoteHandleWorker` with
  `newInstance()` on the receiving OAP node, and only a cluster shows a missing entry. The precompiler's
  `StreamData` scan registers the constructor of every concrete `StreamData` class on its class path.
- **Jackson YAML/JSON DTOs outside `*.entity.*` / `*.response.*`**: the status module's `/debugging/*`
  bodies (`Debugging*Rsp`) are serialized by Jackson; the entity scan covers them since 11.1. A new
  handler that returns POJOs from another package must be added to `scanQueryEntityClasses`.
- **Upstream default switches can silently empty an e2e case**: 11.1 made native OTLP span storage the
  default, so `otlp-traces` (Zipkin API assertions) needs `SW_OTEL_TRACE_STORAGE: zipkin`.

- **Diff upstream `server-starter`'s dependencies against the distro poms on every sync**: a module that
  ships only SPI implementations, no `ModuleDefine`, is invisible to `ImmigrationScopeTest`. `ios-analyzer`
  (iOS `SpanListener`s, 11.0.0) was never bundled, and the iOS MAL/LAL rules kept the gap hidden until the
  `ios` e2e case was adopted in 11.1. Anything missing must be policy-excluded (cluster/configuration/storage
  plugins, `runtime-rule`) or added. `server-starter-for-graalvm` excludes all of `server-starter`'s dependencies,
  so a policy-excluded plugin stays off the classpath, where the module SPI would load it.
- **Generated TAL classes hold function objects** (since SWIP-17, 11.2): upstream's `TALClassGenerator.compile` sets
  private `_fnN` fields from a `TALFunctionRegistry` after `newInstance()`, so a pre-compiled class loaded by name has
  them null and every rule NPEs on its first function call. The precompiler records each field's registry key (the
  owner's class name, or `class/alias` for the second object of a class, so the key depends on the ServiceLoader order
  of `TALSourceTypeProvider`) and `TalDSL.of` sets the objects from the factory's registry. When upstream adds a
  `TALFunctionProvider` module, add it to the precompiler's dependencies or the rules calling it fail to compile.
  `NativeTALComparisonTest` and `TALRecordedTraceComparisonTest` catch a broken wiring.
- **TAL rules are user-editable**: keep the precompiler pass and the `TalDSL` / `TALConfigs` replacements free of
  shipped-rule assumptions. `TALPrecompileTest` (fixtures) and `TALDocScriptsTest` (upstream's documented scripts,
  extracted from the docs and spliced where the practices page says) guard that; when upstream's docs move a
  snippet or the shipped rule loses an anchor line, the test names it.

- **Reflection errors at native image runtime**: New classes instantiated via `Class.forName().newInstance()` need entries in `reflect-config.json` or `reachability-metadata.json`
- **Config loading failures**: New `ModuleConfig` subclasses need config-generator regeneration AND may need `@Setter` same-FQCN replacement
- **Missing config files**: Runtime config files (non-DSL) must be in distribution assembly descriptors
- **OAL builder registration**: the precompiler generates constructor entries for every OAL metrics, builder and dispatcher class it compiled (incl. a rules overlay's scripts); the older hand-kept entries in `reachability-metadata.json` are redundant
- **Config-generator runs against upstream classpath**: Setter checks see upstream classes (no `@Setter`), not our for-graalvm replacements — the generator trusts that for-graalvm modules will provide setters