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
   Currently tracks 4 files (see "Tracking Gaps" below).

2. **`PrecompiledYamlStalenessTest`** — SHA-256 tracks ~49 YAML rule files consumed
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
| `OALEngineLoaderService` | `server-core/.../oal/rt/OALEngineLoaderService.java` | Load OAL classes from manifests instead of Javassist | No |
| `AnnotationScan` | `server-core/.../annotation/AnnotationScan.java` | Read manifests instead of Guava ClassPath scan | No |
| `SourceReceiverImpl` | `server-core/.../source/SourceReceiverImpl.java` | Read manifests instead of Guava ClassPath scan | No |
| `MeterSystem` | `server-core/.../analysis/meter/MeterSystem.java` | Read MeterFunction manifest; load pre-generated Javassist classes | No |
| `CoreModuleConfig` | `server-core/.../CoreModuleConfig.java` | Added `@Setter` at class level | No |
| `HierarchyDefinitionService` | `server-core/.../config/HierarchyDefinitionService.java` | Java-backed closures instead of GroovyShell | No |
| `HierarchyService` | `server-core/.../hierarchy/HierarchyService.java` | Support for Java-backed closures | No |

### meter-analyzer-for-graalvm (10 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `meter-analyzer/.../dsl/DSL.java` | Load transpiled `MalExpression` from manifest | No |
| `FilterExpression` | `meter-analyzer/.../dsl/FilterExpression.java` | Load transpiled `MalFilter` from manifest | No |
| `Expression` | `meter-analyzer/.../dsl/Expression.java` | Uses `MalExpression` instead of `DelegatingScript` | No |
| `ExpressionParsingContext` | `meter-analyzer/.../dsl/ExpressionParsingContext.java` | Adapted for `MalExpression` | No |
| `SampleFamily` | `meter-analyzer/.../dsl/SampleFamily.java` | Closure params → Java functional interfaces | No |
| `InstanceEntityDescription` | `meter-analyzer/.../EntityDescription/InstanceEntityDescription.java` | Closure params → Java functional interfaces | No |
| `Rules` | `meter-analyzer/.../prometheus/rule/Rules.java` | Load from JSON manifests instead of filesystem YAML | **Yes** |
| `MalExpression` | (new) | Interface for transpiled MAL expressions | N/A |
| `MalFilter` | (new) | Interface for transpiled MAL filters | N/A |
| `SampleFamilyFunctions` | (new) | Java functional interfaces replacing Groovy Closures | N/A |

### log-analyzer-for-graalvm (9 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `DSL` | `log-analyzer/.../dsl/DSL.java` | Load transpiled `LalExpression` from manifest | No |
| `LogAnalyzerModuleConfig` | `log-analyzer/.../provider/LogAnalyzerModuleConfig.java` | Added `@Setter` at class level | No |
| `LALConfigs` | `log-analyzer/.../provider/LALConfigs.java` | Load from JSON manifests instead of filesystem YAML | **Yes** |
| `AbstractSpec` | `log-analyzer/.../dsl/spec/AbstractSpec.java` | Added `abort()` no-arg overload | No |
| `FilterSpec` | `log-analyzer/.../dsl/spec/filter/FilterSpec.java` | Added `Consumer` overloads for transpiled code | No |
| `ExtractorSpec` | `log-analyzer/.../dsl/spec/extractor/ExtractorSpec.java` | Added `Consumer` overloads | No |
| `SinkSpec` | `log-analyzer/.../dsl/spec/sink/SinkSpec.java` | Added `Consumer` overloads | No |
| `SamplerSpec` | `log-analyzer/.../dsl/spec/sink/SamplerSpec.java` | Added String-keyed sampler overloads | No |
| `LalExpression` | (new) | Interface for transpiled LAL expressions | N/A |

### agent-analyzer-for-graalvm (2 classes)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `AnalyzerModuleConfig` | `agent-analyzer/.../provider/AnalyzerModuleConfig.java` | Added `@Setter` at class level | No |
| `MeterConfigs` | `agent-analyzer/.../meter/config/MeterConfigs.java` | Load from JSON manifests instead of filesystem YAML | **Yes** |

### library-module-for-graalvm (1 class)

| Replacement Class | Upstream Source | Change | Staleness Tracked |
|---|---|---|---|
| `ModuleDefine` | `library-module/.../module/ModuleDefine.java` | Added `prepare()` overload for direct provider wiring | **Yes** |

### library-util-for-graalvm (0 classes, shade-only)

No replacement Java sources. Uses shade plugin to exclude `YamlConfigLoaderUtils`,
`ResourceUtils`, `FieldsHelper` from upstream JAR. The replacements for these live
in `oap-graalvm-server/` (due to 30+ cross-module imports).

### Config-only replacements (added `@Setter` at class level)

| Module | Replacement Class | Staleness Tracked |
|---|---|---|
| `envoy-metrics-receiver-for-graalvm` | `EnvoyMetricReceiverConfig` | No |
| `otel-receiver-for-graalvm` | `OtelMetricReceiverConfig` | No |
| `ebpf-receiver-for-graalvm` | `EBPFReceiverModuleConfig` | No |
| `aws-firehose-receiver-for-graalvm` | `AWSFirehoseReceiverModuleConfig` | No |
| `cilium-fetcher-for-graalvm` | `CiliumFetcherConfig` | No |
| `status-query-for-graalvm` | `StatusQueryConfig` | No |
| `health-checker-for-graalvm` | `HealthCheckerConfig` | No |

### groovy-stubs (12 stub classes)

Minimal `groovy.lang.*` stubs for class loading. No `org.codehaus.groovy.*` packages
(prevents GraalVM `GroovyIndyInterfaceFeature` from activating). Not a replacement
of any upstream class — no staleness tracking needed.

### server-starter-for-graalvm (0 classes, resource-only)

Repackages upstream `server-starter` with `version.properties` excluded (distro
generates its own from git).

## Tracking Gaps

Only **4 of ~37 replacement source files** are tracked in `replacement-source-sha256.properties`:

- `ModuleDefine.java`
- `MeterConfigs.java`
- `Rules.java`
- `LALConfigs.java`

The following categories of upstream files have **no SHA-256 staleness tracking**:

| Category | Count | Risk |
|---|---|---|
| Non-trivial rewrites (OALEngineLoaderService, AnnotationScan, SourceReceiverImpl, MeterSystem, DSL x2, FilterExpression, Expression, SampleFamily, HierarchyDefinitionService) | ~10 | **High** — upstream API changes would silently break |
| `@Setter` additions (CoreModuleConfig, AnalyzerModuleConfig, LogAnalyzerModuleConfig, 7 config classes) | ~10 | **Medium** — new fields added upstream won't get setters |
| Spec class `Consumer` overloads (AbstractSpec, FilterSpec, ExtractorSpec, SinkSpec, SamplerSpec) | 5 | **Medium** — new DSL methods upstream won't get overloads |

## Verification Tests

All tests live in `oap-graalvm-server/src/test/`:

| Test | What It Verifies |
|---|---|
| `ReplacementClassStalenessTest` | SHA-256 of 4 tracked upstream sources |
| `PrecompiledYamlStalenessTest` | SHA-256 of ~49 YAML rule files |
| `PrecompiledRegistrationTest` | Manifests match live Guava ClassPath scans; all OAL/MAL/LAL classes loadable |
| 73 MAL comparison tests | Dual-path: fresh Groovy vs transpiled Java (1281 assertions) |
| 5 LAL comparison tests | Dual-path: fresh Groovy vs transpiled Java (19 assertions) |

## Adding a New Replacement

1. Create (or add to existing) `*-for-graalvm` module under `oap-libs-for-graalvm/`
2. Add the replacement `.java` file with the **same FQCN** as upstream
3. Configure shade plugin in `pom.xml` to exclude the original `.class` (and inner classes with `$*`)
4. Add the `-for-graalvm` artifact to root `pom.xml` `<dependencyManagement>`
5. In `oap-graalvm-server/pom.xml`: add original JAR to `<dependencyManagement>` as `provided`, add `-for-graalvm` to `<dependencies>`
6. Add the original JAR to `distribution.xml` `<excludes>`
7. Add upstream source SHA-256 to `replacement-source-sha256.properties`
