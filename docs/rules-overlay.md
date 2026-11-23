# Compiling Your Own Rules

A native image cannot generate classes at runtime, so this distro compiles every rule (OAL, MAL, LAL, TAL and the
hierarchy matching rules) when it is built, and the image takes no rule file from its deployment at boot. To run your own rules, keep them
in your own repository, reference this repository from it (a git submodule or a checkout pinned to a commit), and build
the image with your rule files compiled in. The result is a self-contained native image, or JVM distribution, carrying
your rules: nothing about the rules is deployed or configured next to it.

Your rule files are a **rules overlay**: a directory laid out like OAP's `config/` with a `rules-overlay.yaml` that
declares which bundled rule files it adds, overrides and excludes.

## Rules are compiled in, never configured

**Every compiled rule file is active.** Upstream SkyWalking picks rule files at boot from `application.yml` settings
and their environment variables. In this distro, the image fills those settings with every file it compiled, and the
rules can't be selected, added or replaced at runtime:

| Module | Setting | Environment variable |
|--------|---------|----------------------|
| `receiver-otel` | `enabledOtelMetricsRules` | `SW_OTEL_RECEIVER_ENABLED_OTEL_METRICS_RULES` |
| `agent-analyzer` | `meterAnalyzerActiveFiles` | `SW_METER_ANALYZER_ACTIVE_FILES` |
| `log-analyzer` | `malFiles` | `SW_LOG_MAL_FILES` |
| `log-analyzer` | `lalFiles` | `SW_LOG_LAL_FILES` |
| `trace-analyzer` | `talFiles` | `SW_TRACE_TAL_FILES` |
| `trace-analyzer` | `nativeTalFiles` | `SW_TRACE_NATIVE_TAL_FILES` |
| `envoy-metric` | `enabledEnvoyMetricsRules` | `SW_ENVOY_METRIC_ENABLED_RULES` |
| `receiver-telegraf` | `activeFiles` | `SW_RECEIVER_TELEGRAF_ACTIVE_FILES` |
| `receiver-zabbix` | `activeFiles` | `SW_RECEIVER_ZABBIX_ACTIVE_FILES` |

- The image sets them after reading the configuration, so no value given for them takes effect.
- A rule file that yields no rule is active like any other: one of comments only, or a LAL or TAL file without a
  `rules` list. Its loader reads it and returns no rule for it, as upstream's does, so overriding a bundled file with
  comments only turns its rules off. The MAL and LAL loaders record it for the read-only rule catalog; under a catalog
  `/runtime/rule` serves (`otel-rules`, `log-mal-rules`, `telegraf-rules`, `meter-analyzer-config`, `lal`), it shows
  as bundled and is not listed. `envoy-metrics-rules` goes through the MAL loader too, but `/runtime/rule` serves no
  such catalog; the Zabbix loader skips such a file without recording it, and TAL has no HTTP catalog. A
  TAL file of `layerDefinitions` alone registers its layers; a LAL one does not, as upstream's LAL loader skips such a
  file before registering them. A MAL file of `layerDefinitions` alone fails the build, as upstream's boot fails on it.
  A bundled native TAL file overridden without rules stays in `nativeTalFiles`, as upstream lists it, so it is read
  only with the native rules (the agent analyzer loaded).
- Giving one anyway fails the boot rather than being silently ignored, wherever it is given: in `application.yml`
  (under any provider, selected or not), by its environment variable, or by system property under either name
  (`-DSW_OTEL_RECEIVER_ENABLED_OTEL_METRICS_RULES=…`, `-Dreceiver-otel.<provider>.enabledOtelMetricsRules=…`). A
  deployment carried over from upstream, such as a Helm chart that still sets one, finds out at once.
- So does a setting that locates rule files or carries rules (`log-analyzer` `lalPath`, `malPath` and its
  `meterConfigs` cache, `trace-analyzer` `talPath`), given in `application.yml` or by system property
  (`-Dlog-analyzer.<provider>.lalPath=…`); upstream's `application.yml` gives none of them, so they have no environment
  variable.
- A rule file in the directory OAP reads its configuration from fails the boot too (`oal/`, `otel-rules/`,
  `meter-analyzer-config/`, `log-mal-rules/`, `envoy-metrics-rules/`, `telegraf-rules/`, `zabbix-rules/`, `lal/`,
  `tal/`, `hierarchy-definition.yml`, `layer-extensions.yml`): the native image's `config/`, or the JVM distribution's
  `config/` (where `application.yml` is read from). Rules enter the image only at build time.

Both fail rather than being ignored, so a deployment can't believe it changed the rules when it didn't. Everything else in `application.yml`, `alarm-settings.yml` and the
other runtime configuration files stays configurable, including whether a module runs (for example `SW_RECEIVER_ZABBIX=-`): a disabled module's rules stay compiled and unused.

The image built from this repository without an overlay compiles every rule file upstream bundles.

## Supported rule files

An overlay may add, override or exclude these files, the ones upstream reads rules from:

| Path | Engine |
|------|--------|
| `oal/*.oal` | OAL |
| `otel-rules/**` | MAL, OpenTelemetry metrics |
| `meter-analyzer-config/**` | MAL, SkyWalking meter protocol |
| `log-mal-rules/**` | MAL, metrics from logs |
| `envoy-metrics-rules/**` | MAL, Envoy metrics service |
| `telegraf-rules/**` | MAL, Telegraf |
| `zabbix-rules/*` | MAL, Zabbix |
| `lal/*` | LAL |
| `tal/*` | TAL, OTLP spans and SkyWalking agent segments |
| `hierarchy-definition.yml` | Service hierarchy |
| `layer-extensions.yml` | Layer extensions, registered before any rule compiles |

Rule files are YAML, as upstream documents them: `.yaml` or `.yml`, and `.oal` for an OAL script. As upstream reads
them, MAL rule files sit in their directory or below it (`**`), the others in the directory itself. A path uses ASCII
letters, digits, `.`, `_` and `-` only, at most 64 characters a segment and 128 in all, and a MAL file one extension:
the image carries the names into the rule settings lists, generated class files and the rule browser's URLs. A
case-insensitive disk takes names differing by case only for one, so a directory differing from a bundled one by case
only (`otel-rules/K8S/`) fails the build, even when every bundled file under it is excluded; use the bundled spelling.
Two compiled rule files may not differ by case or extension only either, but excluding `lal/default.yaml` lets the
overlay add `lal/DEFAULT.yaml`. Excluding every file of a directory is fine: its setting is then an empty list, as
upstream accepts one.

Other files in the overlay directory, such as a README at its root or a file of another extension in a rule
directory, are not rule files: they are ignored, the build log lists them, and a manifest entry naming one fails the
build. Hidden files are skipped.

## `rules-overlay.yaml`

The manifest at the overlay root declares every change. Each entry is a path or a glob relative to the overlay root:

```yaml
add:                      # rule files upstream doesn't have
  - otel-rules/fleet/*.yaml
  - oal/fleet.oal
  - lal/fleet.yaml
  - tal/fleet.yaml
override:                 # replace a bundled file as a whole
  - otel-rules/vm.yaml
  - layer-extensions.yml
exclude:                  # bundled files not to compile, so not active
  - oal/tcp.oal
  - "otel-rules/**"
```

- `exclude` matches bundled files only. A file the overlay overrides stays compiled, so `otel-rules/**` plus the
  `override` of `otel-rules/vm.yaml` above leaves just that file and the added `otel-rules/fleet/` files. This is how
  to compile a few of a directory's files: exclude the directory and override or add the ones you run.
- The build fails when:
  - a rule file in the overlay is not declared, or is declared both as add and as override, or an entry is listed
    twice;
  - an `add` names a file upstream has, or an `override` one it doesn't;
  - an `add` or `override` entry matches no overlay rule file, or an `exclude` entry matches no bundled file the
    overlay doesn't provide, so a manifest can't go stale silently after an upstream update;
  - two compiled rule files differ only by extension or case (`foo.yaml`, `Foo.yml`);
  - a rule file is nested in a directory read flat (`lal/sub/x.yaml`), or its path breaks the naming rule above;
  - `hierarchy-definition.yml` is excluded. To turn the hierarchy off, override it with
    `hierarchy: {}`, `auto-matching-rules: {}` and `layer-levels: {}`;
  - a rule doesn't compile, or would fail upstream's boot (such as an unknown input type or function, or conflicting
    metric shapes);
  - a LAL rule, of `layer: auto` or a known layer, declares `fallback: true` off a `layer: auto` rule or shares its
    name with another rule of its layer. Rules on one line (flow-style YAML) may share a name in other layers, as
    upstream accepts them; two that also agree on DSL text and input and output types run one class, the layer being
    no input of it. A rule upstream skips at boot with a warning (of a layer no file, extension or module registers, or
    a `layer: auto` rule with a `sink` but no `layer` statement) is not compiled and still skipped with that warning;
  - a TAL file mixes `sw-native` rules with rules of another input, or two `sw-native` rules share a name, or two
    rules of the other files share a name in one layer: the `sw-native` files become `nativeTalFiles`, the others
    `talFiles`. Two TAL rules on one line sharing a name in other layers and agreeing on DSL text, input and metric
    prefix run one class, and fail the build should that class's function fields or metric declarations differ
    between them. A TAL file requiring a module this image does not ship is never loaded at boot, so it is not
    compiled: none of its layers is registered;
  - `layer-levels` names a layer neither built in nor in `layer-extensions.yml`; the hierarchy maps a layer to
    something other than its lower layers, relates a layer whose level is missing or isn't an integer, puts an upper
    layer's level not above a lower layer's, or maps a lower layer to anything but a matching rule's name (or nothing,
    to relate the two without automatic matching).
- Otherwise a rule file is accepted as upstream SkyWalking accepts it. What depends on the modules a deployment runs,
  such as a layer only another receiver's rule file declares or a function of a module that isn't loaded, is checked
  when the OAP boots, as upstream checks it.
- The same metric name in several files is allowed, as upstream allows it: the bundled OpenTelemetry, Telegraf and
  Zabbix VM rules share their metrics.

### OAL

- An excluded script isn't compiled: none of its metrics exist. An alarm rule in `alarm-settings.yml` on one of them
  (the bundled ones use `core.oal` metrics such as `service_resp_time`) needs a runtime `alarm-settings.yml` without
  it; `rules: {}` turns the alarms off.
- An added script gets a define of its own and may use the sources of `org.apache.skywalking.oap.server.core.source`.
  A script that uses another package's sources fails the build. No module loads it, so it runs from the boot on.
- A bundled script runs when its module loads it, as upstream: `cilium.oal` with the Cilium fetcher, `tcp.oal` with
  the Envoy TCP access log analysis (`SW_ENVOY_METRIC_ALS_TCP_ANALYSIS`). A module that loads an excluded script boots
  and logs that the overlay excluded it.

## Building from your own repository

A typical layout, with this repository as a submodule pinned to a release or commit:

```
my-oap/
├── skywalking-graalvm-distro/        # this repository
├── rules/
│   ├── rules-overlay.yaml
│   ├── otel-rules/fleet/fleet-service.yaml
│   ├── lal/fleet.yaml
│   └── layer-extensions.yml
└── build.sh
```

Pass the overlay as an **absolute path** without spaces: `make -C` resolves a relative one against this repository,
not yours. Keep this repository's checkout path to letters, digits, `/`, `.`, `_` and `-` as well: the rule loaders
find the rule directories through the class path, which does not decode characters such as `#` or `%`.

```bash
DISTRO=$PWD/skywalking-graalvm-distro
RULES=$PWD/rules
M2=$PWD/.m2/repository                  # optional: a Maven repository of your own, kept apart from ~/.m2

# Once per distro commit: install the bundled SkyWalking it builds on.
make -C "$DISTRO" init-skywalking M2_REPO="$M2"

# Compile the rules and run the tests that check them, without the native image: the check for CI.
make -C "$DISTRO" verify-rules RULES_OVERLAY="$RULES" M2_REPO="$M2"

# The native image, on Linux, or cross-compiled for Linux on macOS (in a GraalVM container).
make -C "$DISTRO" native-image RULES_OVERLAY="$RULES" M2_REPO="$M2"
make -C "$DISTRO" native-image-macos RULES_OVERLAY="$RULES" M2_REPO="$M2"

# The Docker image, from that native image.
make -C "$DISTRO" docker-native
```

- `RULES_OVERLAY` takes effect in every target that compiles: `compile`, `build-distro` (the JVM distribution),
  `native-image`, `native-image-macos` and `verify-rules`.
- `verify-rules` compiles the rules and runs the precompiler's tests and the tests over the compiled rules. It runs
  every overlay MAL rule through its compiled class and through a fresh compile on generated inputs, checks that
  every overlay LAL rule has its class, and holds the read-only rule catalog to upstream's answers over the rules.
- `M2_REPO=<dir>` builds with that Maven repository instead of `~/.m2/repository`, and `native-image-macos` mounts it
  into its container. A build installs its compiled rules into the Maven repository it uses, so builds running at the
  same time with different overlays each need their own.
- Each build restages the rules from scratch: a build without `RULES_OVERLAY` leaves nothing of an earlier overlay.
- To track a new release of this repository, move the submodule and rebuild. A manifest entry that no longer matches
  the bundled files fails the build and names the entry.
- This repository's CI does the same with `test/e2e/cases/rules-overlay/rules/`, copied out of the checkout: a MAL, a
  LAL and a TAL rule under a layer of its own and an OAL script over the agent services the kept native TAL rules
  find, most bundled rules excluded and a few kept. The `rules-overlay` e2e case then checks the native image runs
  its rules and the kept ones, and has none of the excluded.

## What the image holds

Both distributions carry `compiled-rules/` at their root:

```
compiled-rules/
├── rules-overlay.yaml     # the manifest, when built with an overlay
├── BUILD-INFO             # upstream commit and the overlay digest ("none" without one)
├── oal/ otel-rules/ meter-analyzer-config/ log-mal-rules/ envoy-metrics-rules/ telegraf-rules/ zabbix-rules/
├── lal/ tal/
├── hierarchy-definition.yml
└── layer-extensions.yml
```

It holds copies of the rule files the image was compiled from, after add, override and exclude (a file holding no
rule, such as the bundled `log-mal-rules/placeholder.yaml`, is copied as it is). It is a record only:
the image runs the classes compiled from these files and never reads the folder.

At boot, the OAP logs `Every compiled rule file is active: {…}` with the files each rule-selecting setting got.
