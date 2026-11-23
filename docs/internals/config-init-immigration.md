# Config Initialization — Generated Config Loading

## Context

`YamlConfigLoaderUtils.copyProperties()` uses `Field.setAccessible(true)` +
`field.set()` to populate `ModuleConfig` objects from YAML properties. This
reflection pattern is problematic for GraalVM native image — every config field
would require `reflect-config.json` entries, and `setAccessible()` is restricted.

Since the image ships a known module/provider set (the accepted providers, see
distro-policy.md), we generate the field-setting code at build time: a field its class has a setter for is set
through it; a field no setter writes (none declared, or a final or static field) is written through `Field.set`, as
upstream writes every field, and the precompiler registers those fields from `module-config-field-set.txt`. Upstream's
`ModuleDefine.prepare` still creates each config object through its no-arg constructor
reflectively; the precompiler registers those constructors from `module-config-classes.txt`.

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
dispatches by config object type and sets each field through its class's setter where it has one, the value checked
and converted as upstream's `Field.set` does (`ConfigFieldValues`) — no `Field.setAccessible()`, no
`getDeclaredField()` scan for them. Any other field goes through `Field.set` itself (`ConfigFieldValues.setByField`),
as upstream.

This is one of the distro's same-FQCN replacement classes (see distro-policy.md for the full list).

### Field Access Strategy (per field)

| Strategy | Condition | Example |
|---|---|---|
| **Setter** | A field its class has `set<Name>(<field type>)` for (upstream's own `@Setter` or `@Data`) | `cfg.setTargets(ConfigFieldValues.referenceValue(value, String.class, "...BanyanDBStorageConfig$Global.targets"))` |
| **`Field.set`** | A field no setter writes: none declared (a config class upstream gives only `@Getter`), or a final or static field. Written as upstream writes it, reflectively (registered in `reflect-config.json` from `module-config-field-set.txt`, a final instance field as writable; a static final one refused with `IllegalAccessException`, as upstream) | `ConfigFieldValues.setByField(cfg, "checkIntervalSeconds", value)` |
| **Error** | Unknown config type | `throw new IllegalArgumentException("Unknown config type: ...")` |

The values convert as upstream's `Field.set` converts them (`ConfigFieldValues.intValue(value, field)` and its kind): a primitive field
takes its wrapper or a value of a primitive type that widens to it (an `int` field a `Short`, `Byte` or `Character`
too; a `long` field an `Integer`; a `double` field any primitive number), never a narrowing one (an `int` field refuses a
`Long` or a `Double`: `1.5` is not truncated); a reference field takes an instance of its type or null. A refused value
throws `Field.set`'s `IllegalArgumentException` ("Can not set int field ... to java.lang.Double"). A final field gets
`Field.set` itself, as upstream: a value of another type stops the boot (`envoy-metric.default.listenerMetricsAdapter:
bad`), the field's own list given back keeps its elements, and the null a key of the properties' defaults reads is
assigned. A key naming a static field goes through `Field.set` too, so a constant (`alarm.default.DEFAULT_HTTP_TIMEOUT_SECONDS`)
stops the boot with `IllegalAccessException`, as upstream's does. The precompiler registers every field the loader
writes through `Field.set` from the list the config-generator writes (`module-config-field-set.txt`), a final instance
field with `allowWrite`.

The generator decides each field from the upstream class: a public `set<Name>(<field type>)` means a setter call,
anything else `Field.set`. No config class is copied to add setters, and there is no VarHandle.

### Generated Code Structure

```java
package org.apache.skywalking.oap.server.library.util;

public class YamlConfigLoaderUtils {
    // No VarHandle: setters, and Field.set for a field no setter writes.

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

    // Per-type: switch on property name, set via the setter, a field no setter writes via Field.set
    private static void copyToCoreModuleConfig(
            CoreModuleConfig cfg, Properties src,
            String moduleName, String providerName) throws IllegalAccessException {
        // iterate properties
        switch (key) {
            case "role": // upstream gives CoreModuleConfig no setter for it
                ConfigFieldValues.setByField(cfg, "role", value);
                break;
            case "persistentPeriod":
                cfg.setPersistentPeriod(ConfigFieldValues.intValue(value, "...CoreModuleConfig.persistentPeriod"));
                break;
            case "downsampling":
                ConfigFieldValues.setByField(cfg, "downsampling", value);
                break;
            // ... all fields ...
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
- Classpath: upstream's `server-starter` (every upstream provider) and the admin-server family, the raw upstream
  classes, not the `-for-graalvm` replacements
- Provider class list — the `ModuleProvider` SPI entries on the classpath that `AcceptedModules` (build-common) accepts.
  A provider that does not load, or an accepted one missing from the classpath, fails the generator;
  `ApplicationYmlModulesTest` requires `AcceptedModules` to equal the ACCEPTED providers of
  `provider-inventory.properties`

### Process
1. For each provider, call `newConfigCreator().type()` to discover the config class (none for a null creator or type,
   as `ModuleDefine.prepare` copies nothing then)
2. Add the nested classes of each config class (the BanyanDB group classes `BanyanDBConfigLoader` copies into)
3. For each config class, use Java reflection to scan all declared fields
   (walking up to `ModuleConfig` superclass), and whether the class has a setter for each
4. Generate `YamlConfigLoaderUtils.java` with type-dispatch (a subclass before its superclass) + switch-based field
   assignment

The generated class is compiled in `oap-graalvm-server` against the same upstream classes, so every setter it calls
exists. `GeneratedConfigLoaderIsCurrentTest` requires the committed class to be what the generator writes.

### Output
- `oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java`
  (same-FQCN replacement)
- `build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt` — the config classes, from which the
  precompiler generates their reflection metadata (`ModuleDefine.prepare` instantiates them reflectively)
- `module-config-field-set.txt` next to it — every field the loader writes through `Field.set` (no setter writes it:
  none declared, final or static; on a config class or a superclass up to `ModuleConfig`; `declaringClass#field`, then
  `static` / `final`), which the
  precompiler registers in `reflect-config.json`, a final instance field writable (`allowWrite`)

### Running the generator
```bash
./mvnw -pl build-tools/config-generator exec:java \
  -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt"
```

---

## Config Classes Inventory

The accepted providers (`AcceptedModules`, matching the module SPI entries on the classpath — see
`provider-inventory.properties` and `ApplicationYmlModulesTest`). Providers whose `newConfigCreator()` returns null
need no coverage.

### Provider Config Classes

The full list is `module-config-classes.txt`. A field its class has no setter for is written through `Field.set`
(`module-config-field-set.txt` lists them); the others through upstream's own `@Setter` (or `@Data`).

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

**`instanceof` ordering**: the dispatch checks a subclass before its superclass (deeper classes first, then by name),
so `RecordsNormal` reaches its own branch before `GroupResource`'s. Every nested class of a config class gets a branch,
including those `BanyanDBConfigLoader` does not copy into (`RecordsTrace`, `RecordsZipkinTrace`, `TopN`, ...).

---

## Same-FQCN Replacements (Config Initialization)

| Upstream Class | Upstream Location | Replacement Location | What Changed |
|---|---|---|---|
| `YamlConfigLoaderUtils` | `server-library/library-util/.../util/YamlConfigLoaderUtils.java` | `oap-graalvm-server/` (not in library-util-for-graalvm due to 30+ cross-module imports) | Complete rewrite. Type-dispatch with setter calls, and `Field.set` for a field no setter writes, instead of a reflective walk over every field. |

No config class is replaced. `YamlConfigLoaderUtils` lives in `oap-graalvm-server` because it imports types from 30+ modules; the original `.class` is excluded from `library-util-for-graalvm` via shade filter.

## Same-FQCN Packaging

The original `YamlConfigLoaderUtils.class` is excluded from the `library-util-for-graalvm` shaded JAR. The replacement in `oap-graalvm-server.jar` is the only copy on the classpath.

---

## Verification

```bash
# Run generator
./mvnw -pl build-tools/config-generator exec:java \
  -Dexec.args="oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt"

# Full build (compile + test + package)
make build-distro
```
