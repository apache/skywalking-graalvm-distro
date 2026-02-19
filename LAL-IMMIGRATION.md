# Phase 2: LAL Build-Time Pre-Compilation — COMPLETE

## Context

LAL (Log Analysis Language) uses Groovy with `@CompileStatic` + `LALPrecompiledExtension` for log analysis scripts. At startup, `GroovyShell.parse()` compiles each LAL DSL script into a `LALDelegatingScript`. GraalVM native image cannot compile Groovy at runtime.

LAL also enforces security constraints via `SecureASTCustomizer` — `while`, `do-while`, and `for` loops are disallowed. Branch coverage focuses on `if`/`else if`/`else` chains.

**Solution**: Compile all LAL scripts at build time via the unified precompiler. Export `.class` files + manifests. At runtime, load pre-compiled classes via SHA-256 hash lookup — no Groovy compilation.

---

## Rule File Inventory

**8 LAL YAML files, 10 rules, 6 unique pre-compiled classes:**

| File | Rule Name | DSL Features |
|---|---|---|
| `default.yaml` | default | Empty sink (trivial passthrough) |
| `nginx.yaml` | nginx-access-log | `tag()` guard, `text { regexp }`, conditional tag extraction |
| `nginx.yaml` | nginx-error-log | `tag()` guard, `text { regexp }`, timestamp with format, `metrics {}` |
| `mysql-slowsql.yaml` | mysql-slowsql | `json {}`, conditional `slowSql {}` |
| `pgsql-slowsql.yaml` | pgsql-slowsql | Identical DSL to mysql-slowsql |
| `redis-slowsql.yaml` | redis-slowsql | Identical DSL to mysql-slowsql |
| `envoy-als.yaml` | envoy-als | `parsed?.` navigation, conditional `abort {}`, tag extraction, `rateLimit` sampler |
| `envoy-als.yaml` | network-profiling-slow-trace | `json {}`, `tag()` guard, `sampledTrace {}` with 3-way if/else chains |
| `mesh-dp.yaml` | network-profiling-slow-trace | Identical DSL to envoy-als's 2nd rule |
| `k8s-service.yaml` | network-profiling-slow-trace | Identical DSL to envoy-als's 2nd rule |

**SHA-256 deduplication**: mysql/pgsql/redis share identical DSL (1 class). The 3 network-profiling rules share identical DSL (1 class). Total unique pre-compiled classes: **6**.

---

## Build-Time Compilation

The unified precompiler (`build-tools/precompiler`) handles LAL alongside OAL and MAL:

1. Loads all 8 LAL YAML files via `LALConfigs.load()`
2. For each rule's DSL string, compiles with the same `CompilerConfiguration` as upstream:
   - `@CompileStatic` with `LALPrecompiledExtension` for type checking
   - `SecureASTCustomizer` disallowing loops
   - `ImportCustomizer` for `ProcessRegistry`
   - Script base class: `LALDelegatingScript`
3. Exports compiled `.class` files to the output directory
4. Writes two manifest files:

**`META-INF/lal-scripts-by-hash.txt`** — SHA-256 hash → class name:
```
a1b2c3d4...=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0001
e5f6a7b8...=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0002
...
```

**`META-INF/lal-scripts.txt`** — rule name → class name:
```
default=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0001
nginx-access-log=org.apache.skywalking.oap.server.core.analysis.lal.script.LALScript0002
...
```

The hash-based manifest enables runtime lookup by DSL content (independent of rule naming), while the name-based manifest enables verification that all expected rules are covered.

---

## Runtime Replacement

**Same-FQCN class**: `oap-graalvm-server/.../log/analyzer/dsl/DSL.java`

Same FQCN as upstream `org.apache.skywalking.oap.log.analyzer.dsl.DSL`. The `of()` method:

1. Computes SHA-256 of the DSL string
2. Loads `META-INF/lal-scripts-by-hash.txt` manifest (lazy, thread-safe, cached)
3. Looks up the pre-compiled class name by hash
4. `Class.forName(className)` → `newInstance()` → cast to `DelegatingScript`
5. Creates `FilterSpec`, sets delegate, returns `DSL` instance

No `GroovyShell`, no compilation. The pre-compiled class already contains the statically-compiled bytecode with all type checking baked in.

---

## Key Difference from MAL

| Aspect | MAL | LAL |
|---|---|---|
| Groovy mode | Dynamic (MOP, `propertyMissing`, `ExpandoMetaClass`) | `@CompileStatic` with extension |
| Loop support | No restriction | Loops disallowed (`SecureASTCustomizer`) |
| Script base class | `DelegatingScript` | `LALDelegatingScript` |
| Manifest lookup | By metric name | By SHA-256 hash of DSL content |
| GraalVM risk | High (dynamic Groovy MOP) | Low (statically compiled) |

LAL's `@CompileStatic` compilation means the pre-compiled classes are fully statically typed — no runtime metaclass manipulation needed. This makes LAL significantly more native-image-friendly than MAL.

---

## Comparison Test Suite

**5 test classes, 19 assertions** covering all 8 YAML files and 10 rules.

Each test runs both paths and asserts identical `Binding` state:

```
              LAL YAML file
                   |
             Load rules (name, dsl)
                   |
            For each rule's DSL:
                 /            \
      Path A (Fresh Groovy)  Path B (Pre-compiled)
      GroovyShell.parse()    SHA-256 → manifest lookup
      same CompilerConfig    Class.forName() → newInstance()
               \                /
         Create FilterSpec (mocked ModuleManager)
         script.setDelegate(filterSpec)
         filterSpec.bind(binding)
         script.run()
               \                /
            Assert identical Binding state
```

**What is compared after evaluation:**
- `binding.shouldAbort()` — did `abort {}` fire?
- `binding.shouldSave()` — log persistence flag
- `binding.log()` — LogData.Builder state (service, serviceInstance, endpoint, layer, timestamp, tags)
- `binding.metricsContainer()` — SampleFamily objects (for nginx-error-log `metrics {}`)
- `binding.databaseSlowStatement()` — builder state (for slowSql rules)
- `binding.sampledTraceBuilder()` — builder state (for network-profiling sampledTrace)

### Test Classes

| Test Class | YAML File(s) | Tests | Coverage |
|---|---|---|---|
| `LALDefaultTest` | default.yaml | 2 | Trivial passthrough + manifest verification |
| `LALNginxTest` | nginx.yaml | 5 | Access log: matching/non-matching tag + non-matching regex. Error log: matching/non-matching tag |
| `LALSlowSqlTest` | mysql/pgsql/redis-slowsql.yaml | 3 | SLOW_SQL tag guard (match/skip) + 3-file manifest verification |
| `LALEnvoyAlsTest` | envoy-als.yaml (1st rule) | 3 | Abort path (low code, no flags), non-abort with flags, non-abort with high code |
| `LALNetworkProfilingTest` | envoy-als/mesh-dp/k8s-service.yaml | 6 | 4 componentId branches (http/tcp x ssl/no-ssl), LOG_KIND guard, 3-file manifest verification |

### Branch Coverage

- **`tag()` guard**: All rules with `if (tag("LOG_KIND") == ...)` tested with matching and non-matching values
- **`abort {}`**: envoy-als tested with conditions that trigger and skip abort
- **`slowSql {}`**: Tested with SLOW_SQL tag match (block executed) and non-match (block skipped)
- **`sampledTrace {}`**: componentId 4-way if/else chain fully covered (HTTP=49, HTTPS=129, TLS=130, TCP=110)
- **`text { regexp }`**: nginx access log tested with matching and non-matching text patterns
- **`json {}`**: Tested via slowSql and network-profiling rules
- **`rateLimit` sampler**: envoy-als tested with responseFlags present/absent (if/else branch)
- **`parsed?.` navigation**: envoy-als tested with nested Map traversal

---

## Files Created

1. **`oap-graalvm-server/src/main/java/.../log/analyzer/dsl/DSL.java`**
   Same-FQCN replacement: loads pre-compiled LAL scripts from manifest via SHA-256 hash

2. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALScriptComparisonBase.java`**
   Abstract base class: ModuleManager mock setup, dual-path compilation, Binding state comparison

3. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALDefaultTest.java`**
   Tests for default.yaml (2 tests)

4. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALNginxTest.java`**
   Tests for nginx.yaml access-log and error-log rules (5 tests)

5. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALSlowSqlTest.java`**
   Tests for mysql/pgsql/redis-slowsql.yaml (3 tests)

6. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALEnvoyAlsTest.java`**
   Tests for envoy-als.yaml envoy-als rule (3 tests)

7. **`oap-graalvm-server/src/test/java/.../graalvm/lal/LALNetworkProfilingTest.java`**
   Tests for network-profiling-slow-trace rule across 3 files (6 tests)

---

## Verification

```bash
# Build precompiler first (generates LAL classes + manifests)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl build-tools/precompiler install -DskipTests

# Run LAL tests only
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal \
  mvn -pl oap-graalvm-server test \
  -Dtest="org.apache.skywalking.oap.server.graalvm.lal.*"

# Full build (all tests)
JAVA_HOME=/Users/wusheng/.sdkman/candidates/java/25-graal make build-distro
```

Expected: 19 comparison tests across 5 test classes, all passing.
