# Phase 2: Config Initialization Immigration — Eliminate Reflection in Config Loading

## Context

`YamlConfigLoaderUtils.copyProperties()` uses `Field.setAccessible(true)` +
`field.set()` to populate `ModuleConfig` objects from YAML properties. This
reflection pattern is problematic for GraalVM native image — every config field
would require `reflect-config.json` entries, and `setAccessible()` is restricted.

Since our module/provider set is fixed (37 modules, see DISTRO-POLICY.md), we
can generate hardcoded field-setting code at build time that eliminates all
config-related reflection.

---

## Problem

In `ModuleDefine.prepare()` and in `BanyanDBConfigLoader`,
`copyProperties()` iterates property names, looks up fields by name via
reflection, and sets them:

```java
Field field = getDeclaredField(destClass, propertyName);
field.setAccessible(true);     // restricted in native image
field.set(dest, value);        // needs reflect-config.json
```

This requires:
- `getDeclaredField()` with class hierarchy walk
- `field.setAccessible(true)` to bypass private access
- `field.set()` for every property

---

## Solution: Same-FQCN Replacement of YamlConfigLoaderUtils

Generate a replacement `YamlConfigLoaderUtils.java` with the same FQCN
(`org.apache.skywalking.oap.server.library.util.YamlConfigLoaderUtils`) that
dispatches by config object type and sets fields directly — no
`Field.setAccessible()`, no `getDeclaredField()` scan.

This is the 8th same-FQCN replacement class in the distro.

### Field Access Strategy (per field)

| Strategy | Condition | Example |
|---|---|---|
| **Lombok setter** | All non-final fields (class-level `@Setter` added via `-for-graalvm` modules) | `cfg.setRole((String) value)` |
| **Getter + clear + addAll** | Final collection field | `cfg.getDownsampling().clear(); cfg.getDownsampling().addAll((List) value)` |
| **Error** | Unknown config type | `throw new IllegalArgumentException("Unknown config type: ...")` |

All config classes that previously lacked `@Setter` now have it added via
same-FQCN replacement classes in the `-for-graalvm` modules. No VarHandle, no
reflection fallback. The generator fails at build time if any non-final field
lacks a setter.

### Generated Code Structure

```java
package org.apache.skywalking.oap.server.library.util;

public class YamlConfigLoaderUtils {
    // No VarHandle, no reflection. Pure setter-based.

    // Type-dispatch: check instanceof, delegate to type-specific method
    public static void copyProperties(Object dest, Properties src,
            String moduleName, String providerName)
            throws IllegalAccessException {
        if (dest instanceof CoreModuleConfig) {
            copyToCoreModuleConfig((CoreModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ClusterModuleKubernetesConfig) {
            copyToClusterModuleKubernetesConfig((ClusterModuleKubernetesConfig) dest, src, moduleName, providerName);
        }
        // ... all config types ...
        else {
            throw new IllegalArgumentException("Unknown config type: " + dest.getClass().getName());
        }
    }

    // Per-type: switch on property name, set via Lombok setter
    private static void copyToCoreModuleConfig(
            CoreModuleConfig cfg, Properties src,
            String moduleName, String providerName) {
        // iterate properties
        switch (key) {
            case "role":
                cfg.setRole((String) value);
                break;
            case "persistentPeriod":
                cfg.setPersistentPeriod((int) value);
                break;
            // ... all fields via setters ...
            default:
                log.warn("{} setting is not supported in {} provider of {} module",
                    key, providerName, moduleName);
                break;
        }
    }
    // ... one method per config type ...
}
```

---

## Build Tool

**Module**: `build-tools/config-generator`

**Main class**: `ConfigInitializerGenerator.java`

### Input
- Classpath with all SkyWalking module JARs (same dependencies as `oap-graalvm-server`)
- Provider class list — derived from the fixed module table in `GraalVMOAPServerStartUp`
- Extra config class list — BanyanDB nested configs used by `BanyanDBConfigLoader`

### Process
1. For each provider, call `newConfigCreator().type()` to discover the config class
2. For each config class, use Java reflection to scan all declared fields
   (walking up to `ModuleConfig` superclass)
3. For each field, check if a setter method exists (`set` + capitalize(name))
4. **Fail if any non-final field lacks a setter** (all config classes must have `@Setter`)
5. Generate `YamlConfigLoaderUtils.java` with type-dispatch + switch-based field assignment

The generator depends on `-for-graalvm` modules (not upstream JARs) so it sees
config classes with `@Setter` added. Original upstream JARs are forced to
`provided` scope to prevent classpath shadowing.

### Output
- `oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java`
  (same-FQCN replacement — 8th replacement class)

### Running the generator
```bash
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl build-tools/config-generator exec:java \
  -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java"
```

---

## Config Classes Inventory

37 modules registered in `GraalVMOAPServerStartUp`. 1 (`AlarmModule`) has null
ConfigCreator. The rest need coverage.

### Provider Config Classes

| Provider | Config Class | Fields | Setter Status |
|---|---|---|---|
| CoreModuleProvider | `CoreModuleConfig` | ~40 | `@Getter` class-level, `@Setter` on ~12 fields, rest need VarHandle |
| BanyanDBStorageProvider | `BanyanDBStorageConfig` | ~5 | `@Getter @Setter` |
| ClusterModuleStandaloneProvider | (empty config) | 0 | — |
| ClusterModuleKubernetesProvider | `ClusterModuleKubernetesConfig` | 3 | `@Setter` |
| ConfigmapConfigurationProvider | `ConfigmapConfigurationSettings` | 3 | `@Setter` |
| PrometheusTelemetryProvider | `PrometheusConfig` | 5 | `@Setter` |
| AnalyzerModuleProvider | `AnalyzerModuleConfig` | ~10 | `@Getter @Setter` |
| LogAnalyzerModuleProvider | `LogAnalyzerModuleConfig` | 2 | `@Setter` |
| SharingServerModuleProvider | `SharingServerConfig` | ~15 | Needs check |
| EnvoyMetricReceiverProvider | `EnvoyMetricReceiverConfig` | ~15 | Needs check |
| KafkaFetcherProvider | `KafkaFetcherConfig` | ~20 | `@Data` (all setters) |
| ZipkinReceiverProvider | `ZipkinReceiverConfig` | ~20 | Needs check |
| GraphQLQueryProvider | `GraphQLQueryConfig` | 4 | `@Setter` |
| HealthCheckerProvider | `HealthCheckerConfig` | 1 | `@Setter` |
| + others | (simple configs) | 0-5 | Various |

### BanyanDB Config Loading (BanyanDBConfigLoader)

`BanyanDBConfigLoader` (in `storage-banyandb-plugin`) loads config from `bydb.yml`
and `bydb-topn.yml` independently of the standard YAML config loading path. It
calls `copyProperties()` on nested config objects, so the generated
`YamlConfigLoaderUtils` must handle all BanyanDB inner classes.

**`loadBaseConfig()`** — reads `bydb.yml`, calls `copyProperties()` 11 times:

| Call target | Type | Handler | Fields |
|---|---|---|---|
| `config.getGlobal()` | `Global` | `copyToGlobal()` | 18 (targets, maxBulkSize, flushInterval, ...) |
| `config.getRecordsNormal()` | `RecordsNormal` | `copyToRecordsNormal()` | 8 (inherited from GroupResource) |
| `config.getRecordsLog()` | `RecordsLog` | `copyToRecordsLog()` | 8 |
| `config.getTrace()` | `Trace` | `copyToTrace()` | 8 |
| `config.getZipkinTrace()` | `ZipkinTrace` | `copyToZipkinTrace()` | 8 |
| `config.getRecordsBrowserErrorLog()` | `RecordsBrowserErrorLog` | `copyToRecordsBrowserErrorLog()` | 8 |
| `config.getMetricsMin()` | `MetricsMin` | `copyToMetricsMin()` | 8 |
| `config.getMetricsHour()` | `MetricsHour` | `copyToMetricsHour()` | 8 |
| `config.getMetricsDay()` | `MetricsDay` | `copyToMetricsDay()` | 8 |
| `config.getMetadata()` | `Metadata` | `copyToMetadata()` | 8 |
| `config.getProperty()` | `Property` | `copyToProperty()` | 8 |

**`copyStages()`** — creates warm/cold `Stage` objects, calls `copyProperties()` on each:

| Call target | Type | Handler | Fields |
|---|---|---|---|
| warm Stage | `Stage` | `copyToStage()` | 7 (name, nodeSelector, shardNum, segmentInterval, ttl, replicas, close) |
| cold Stage | `Stage` | `copyToStage()` | 7 |

**`loadTopNConfig()`** — reads `bydb-topn.yml`, populates `TopN` objects via direct
setter calls (`topN.setName()`, `topN.setGroupByTagNames()`, etc.). Does NOT call
`copyProperties()` — no handler needed.

**Class hierarchy note**: All group config classes (`RecordsNormal`, `Trace`,
`MetricsMin`, etc.) extend `GroupResource`. The generator walks the class hierarchy
from each subclass up to `GroupResource` and generates setter calls for all 8
inherited fields (`shardNum`, `segmentInterval`, `ttl`, `replicas`,
`enableWarmStage`, `enableColdStage`, `defaultQueryStages`,
`additionalLifecycleStages`). `Trace` doesn't have its own `@Getter @Setter` but
inherits all setters from `GroupResource`.

**`instanceof` ordering**: Inner classes (`Global`, `RecordsNormal`, `Trace`, etc.)
are static inner classes that do NOT extend `BanyanDBStorageConfig`, so the
`instanceof BanyanDBStorageConfig` check matches only the top-level config. Inner
class checks come after and work independently.

Two extra inner classes (`RecordsTrace`, `RecordsZipkinTrace`) are included in
EXTRA_CONFIG_CLASSES for completeness, even though `BanyanDBConfigLoader` doesn't
currently call `copyProperties()` on them.

---

## Same-FQCN Replacements (Config Initialization)

| Upstream Class | Upstream Location | Replacement Location | What Changed |
|---|---|---|---|
| `YamlConfigLoaderUtils` | `server-library/library-util/.../util/YamlConfigLoaderUtils.java` | `oap-graalvm-server/` (not in library-util-for-graalvm due to 30+ cross-module imports) | Complete rewrite. Uses type-dispatch with Lombok `@Setter` methods instead of `Field.setAccessible()` + `field.set()`. |
| `CoreModuleConfig` | `server-core/.../core/CoreModuleConfig.java` | `oap-libs-for-graalvm/server-core-for-graalvm/` | Added `@Setter` at class level. Upstream only has `@Getter`. |
| `AnalyzerModuleConfig` | `analyzer/agent-analyzer/.../provider/AnalyzerModuleConfig.java` | `oap-libs-for-graalvm/agent-analyzer-for-graalvm/` | Added `@Setter` at class level. |
| `LogAnalyzerModuleConfig` | `analyzer/log-analyzer/.../provider/LogAnalyzerModuleConfig.java` | `oap-libs-for-graalvm/log-analyzer-for-graalvm/` | Added `@Setter` at class level. |
| `EnvoyMetricReceiverConfig` | `server-receiver-plugin/envoy-metrics-receiver-plugin/.../EnvoyMetricReceiverConfig.java` | `oap-libs-for-graalvm/envoy-metrics-receiver-for-graalvm/` | Added `@Setter` at class level. |
| `OtelMetricReceiverConfig` | `server-receiver-plugin/otel-receiver-plugin/.../OtelMetricReceiverConfig.java` | `oap-libs-for-graalvm/otel-receiver-for-graalvm/` | Added `@Setter` at class level. |
| `EBPFReceiverModuleConfig` | `server-receiver-plugin/skywalking-ebpf-receiver-plugin/.../EBPFReceiverModuleConfig.java` | `oap-libs-for-graalvm/ebpf-receiver-for-graalvm/` | Added `@Setter` at class level. |
| `AWSFirehoseReceiverModuleConfig` | `server-receiver-plugin/aws-firehose-receiver/.../AWSFirehoseReceiverModuleConfig.java` | `oap-libs-for-graalvm/aws-firehose-receiver-for-graalvm/` | Added `@Setter` at class level. |
| `CiliumFetcherConfig` | `server-fetcher-plugin/cilium-fetcher-plugin/.../CiliumFetcherConfig.java` | `oap-libs-for-graalvm/cilium-fetcher-for-graalvm/` | Added `@Setter` at class level. |
| `StatusQueryConfig` | `server-query-plugin/status-query-plugin/.../StatusQueryConfig.java` | `oap-libs-for-graalvm/status-query-for-graalvm/` | Added `@Setter` at class level. |
| `HealthCheckerConfig` | `server-health-checker/.../HealthCheckerConfig.java` | `oap-libs-for-graalvm/health-checker-for-graalvm/` | Added `@Setter` at class level. |

Config replacements (except `YamlConfigLoaderUtils`) are repackaged into their respective `-for-graalvm` modules via `maven-shade-plugin`. `YamlConfigLoaderUtils` lives in `oap-graalvm-server` because it imports types from 30+ modules; the original `.class` is excluded from `library-util-for-graalvm` via shade filter.

## Same-FQCN Packaging

The original `YamlConfigLoaderUtils.class` is excluded from the `library-util-for-graalvm` shaded JAR. The replacement in `oap-graalvm-server.jar` is the only copy on the classpath.

---

## Verification

```bash
# Run generator
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl build-tools/config-generator exec:java \
  -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java"

# Full build (compile + test + package)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro
```
