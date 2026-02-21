# Phase 3: LAL-to-Java Transpiler Design

## Context

MAL transpilation is complete (1250+ expressions, 29 filters → pure Java). LAL is the
**only remaining Groovy dependency** blocking native-image. There are 10 LAL scripts
(6 unique after SHA-256 dedup) across 8 YAML files.

**Goal**: Eliminate Groovy from runtime. Generate pure Java classes at build time
implementing `LalExpression`. Groovy is still used at build time (for AST parsing)
and in tests (dual-path comparison) but NOT at runtime.

**GraalVM error solved**: `GroovyIndyInterfaceFeature` is triggered by
`org.codehaus.groovy.vmplugin.v7.IndyInterface` on classpath. Minimal `groovy.lang.*`
stubs (no `org.codehaus.groovy.*`) prevent the feature from activating.

---

## Architecture

```
LalToJavaTranspiler
├── transpile(className, dslString)      → generates LalExpr_<N>.java
├── writeManifest()                      → lal-expressions.txt (hash=FQCN)
├── compileAll()                         → javax.tools.JavaCompiler on generated .java files
│
├── (internal) parseToAST(dslString)     → Groovy CompilationUnit → ModuleNode
├── (internal) visitStatement(Statement) → Java code fragment
├── (internal) visitMethodCall(node)     → spec method calls with Consumer lambdas
├── (internal) visitClosure(node)        → Java lambda or Runnable
├── (internal) visitIf(IfStatement)      → Java if/else chain
├── (internal) visitCondition(Expression)→ boolean expression
├── (internal) visitParsedAccess(node)   → binding.parsed().getAt("key")
├── (internal) visitSafeNav(node)        → null-check chains for ?. operator
├── (internal) visitGString(node)        → String concatenation
└── (internal) visitCast(node)           → type cast (as String, as Long, etc.)
```

---

## LalExpression Interface

```java
@FunctionalInterface
public interface LalExpression {
    void execute(FilterSpec filterSpec, Binding binding);
}
```

Each generated LAL class implements this interface. `FilterSpec` provides spec methods
(json, text, extractor, sink, tag, abort). `Binding` provides parsed data, log builder,
and state flags.

---

## LAL Script Inventory (6 Unique DSL Bodies)

### DSL #1: default (1 YAML)
```groovy
filter {
  sink {
  }
}
```
**Constructs**: filter, sink (empty)

### DSL #2: nginx-access-log (1 YAML)
```groovy
filter {
  if (tag("LOG_KIND") == "NGINX_ACCESS_LOG") {
    text {
      regexp $/.+ \"(?<request>.+)\" (?<status>\d{3}) .+/$
    }
    extractor {
      if (parsed.status) {
        tag 'http.status_code': parsed.status
      }
    }
    sink {
    }
  }
}
```
**Constructs**: filter, if/tag==, text/regexp (dollar-slashy), extractor/tag(Map),
parsed.field truthiness, sink

### DSL #3: nginx-error-log (1 YAML)
```groovy
filter {
  if (tag("LOG_KIND") == "NGINX_ERROR_LOG") {
    text {
      regexp $/(?<time>\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) \[(?<level>.+)].*/$
    }
    extractor {
      tag level: parsed.level
      timestamp parsed.time as String, "yyyy/MM/dd HH:mm:ss"
      metrics {
        timestamp log.timestamp as Long
        labels level: parsed.level, service: log.service, service_instance_id: log.serviceInstance
        name "nginx_error_log_count"
        value 1
      }
    }
    sink {
    }
  }
}
```
**Constructs**: text/regexp, extractor/tag(Map), timestamp(String,pattern),
metrics block (timestamp, labels, name, value), log.timestamp/log.service access

### DSL #4: slow-sql (3 YAMLs — mysql/pgsql/redis, identical DSL)
```groovy
filter {
  json{
  }
  extractor{
    layer parsed.layer as String
    service parsed.service as String
    timestamp parsed.time as String
    if (tag("LOG_KIND") == "SLOW_SQL") {
       slowSql {
         id parsed.id as String
         statement parsed.statement as String
         latency parsed.query_time as Long
       }
    }
  }
}
```
**Constructs**: json (empty), extractor/layer/service/timestamp, parsed.field as Type,
if/tag==, slowSql block (id, statement, latency)

### DSL #5: envoy-als (1 YAML)
```groovy
filter {
  if (parsed?.response?.responseCode?.value as Integer < 400
      && !parsed?.commonProperties?.responseFlags?.toString()?.trim()) {
    abort {}
  }
  extractor {
    if (parsed?.response?.responseCode) {
      tag 'status.code': parsed?.response?.responseCode?.value
    }
    tag 'response.flag': parsed?.commonProperties?.responseFlags
  }
  sink {
    sampler {
      if (parsed?.commonProperties?.responseFlags?.toString()) {
        rateLimit("${log.service}:${parsed?.commonProperties?.responseFlags?.toString()}") {
          rpm 6000
        }
      } else {
        rateLimit("${log.service}:${parsed?.response?.responseCode}") {
          rpm 6000
        }
      }
    }
  }
}
```
**Constructs**: safe navigation (?.), as Integer, compound boolean (&&, !),
abort, extractor/tag(Map), sink/sampler/rateLimit, GString interpolation (${...}),
toString(), trim()

### DSL #6: network-profiling-slow-trace (3 YAMLs — envoy-als/k8s-service/mesh-dp, identical DSL)
```groovy
filter {
  json{
  }
  extractor{
    if (tag("LOG_KIND") == "NET_PROFILING_SAMPLED_TRACE") {
      sampledTrace {
        latency parsed.latency as Long
        uri parsed.uri as String
        reason parsed.reason as String
        if (parsed.client_process.process_id as String != "") {
          processId parsed.client_process.process_id as String
        } else if (parsed.client_process.local as Boolean) {
          processId ProcessRegistry.generateVirtualLocalProcess(...) as String
        } else {
          processId ProcessRegistry.generateVirtualRemoteProcess(...) as String
        }
        // destProcessId: same 3-way pattern with server_process
        detectPoint parsed.detect_point as String
        if (parsed.component as String == "http" && parsed.ssl as Boolean) {
          componentId 129      // HTTPS
        } else if (parsed.component as String == "http") {
          componentId 49       // HTTP
        } else if (parsed.ssl as Boolean) {
          componentId 130      // TLS
        } else {
          componentId 110      // TCP
        }
      }
    }
  }
}
```
**Constructs**: json, extractor/if/tag==, sampledTrace block, parsed.nested.field,
as String/Long/Boolean, 3-way if/else-if/else (processId), 4-way if chain (componentId),
ProcessRegistry static methods, nested Map access (client_process.process_id)

---

## AST Node → Java Mapping

### Spec Method Calls

| Groovy Pattern | Java Output |
|---------------|-------------|
| `filter { ... }` | `/* top-level body → LalExpression.execute() */` |
| `json {}` | `filterSpec.json()` (no-arg overload) |
| `text { regexp /pat/ }` | `filterSpec.text(tp -> tp.regexp("pat"))` |
| `extractor { ... }` | `filterSpec.extractor(ext -> { ... })` |
| `sink { ... }` | `filterSpec.sink(s -> { ... })` |
| `slowSql { ... }` | `ext.slowSql(sql -> { ... })` |
| `sampledTrace { ... }` | `ext.sampledTrace(st -> { ... })` |
| `metrics { ... }` | `ext.metrics(mb -> { ... })` |
| `sampler { ... }` | `s.sampler(sp -> { ... })` |
| `rateLimit("${id}") { rpm N }` | `sp.rateLimit(idExpr, rls -> rls.rpm(N))` |
| `abort {}` | `filterSpec.abort()` |

### Variable & Property Access

| Groovy Pattern | Java Output |
|---------------|-------------|
| `tag("KEY")` | `filterSpec.tag("KEY")` |
| `parsed.field` | `binding.parsed().getAt("field")` |
| `parsed.a.b` (nested) | `((Map) binding.parsed().getAt("a")).get("b")` |
| `parsed?.a?.b?.c` | null-safe chain with temp variables |
| `log.service` | `binding.log().getService()` |
| `log.serviceInstance` | `binding.log().getServiceInstance()` |
| `log.timestamp` | `binding.log().getTimestamp()` |

### Type Casting

| Groovy Pattern | Java Output |
|---------------|-------------|
| `expr as String` | `String.valueOf(expr)` |
| `expr as Long` | `((Number) expr).longValue()` |
| `expr as Integer` | `((Number) expr).intValue()` |
| `expr as Boolean` | `(Boolean) expr` |

### Conditions & Operators

| Groovy Pattern | Java Output |
|---------------|-------------|
| `tag("K") == "V"` | `"V".equals(filterSpec.tag("K"))` |
| `a == "b"` | `"b".equals(a)` |
| `a != ""` | `!"".equals(a)` |
| `a && b` | `a && b` |
| `!expr` | `!expr` |
| `expr < 400` | `expr < 400` |
| `if (...) { ... }` | `if (...) { ... }` |
| `if (...) { ... } else if (...) { ... } else { ... }` | Same in Java |
| `parsed.status` (truthiness) | `binding.parsed().getAt("status") != null` |

### String Interpolation

| Groovy Pattern | Java Output |
|---------------|-------------|
| `"${log.service}:${x}"` | `binding.log().getService() + ":" + String.valueOf(x)` |
| `parsed?.a?.toString()` | safe-nav chain → `String.valueOf(...)` |

### Safe Navigation (?.)

```groovy
parsed?.response?.responseCode?.value
```
→
```java
Object _t0 = binding.parsed().getAt("response");
Object _t1 = _t0 != null ? ((Map<String, Object>) _t0).get("responseCode") : null;
Object _t2 = _t1 != null ? ((Map<String, Object>) _t1).get("value") : null;
```

### Named-Argument Method Calls (tag/labels)

| Groovy Pattern | Java Output |
|---------------|-------------|
| `tag 'k': v` | `ext.tag(Map.of("k", v))` |
| `tag level: p.level` | `ext.tag(Map.of("level", p.getAt("level")))` |
| `labels a: x, b: y` | `mb.labels(Map.of("a", x, "b", y))` |

---

## Generated Code Examples

### DSL #1: default
```java
public class LalExpr_0 implements LalExpression {
    @Override
    public void execute(FilterSpec filterSpec, Binding binding) {
        filterSpec.sink(sink -> {});
    }
}
```

### DSL #4: slow-sql
```java
public class LalExpr_3 implements LalExpression {
    @Override
    public void execute(FilterSpec filterSpec, Binding binding) {
        filterSpec.json();
        filterSpec.extractor(ext -> {
            Binding.Parsed parsed = binding.parsed();
            ext.layer(String.valueOf(parsed.getAt("layer")));
            ext.service(String.valueOf(parsed.getAt("service")));
            ext.timestamp(String.valueOf(parsed.getAt("time")));
            if ("SLOW_SQL".equals(filterSpec.tag("LOG_KIND"))) {
                ext.slowSql(sql -> {
                    sql.id(String.valueOf(parsed.getAt("id")));
                    sql.statement(String.valueOf(parsed.getAt("statement")));
                    sql.latency(((Number) parsed.getAt("query_time")).longValue());
                });
            }
        });
    }
}
```

### DSL #6: network-profiling-slow-trace (simplified)
```java
public class LalExpr_5 implements LalExpression {
    @Override
    public void execute(FilterSpec filterSpec, Binding binding) {
        filterSpec.json();
        filterSpec.extractor(ext -> {
            if ("NET_PROFILING_SAMPLED_TRACE".equals(filterSpec.tag("LOG_KIND"))) {
                Binding.Parsed parsed = binding.parsed();
                ext.sampledTrace(st -> {
                    st.latency(((Number) parsed.getAt("latency")).longValue());
                    st.uri(String.valueOf(parsed.getAt("uri")));
                    st.reason(String.valueOf(parsed.getAt("reason")));

                    // processId: 3-way branch
                    Map<String, Object> clientProcess = (Map<String, Object>) parsed.getAt("client_process");
                    String clientPid = String.valueOf(clientProcess.get("process_id"));
                    if (!"".equals(clientPid)) {
                        st.processId(clientPid);
                    } else if (Boolean.TRUE.equals(clientProcess.get("local"))) {
                        st.processId(String.valueOf(ProcessRegistry.generateVirtualLocalProcess(
                            String.valueOf(parsed.getAt("service")),
                            String.valueOf(parsed.getAt("serviceInstance")))));
                    } else {
                        st.processId(String.valueOf(ProcessRegistry.generateVirtualRemoteProcess(
                            String.valueOf(parsed.getAt("service")),
                            String.valueOf(parsed.getAt("serviceInstance")),
                            String.valueOf(clientProcess.get("address")))));
                    }

                    // destProcessId: same pattern with server_process
                    // ...

                    st.detectPoint(String.valueOf(parsed.getAt("detect_point")));

                    // componentId: 4-way branch
                    String component = String.valueOf(parsed.getAt("component"));
                    boolean ssl = Boolean.TRUE.equals(parsed.getAt("ssl"));
                    if ("http".equals(component) && ssl) {
                        st.componentId(129);
                    } else if ("http".equals(component)) {
                        st.componentId(49);
                    } else if (ssl) {
                        st.componentId(130);
                    } else {
                        st.componentId(110);
                    }
                });
            }
        });
    }
}
```

---

## Same-FQCN Spec Replacements

Spec classes need Consumer overloads alongside Closure methods:

| Spec Class | Added Overloads |
|-----------|-----------------|
| AbstractSpec | `abort()` no-arg |
| FilterSpec | `json()`, `text(Consumer<TextParserSpec>)`, `extractor(Consumer<ExtractorSpec>)`, `sink(Consumer<SinkSpec>)`, `filter(Runnable)` |
| ExtractorSpec | `slowSql(Consumer<SlowSqlSpec>)`, `sampledTrace(Consumer<SampledTraceSpec>)`, `metrics(Consumer<SampleBuilder>)` |
| SinkSpec | `sampler(Consumer<SamplerSpec>)` |
| SamplerSpec | `rateLimit(String, Consumer<?>)` |

Each overload replicates the Closure version's logic:
```java
// Closure version:
public void extractor(Closure<?> cl) {
    if (shouldAbort()) return;
    cl.setDelegate(extractor);
    cl.call();
}
// Consumer overload:
public void extractor(Consumer<ExtractorSpec> body) {
    if (shouldAbort()) return;
    body.accept(extractor);
}
```

---

## Groovy Stubs Module

Minimal `groovy.lang.*` stubs for class loading without GraalVM GroovyIndyInterfaceFeature:

| Stub Class | Purpose |
|-----------|---------|
| `groovy.lang.Binding` | Superclass of our Binding |
| `groovy.lang.GroovyObjectSupport` | Superclass of Binding.Parsed |
| `groovy.lang.GroovyObject` | Interface required by GroovyObjectSupport |
| `groovy.lang.Closure` | Parameter type in spec Closure methods |
| `groovy.lang.MetaClass` | Required by GroovyObject interface |
| `groovy.lang.GString` | Parameter type in SamplerSpec.rateLimit |

**Key**: No `org.codehaus.groovy.*` classes. GraalVM's `GroovyIndyInterfaceFeature`
checks `isInConfiguration()` for `IndyInterface` — without it, the feature stays dormant.

At test time, real Groovy JARs (test dependency) take precedence over stubs.

---

## Precompiler Integration

`compileLAL()` changes:
1. Existing Groovy compilation continues (for test Path A backward compat)
2. After Groovy compilation: transpile each unique DSL to Java
3. Compile Java sources with javac (prepend log-analyzer-for-graalvm/target/classes)
4. Write manifest: `META-INF/lal-expressions.txt` (hash=FQCN, one line per unique class)

---

## Runtime DSL.java Changes

Switch from DelegatingScript to LalExpression:
```java
// Before:
private final DelegatingScript script;
script.setDelegate(filterSpec);
script.run();

// After:
private final LalExpression expression;
expression.execute(filterSpec, binding);
```

Manifest: `lal-expressions.txt` (hash=FQCN) replaces `lal-scripts-by-hash.txt`.

---

## Test Coverage (Dual-Path Comparison)

| YAML | Rule | Branches | Tests |
|------|------|----------|-------|
| default | default | none | 1 |
| nginx | nginx-access-log | LOG_KIND (T/F), regex (T/F) | 3 |
| nginx | nginx-error-log | LOG_KIND (T/F) | 2 |
| mysql/pgsql/redis | slow-sql | SLOW_SQL (T/F) | 2 |
| envoy-als | envoy-als | abort, code>=400, flags | 3 |
| envoy-als/k8s/mesh | net-profiling | componentId 4-way, processId, nomatch | 6 |
| **Total** | | | **~19** |

Path A: Fresh GroovyShell (test dependency)
Path B: Transpiled LalExpression from lal-expressions.txt manifest

---

## Files Summary

### New Files
| File | Purpose |
|---|---|
| `build-tools/precompiler/.../LalToJavaTranspiler.java` | AST-walking transpiler |
| `oap-libs-for-graalvm/log-analyzer-for-graalvm/.../dsl/LalExpression.java` | Interface for transpiled LAL classes |
| `oap-libs-for-graalvm/groovy-stubs/` | Minimal groovy.lang.* stubs module |
| Generated `LalExpr_*.java` files | 6 pure Java LAL expression classes |

### Modified Files
| File | Change |
|---|---|
| `.../log-analyzer-for-graalvm/.../dsl/DSL.java` | Load LalExpression; manifest: lal-expressions.txt |
| `.../log-analyzer-for-graalvm/.../spec/filter/FilterSpec.java` | Consumer overloads |
| `.../log-analyzer-for-graalvm/.../spec/extractor/ExtractorSpec.java` | Consumer overloads |
| `.../log-analyzer-for-graalvm/.../spec/sink/SinkSpec.java` | Consumer overloads |
| `.../log-analyzer-for-graalvm/.../spec/sink/SamplerSpec.java` | rateLimit(String, Consumer) |
| `.../log-analyzer-for-graalvm/.../spec/AbstractSpec.java` | abort() no-arg |
| `.../log-analyzer-for-graalvm/pom.xml` | Shade excludes for spec classes |
| `build-tools/precompiler/.../Precompiler.java` | Add transpileLAL() call |
| `oap-graalvm-server/pom.xml` | groovy-stubs runtime, real Groovy test-only |
