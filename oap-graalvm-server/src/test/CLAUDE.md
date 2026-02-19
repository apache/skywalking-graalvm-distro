# OAP GraalVM Server — AI Guide

## Generating MAL Comparison Tests

MAL comparison tests verify that pre-compiled Groovy classes produce identical
results to freshly-compiled Groovy scripts.

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
          Path A (Groovy)    Path B (Pre-compiled)
          GroovyShell with    Class.forName() from
          same DSL.parse()    manifest, instantiate
          CompilerConfig      DelegatingScript
                \                /
           new Expression(name, exp, script)
                \                /
              run(sampleFamilyInput)
                \                /
               Assert identical Result
            (success, values, labels)
```

**Path A (Groovy)**: Compiles the expression fresh via `GroovyShell` using the
exact same `CompilerConfiguration` that upstream `DSL.parse()` uses at runtime.

**Path B (Pre-compiled)**: Loads the `.class` file generated at build time from
the manifest via `Class.forName()`, instantiates the `DelegatingScript`.

If both paths produce identical `Result` (same success flag, same sample values
within 0.001 tolerance, same labels), then the build-time compilation is
functionally equivalent to runtime compilation.

**CounterWindow isolation**: `CounterWindow.INSTANCE` is a singleton keyed by
`(metricName, labels)`. Path A uses `metricName + "_groovy"` as its Expression
name to prevent shared state with Path B.

### When to generate a new test

Each MAL YAML file in `skywalking/oap-server/server-starter/src/main/resources/`
(under `meter-analyzer-config/`, `otel-rules/`, `log-mal-rules/`,
`envoy-metrics-rules/`, `telegraf-rules/`, or `zabbix-rules/`) should have
a corresponding test class in
`src/test/java/org/apache/skywalking/oap/server/graalvm/mal/`.

All 71 YAML files are currently covered (1281 test assertions). For zabbix rules
which use `metrics:` instead of `metricsRules:`, use `loadZabbixRule()` and the
`generateComparisonTests(Rule, input1, input2)` overload.

### Step-by-step process

#### 1. Read the target MAL YAML file

Note these fields:
- `metricPrefix` — prefix for all metric names (e.g. `meter_mysql`)
- `expSuffix` — expression suffix applied to all metrics (scope, tag closures)
- `expPrefix` — expression prefix (rare, e.g. `forEach(...)` closures)
- `filter` — YAML-level filter (see note below)
- `metricsRules` — list of `{name, exp}` pairs

**File-level `filter`**: The `filter` field (e.g.
`{ tags -> tags.job_name == 'mysql-monitoring' }`) is NOT part of the
pre-compiled metric expression. It's compiled separately in `FilterExpression`
using a plain `GroovyShell` and applied at the `Analyzer.analyse()` level —
above our `Expression.run()` test path. Since the filter is never pre-compiled
at build time, there is no Path A vs Path B divergence to verify. **Do not
include filter-specific labels** (e.g. `job_name`) in test input data.

#### 2. Collect all unique sample names

Scan every `exp` field. Sample names are the identifiers at the start of each
expression segment (before the first `.`). For multi-sample expressions like
`a.sum([...]) - b.sum([...])`, both `a` and `b` are sample names.

#### 3. Identify scope labels from expSuffix/expPrefix

Look at the scope functions in `expSuffix`/`expPrefix`:
- `.service(['host_name'], Layer.MYSQL)` → consumes `host_name`
- `.instance(['service'], ['instance'], Layer.GENERAL)` → consumes `service`, `instance`
- `.instance(['host_name'], ['service_instance_id'], Layer.MYSQL)` → consumes both
- `.endpoint(['service'], ['endpoint'], Layer.GENERAL)` → consumes `service`, `endpoint`

**Critical: trace labels back through `.tag()` closures.** The scope functions
consume labels from the sample AFTER any `.tag()` transformations. You must
determine whether each scope label exists in the **original metric** or is
**built by the script** from other labels.

Three patterns:

| Pattern | Example | Input needs | Script does |
|---|---|---|---|
| Label used as-is | `.instance(['service'], ['instance'])` | `service`, `instance` | Nothing |
| Label modified in-place | `.tag({tags -> tags.host_name = 'mysql::' + tags.host_name}).service(['host_name'])` | `host_name` (original value) | Transforms it |
| Label built from another | `.tag({tags -> tags.host_name = 'aws-dynamodb::' + tags.cloud_account_id}).service(['host_name'])` | `cloud_account_id` (NOT `host_name`) | Creates `host_name` |

**How to analyze**: Read the `.tag()` closure left-to-right:
1. **LHS** of assignment (`tags.host_name = ...`) — the label being written
2. **RHS** references (`tags.cloud_account_id`) — the source labels needed in input

If the LHS label is the same as an RHS reference (e.g. `tags.host_name = 'mysql::' + tags.host_name`),
the label exists in the original metric and is modified. Include it in input
with the **pre-transformation** value.

If the LHS label is NOT referenced on the RHS (e.g. `tags.host_name = 'aws-dynamodb::' + tags.cloud_account_id`),
the label is **built from scratch**. Include only the source label(s) in input.
The script will create the new label.

#### 4. Scan expressions for tag filter constraints

For each expression, extract the arguments from:

| Function | What to extract | Sample data needed |
|---|---|---|
| `.tagEqual('key','value')` | key, value | Sample with `key=value` |
| `.tagMatch('key','regex')` | key, regex | Sample with `key` matching regex |
| `.tagNotEqual('key','value')` | key, value | Sample with `key` != `value` |
| `.filter({tags -> tags.x == 'y'})` | condition | Sample satisfying condition |
| `.valueEqual(N)` | N | Sample with value == N |

If the same sample name is used with different tag filter values across metrics
(e.g. `commands_total.tagEqual('command','insert')` and
`commands_total.tagEqual('command','select')`), create **multiple Sample objects
in one SampleFamily** covering all required values.

#### 5. Choose the right mode

- **No tag filters or histograms** → use `generateComparisonTests(yamlPath)` (auto-discovery)
- **Has tag filters or histograms** → use `generateComparisonTests(yamlPath, input1, input2)` (explicit input)

**Why auto-discovery is not enough for tag filters**:
`ExpressionParsingContext.parse()` discovers label **names** but not the
**values** expected by tag filters. For `tagEqual('command','insert')`,
auto-discovery produces `command="test-command"` which doesn't match `"insert"`,
so the sample gets filtered out. Both paths produce empty results and "agree" —
but nothing was actually tested.

#### 6. Write the buildInput method

For explicit input mode, create a `buildInput(double base, long timestamp)`
method that:
- Takes `base` value and `timestamp` so input1 and input2 differ
- Scales values relative to `base` (base=100 for TS1, base=200 for TS2)
- Creates all sample families with correct tag values
- Uses only **original metric labels** — labels that exist in the real metric
  data before the expression runs. Do NOT include labels that the script builds
  via `.tag()` closures (the script will create them). Do NOT include
  filter-specific labels (e.g. `job_name`) since the filter is not exercised.
- Documents the data as Javadoc in Prometheus text format

#### 7. Handle multi-sample expressions

For `a / b`, `a - b`, `a + b` expressions:
- Both `a` and `b` must be present in the input map
- Both must have compatible labels (same aggregation label values)
- For division, ensure denominator is non-zero

#### 8. Handle histogram expressions

For `.histogram().histogram_percentile([50,75,90,95,99])`:
- Samples need a `le` label with numeric bucket boundaries
- Values should be cumulative (each bucket >= previous bucket)
- Use the base class `histogramSamples()` helper:

```java
// In buildInput:
.put("metric_name", histogramSamples("metric_name", scope, scale, timestamp))
```

The helper creates 12 buckets (`0.005` through `+Inf`) with cumulative values.
If the expression also has `.sum(['le', ...])` aggregation, the scope labels
must be included alongside the `le` label.

### Template (auto-discovery)

```java
package org.apache.skywalking.oap.server.graalvm.mal;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class NewFileTest extends MALScriptComparisonBase {
    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
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
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class NewFileTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/xxx/xxx.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
        return generateComparisonTests(YAML_PATH,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    /**
     * <pre>
     * # Document all sample data here in Prometheus text format
     * sample_name{label="value",scope_label="scope_value"} 100.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "scope_key1", "value1",
            "scope_key2", "value2");

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

### Staleness detection (SHA-256 tracking)

`MALYamlStalenessTest` compares the SHA-256 of each tracked MAL YAML file
against the recorded hash in `src/test/resources/mal-yaml-sha256.properties`.

**AI workflow — before starting MAL test work:**

1. Run the staleness test first:
   ```bash
   JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
     mvn -pl oap-graalvm-server test \
     -Dtest="org.apache.skywalking.oap.server.graalvm.mal.MALYamlStalenessTest"
   ```
2. If it **passes** — all test files are up-to-date, no regeneration needed.
3. If it **fails** — the output lists which YAML files changed. For each:
   - Re-read the changed YAML file to understand the new expressions.
   - Regenerate the corresponding test class with updated mock input.
   - Update the SHA-256 in `mal-yaml-sha256.properties` by running:
     ```bash
     shasum -a 256 skywalking/oap-server/server-starter/src/main/resources/<path>
     ```
   - Update `MAL-COVERAGE.md` if expression counts changed.

**This is automatic** — after initial test generation, the user does not need
to explicitly request regeneration. The staleness test catches drift after
`skywalking/` submodule updates. New YAML files not yet in the properties file
also need detection; check for untracked files via:
```bash
diff <(sort mal-yaml-sha256.properties | grep -v '^#' | cut -d= -f1) \
     <(find skywalking/.../resources/{meter-analyzer-config,otel-rules,log-mal-rules} \
       -name "*.yaml" | sed 's|.*/resources/||' | sort)
```

### Running tests

```bash
# Install precompiler first (needed for pre-compiled classes on classpath)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl build-tools/precompiler install -DskipTests

# Run staleness check (fast — no Groovy compilation)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl oap-graalvm-server test \
  -Dtest="org.apache.skywalking.oap.server.graalvm.mal.MALYamlStalenessTest"

# Run specific test
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl oap-graalvm-server test \
  -Dtest="org.apache.skywalking.oap.server.graalvm.mal.NewFileTest"

# Full build (runs all tests)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro
```

### Reference files

- `MALScriptComparisonBase.java` — base class with both auto-discovery and explicit input modes
- `MALYamlStalenessTest.java` — SHA-256 staleness detector
- `mal-yaml-sha256.properties` — recorded SHA-256 hashes of all tracked YAML files
- `SpringMicrometerTest.java` — example of auto-discovery mode (no tag filters)
- `MysqlInstanceTest.java` — example of explicit input mode (tagEqual, tagMatch, multi-sample)
- `K8sClusterTest.java` — example of K8s retagByK8sMeta mocking
- `ZabbixAgentTest.java` — example of custom YAML loading (zabbix `metrics:` format)
- `NetworkProfilingTest.java` — example of complex expPrefix with forEach closures
- `EnvoyTest.java` — example of tagMatch with regex patterns
- MAL YAML files: `skywalking/oap-server/server-starter/src/main/resources/`
- `MAL-COVERAGE.md` — tracks which files are covered, expression counts, test mode
