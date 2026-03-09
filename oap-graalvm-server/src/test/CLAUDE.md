# OAP GraalVM Server — AI Guide

## MAL Comparison Tests (V2 Engine)

MAL comparison tests verify that pre-compiled v2 classes produce identical
results to freshly-compiled v2 classes.

### Verification workflow

Each metric expression is run through two independent paths:

```
                   MAL YAML file
                        |
                   Load Rule + MetricsRules
                        |
               Compose full expression
                   (formatExp)
                   /            \
          Path A (Fresh)      Path B (Pre-compiled)
          MALClassGenerator    Class.forName() from
          .compile() in-memory  manifest properties
                \                /
           new Expression(name, exp, malExpr)
                \                /
              run(sampleFamilyInput)
                \                /
               Assert identical Result
            (success, values, labels)
```

**Path A (Fresh)**: Compiles the expression at test time via `MALClassGenerator`
(ANTLR4 + Javassist) — same engine used at build time.

**Path B (Pre-compiled)**: Loads the `.class` file from per-file configs under
`META-INF/mal-v2/` (mirroring original YAML directory structure) via
`Class.forName()` using expression text as lookup key, then wires closure
fields (TagFunction, ForEachFunction, PropertiesExtractor, DecorateFunction)
via `LambdaMetafactory`.

If both paths produce identical `Result` (same success flag, same sample values
within 0.001 tolerance, same labels), then the build-time compilation is
functionally equivalent to runtime compilation.

### When to generate a new test

Each MAL YAML file in `skywalking/oap-server/server-starter/src/main/resources/`
(under `meter-analyzer-config/`, `otel-rules/`, `log-mal-rules/`,
`envoy-metrics-rules/`, `telegraf-rules/`, or `zabbix-rules/`) should have
a corresponding test class in
`src/test/java/org/apache/skywalking/oap/server/graalvm/mal/`.

### Step-by-step process

#### 1. Read the target MAL YAML file

Note these fields:
- `metricPrefix` — prefix for all metric names
- `expSuffix` — expression suffix applied to all metrics (scope, tag closures)
- `expPrefix` — expression prefix (rare, e.g. `forEach(...)`)
- `metricsRules` — list of `{name, exp}` pairs

#### 2. Choose the right mode

- **No tag filters or histograms** → use `generateComparisonTests(yamlPath)` (auto-discovery)
- **Has tag filters or histograms** → use `generateComparisonTests(yamlPath, input1, input2)` (explicit input)

### Template (auto-discovery)

```java
package org.apache.skywalking.oap.server.graalvm.mal;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class NewFileTest extends MALScriptComparisonBase {
    @TestFactory
    Stream<DynamicTest> allMetrics() {
        return generateComparisonTests("meter-analyzer-config/xxx.yaml");
    }
}
```

### Template (explicit input)

```java
package org.apache.skywalking.oap.server.graalvm.mal;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.stream.Stream;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class NewFileTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/xxx/xxx.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @TestFactory
    Stream<DynamicTest> allMetrics() {
        return generateComparisonTests(YAML_PATH,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "scope_key1", "value1");

        return ImmutableMap.<String, SampleFamily>builder()
            .put("sample_name", SampleFamilyBuilder.newBuilder(
                Sample.builder()
                    .name("sample_name")
                    .labels(scope)
                    .value(100.0 * scale)
                    .timestamp(timestamp)
                    .build()
            ).build())
            .build();
    }
}
```

### Reference files

- `MALScriptComparisonBase.java` — base class with auto-discovery and explicit input modes (v2 engine)
- `PrecompiledYamlStalenessTest.java` — SHA-256 staleness detector
- `precompiled-yaml-sha256.properties` — recorded SHA-256 hashes of tracked YAML files
- `SpringMicrometerTest.java` — example of auto-discovery mode
- `MysqlInstanceTest.java` — example of explicit input mode (tagEqual, tagMatch, multi-sample)
- `ZabbixAgentTest.java` — example of custom YAML loading (zabbix `metrics:` format)
- MAL YAML files: `skywalking/oap-server/server-starter/src/main/resources/`
