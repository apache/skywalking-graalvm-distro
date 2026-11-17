# oap-libs-for-graalvm — Same-FQCN Replacement Modules

## Purpose

Each module under `oap-libs-for-graalvm/` repackages one upstream SkyWalking JAR
using `maven-shade-plugin`. The shade plugin includes the full upstream JAR but
**excludes** specific `.class` files, replacing them with GraalVM-compatible
versions that live in this module's `src/main/java/`.

## Sync with Upstream (`skywalking/` submodule)

Every replacement class must stay in sync with its upstream counterpart. When the
`skywalking/` submodule is updated, replacement classes may need corresponding updates.

### Staleness Detection

Two test-based mechanisms detect upstream drift:

1. **`ReplacementClassStalenessTest`** — SHA-256 tracks upstream `.java` source files.
   Hashes recorded in `oap-graalvm-server/src/test/resources/replacement-source-sha256.properties`.
   Tracks every replacement's upstream source (26 files, see "Tracking Gaps" below).

2. **`PrecompiledYamlStalenessTest`** — SHA-256 tracks ~116 YAML rule files (MAL, LAL, TAL, ...) consumed
   by the precompiler. Hashes recorded in `oap-graalvm-server/src/test/resources/precompiled-yaml-sha256.properties`.

### After a submodule update

```bash
# 1. Run staleness tests
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl oap-graalvm-server test \
  -Dtest="ReplacementClassStalenessTest,PrecompiledYamlStalenessTest"

# 2. If tests fail, review changed upstream files and update replacements
# 3. Update SHA-256 hashes:
shasum -a 256 skywalking/oap-server/path/to/changed/Source.java

# 4. Full build to verify
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro
```

## Complete Replacement Inventory

### server-core-for-graalvm (7 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `OALEngineLoaderService` | `server-core/.../oal/rt/OALEngineLoaderService.java` | Load OAL classes from manifests instead of Javassist | **Yes** |
| `AnnotationScan` | `server-core/.../annotation/AnnotationScan.java` | Read manifests instead of Guava ClassPath scan | **Yes** |
| `SourceReceiverImpl` | `server-core/.../source/SourceReceiverImpl.java` | Read manifests instead of Guava ClassPath scan | **Yes** |
| `MeterSystem` | `server-core/.../analysis/meter/MeterSystem.java` | Read MeterFunction manifest; load pre-generated Javassist classes | **Yes** |
| `CoreModuleConfig` | `server-core/.../CoreModuleConfig.java` | Added `@Setter` at class level | **Yes** |
| `HierarchyDefinitionService` | `server-core/.../config/HierarchyDefinitionService.java` | Java-backed closures instead of GroovyShell | **Yes** |
| `HierarchyService` | `server-core/.../hierarchy/HierarchyService.java` | Support for Java-backed closures | **Yes** |

### library-server-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DynamicSslContext` | `library-server/.../grpc/ssl/DynamicSslContext.java` | `SslProvider.JDK` instead of `SslProvider.OPENSSL` (no `netty_tcnative` needed for native image) | **Yes** |

### meter-analyzer-for-graalvm (3 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `meter-analyzer/.../v2/dsl/DSL.java` | Load pre-compiled `MalExpression` from per-file configs (`META-INF/mal-v2/`); look up by expression text; closure fields are self-wired by companion classes in the static initializer (no `LambdaMetafactory`) | **Yes** |
| `FilterExpression` | `meter-analyzer/.../v2/dsl/FilterExpression.java` | Load pre-compiled `MalFilter` from v2 manifest | **Yes** |
| `Rules` | `meter-analyzer/.../v2/prometheus/rule/Rules.java` | Load rule files from the precompiler's config-data JSON instead of YAML | **Yes** |

### log-analyzer-for-graalvm (3 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `log-analyzer/.../v2/dsl/DSL.java` | Load pre-compiled `LalExpression` from v2 manifest | **Yes** |
| `LALConfigs` | `log-analyzer/.../v2/provider/LALConfigs.java` | Load rule files from `config-data/lal.json` instead of YAML | **Yes** |
| `LogAnalyzerModuleConfig` | `log-analyzer/.../v2/provider/LogAnalyzerModuleConfig.java` | Added `@Setter` at class level | **Yes** |

### trace-analyzer-for-graalvm (2 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `TalDSL` | `trace-analyzer/.../v2/dsl/TalDSL.java` | Load pre-compiled `TalExpression` from `tal-v2-rules.txt`; set its function-object fields from the registry by the recorded keys; claim + create the manifest's metric declarations | **Yes** |
| `TALConfigs` | `trace-analyzer/.../v2/provider/TALConfigs.java` | Load from `config-data/tal.json` instead of `tal/*.yaml` + `RuleSetMerger`; `requiredModules`, `loadInOrder`, `requiredModulesOf` from the same JSON | **Yes** |

### agent-analyzer-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `AnalyzerModuleConfig` | `agent-analyzer/.../provider/AnalyzerModuleConfig.java` | Added `@Setter` at class level | **Yes** |

### library-module-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `ModuleDefine` | `library-module/.../module/ModuleDefine.java` | Added `prepare()` overload for direct provider wiring | **Yes** |

### library-util-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `VirtualThreads` | `library-util/.../util/VirtualThreads.java` | Direct JDK 25 API calls instead of reflection | **Yes** |

Also uses shade plugin to exclude `YamlConfigLoaderUtils`, `ResourceUtils`,
`FieldsHelper` from upstream JAR. The replacements for those live in
`oap-graalvm-server/` (due to 30+ cross-module imports).

### Config-only replacements (added `@Setter` at class level)

| Module | Replacement Class | Staleness Tracked |
|---|---|---|
| `envoy-metrics-receiver-for-graalvm` | `EnvoyMetricReceiverConfig` | **Yes** |
| `otel-receiver-for-graalvm` | `OtelMetricReceiverConfig` | **Yes** |
| `ebpf-receiver-for-graalvm` | `EBPFReceiverModuleConfig` | **Yes** |
| `aws-firehose-receiver-for-graalvm` | `AWSFirehoseReceiverModuleConfig` | **Yes** |
| `cilium-fetcher-for-graalvm` | `CiliumFetcherConfig` | **Yes** |
| `health-checker-for-graalvm` | `HealthCheckerConfig` | **Yes** |


### server-starter-for-graalvm (0 classes, resource-only)

Repackages upstream `server-starter` with `version.properties` excluded (distro
generates its own from git).

## Tracking Gaps

All **26 replacement source files** are tracked in `replacement-source-sha256.properties`.
The v2 upstream migration removed many v1 files; remaining replacements cover:

- server-core-for-graalvm: 7 files (OALEngineLoaderService, AnnotationScan, SourceReceiverImpl, MeterSystem, CoreModuleConfig, HierarchyDefinitionService, HierarchyService)
- meter-analyzer-for-graalvm: 3 files (DSL v2, FilterExpression v2, Rules v2)
- log-analyzer-for-graalvm: 3 files (DSL v2, LogAnalyzerModuleConfig v2, LALConfigs v2)
- trace-analyzer-for-graalvm: 2 files (TalDSL, TALConfigs)
- agent-analyzer-for-graalvm: 1 file (AnalyzerModuleConfig)
- library-module-for-graalvm: 1 file (ModuleDefine)
- library-util-for-graalvm: 1 file (VirtualThreads); `YamlConfigLoaderUtils` lives in `oap-graalvm-server`
- Config-only @Setter additions: 6 files

## Verification Tests

All tests live in `oap-graalvm-server/src/test/`:

| Test | What It Verifies |
|---|---|
| `ReplacementClassStalenessTest` | SHA-256 of all tracked upstream sources |
| `PrecompiledYamlStalenessTest` | SHA-256 of ~116 YAML rule files |
| `PrecompiledRegistrationTest` | Manifests match live Guava ClassPath scans; all OAL/MAL/LAL classes loadable |
| 73 MAL comparison tests | Dual-path: fresh v2 compilation vs pre-compiled class |
| 5 LAL pre-compilation tests | Pre-compiled LAL classes load from manifest |

## Adding a New Replacement

1. Create (or add to existing) `*-for-graalvm` module under `oap-libs-for-graalvm/`
2. Add the replacement `.java` file with the **same FQCN** as upstream
3. Configure shade plugin in `pom.xml` to exclude the original `.class` (and inner classes with `$*`)
4. Add the `-for-graalvm` artifact to root `pom.xml` `<dependencyManagement>`
5. In `oap-graalvm-server/pom.xml`: add original JAR to `<dependencyManagement>` as `provided`, add `-for-graalvm` to `<dependencies>`
6. Add the original JAR to `distribution.xml` `<excludes>`
7. Add upstream source SHA-256 to `replacement-source-sha256.properties`
