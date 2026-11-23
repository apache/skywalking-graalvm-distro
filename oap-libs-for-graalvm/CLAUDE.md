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
   Tracks every replacement's upstream source (20 files, see "Tracking Gaps" below).

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

### server-core-for-graalvm (6 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `OALEngineLoaderService` | `server-core/.../oal/rt/OALEngineLoaderService.java` | Register a define's compiled OAL script (`CompiledRules`) when its provider loads it, instead of Javassist | **Yes** |
| `AnnotationScan` | `server-core/.../annotation/AnnotationScan.java` | Read manifests instead of Guava ClassPath scan | **Yes** |
| `SourceReceiverImpl` | `server-core/.../source/SourceReceiverImpl.java` | Read manifests instead of Guava ClassPath scan | **Yes** |
| `MeterSystem` | `server-core/.../analysis/meter/MeterSystem.java` | Take the meter functions and pre-generated Javassist classes from the rule registry; `create` is upstream's boot path with the class the build recorded for the metric name looked up instead of generated | **Yes** |
| `HierarchyDefinitionService` | `server-core/.../config/HierarchyDefinitionService.java` | Definition and pre-compiled matching rules from the rule registry instead of the YAML and the `HierarchyRuleProvider` SPI; upstream's `HierarchyService` runs unchanged on its `MatchingRule` | **Yes** |
| `LayerExtensionLoader` | `server-core/.../analysis/LayerExtensionLoader.java` | `layer-extensions.yml` entries from the rule registry instead of the file; SPI as upstream | **Yes** |

### library-server-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DynamicSslContext` | `library-server/.../grpc/ssl/DynamicSslContext.java` | `SslProvider.JDK` instead of `SslProvider.OPENSSL` (no `netty_tcnative` needed for native image) | **Yes** |

### meter-analyzer-for-graalvm (3 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `meter-analyzer/.../v2/dsl/DSL.java` | Take the pre-compiled `MalExpression` from the rule registry by the coordinates `MetricConvert` passes, checking the metric name and text; closure fields are self-wired by companion classes in the static initializer (no `LambdaMetafactory`) | **Yes** |
| `FilterExpression` | `meter-analyzer/.../v2/dsl/FilterExpression.java` | Take the pre-compiled `MalFilter` from the rule registry by the coordinates `MetricConvert` passes, checking the literal | **Yes** |
| `Rules` | `meter-analyzer/.../v2/prometheus/rule/Rules.java` | Rule files from the rule registry instead of YAML, selected with upstream's patterns and passed through `RuleSetMerger` (`RuleSources`); registers inline `layerDefinitions` | **Yes** |

### log-analyzer-for-graalvm (2 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `log-analyzer/.../v2/dsl/DSL.java` | Take the pre-compiled `LalExpression` from the rule registry by source coordinates and compile inputs (name, DSL text, requested types) | **Yes** |
| `LALConfigs` | `log-analyzer/.../v2/provider/LALConfigs.java` | Rule files from the rule registry instead of YAML, passed through `RuleSetMerger` (`RuleSources`), in the order upstream's loader returned them all at build time; registers inline `layerDefinitions` | **Yes** |

### trace-analyzer-for-graalvm (2 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `TalDSL` | `trace-analyzer/.../v2/dsl/TalDSL.java` | Take the pre-compiled `TalExpression` from the rule registry; set its function-object fields from the function registry by the recorded keys; claim + create the recorded metric declarations | **Yes** |
| `TALConfigs` | `trace-analyzer/.../v2/provider/TALConfigs.java` | Rule files from the rule registry instead of `tal/*.yaml`, passed through `RuleSetMerger` (`RuleSources`), in the order upstream's loader returned them at build time; `requiredModules`, `loadInOrder` from the same registry, `requiredModulesOf` as upstream's own read gave it at build time | **Yes** |

### zabbix-receiver-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `ZabbixConfigs` | `skywalking-zabbix-receiver-plugin/.../provider/config/ZabbixConfigs.java` | The Zabbix configs from the rule registry instead of `zabbix-rules/*.yaml` | **Yes** |

### library-util-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `VirtualThreads` | `library-util/.../util/VirtualThreads.java` | Direct JDK 25 API calls instead of reflection | **Yes** |

Also uses shade plugin to exclude `YamlConfigLoaderUtils`, `ResourceUtils`,
`FieldsHelper` from upstream JAR. The replacements for those live in
`oap-graalvm-server/` (due to 30+ cross-module imports), as does `LoggingConfigWatcher` (server-core).

### server-starter-for-graalvm (0 classes, resource-only)

Repackages upstream `server-starter` with `version.properties` excluded (distro
generates its own from git). Its `server-starter` dependency excludes `*:*`: the starter
depends on every upstream plugin, and the boot loads modules through the module SPI, so a
plugin the distro does not ship must not reach the classpath.

## Tracking Gaps

All **20 replacement source files** are tracked in `replacement-source-sha256.properties`, with the upstream sources
whose logic a distro class or precompiler pass copies or replays (`OAPServerBootstrap`, the runtime-rule read routes,
`LogFilterListener`, `SpanFilterListener`, ...). `ReplacementClassStalenessTest` requires every same-FQCN class here and
in `oap-graalvm-server` to be tracked.
The v2 upstream migration removed many v1 files; remaining replacements cover:

- server-core-for-graalvm: 6 files (OALEngineLoaderService, AnnotationScan, SourceReceiverImpl, MeterSystem, HierarchyDefinitionService, LayerExtensionLoader)
- meter-analyzer-for-graalvm: 3 files (DSL v2, FilterExpression v2, Rules v2)
- log-analyzer-for-graalvm: 2 files (DSL v2, LALConfigs v2)
- trace-analyzer-for-graalvm: 2 files (TalDSL, TALConfigs)
- zabbix-receiver-for-graalvm: 1 file (ZabbixConfigs)
- library-util-for-graalvm: 1 file (VirtualThreads)
- library-server-for-graalvm: 1 file (DynamicSslContext)
- oap-graalvm-server: 4 files (YamlConfigLoaderUtils, ResourceUtils, FieldsHelper, LoggingConfigWatcher)

## Verification Tests

All tests live in `oap-graalvm-server/src/test/`:

| Test | What It Verifies |
|---|---|
| `ReplacementClassStalenessTest` | SHA-256 of all tracked upstream sources |
| `PrecompiledYamlStalenessTest` | SHA-256 of ~116 YAML rule files |
| `PrecompiledRegistrationTest` | Annotation-scan manifests match live Guava ClassPath scans; all OAL/MAL/LAL classes of the rule registry loadable |
| 73 MAL comparison tests | Dual-path: fresh v2 compilation vs pre-compiled class |
| 5 LAL pre-compilation tests | Pre-compiled LAL classes load from the rule registry |
| `RegistryParityTest` (precompiler) | The rule registry holds what upstream's loaders read from every bundled file, and the classes the build manifests record; its hierarchy is what upstream's `HierarchyDefinitionService` builds over the staged file, and each matcher decides upstream's hierarchy test pairs as a fresh compile does |

## Adding a New Replacement

1. Create (or add to existing) `*-for-graalvm` module under `oap-libs-for-graalvm/`
2. Add the replacement `.java` file with the **same FQCN** as upstream
3. Configure shade plugin in `pom.xml` to exclude the original `.class` (and inner classes with `$*`)
4. Add the `-for-graalvm` artifact to root `pom.xml` `<dependencyManagement>`
5. In `oap-graalvm-server/pom.xml`: add original JAR to `<dependencyManagement>` as `provided`, add `-for-graalvm` to `<dependencies>`
6. Add the original JAR to `distribution.xml` `<excludes>`
7. Add upstream source SHA-256 to `replacement-source-sha256.properties`
8. A replacement that reads the rule registry (`org.apache.skywalking.oap.server.graalvm.rules.CompiledRules`)
   depends on `precompiler:generated` as `provided` with a `*:*` exclusion, like `log-analyzer-for-graalvm`
