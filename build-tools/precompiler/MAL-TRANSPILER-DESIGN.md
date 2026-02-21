# Phase 3: MAL-to-Java Transpiler Design

## Context

Phase 2 (Groovy pre-compilation) is complete. Phase 3 Step 1 (functional interfaces) and Step 2 (SampleFamily replacement) are done. This document covers Steps 3-6: the transpiler, Expression.java replacement, DSL/FilterExpression updates, and Precompiler wiring.

**Goal**: Eliminate Groovy from runtime. Generate pure Java classes at build time implementing `MalExpression` and `MalFilter`. Groovy is still used at build time (for AST parsing and the MetricConvert/Javassist pipeline) but NOT at runtime.

---

## Architecture

```
MalToJavaTranspiler
├── transpile(metricName, expression)       → generates MalExpr_<name>.java
├── transpileFilter(filterLiteral)          → generates MalFilter_<N>.java
├── writeManifests()                        → mal-expressions.txt + mal-filter-expressions.properties
├── compileAll()                            → javax.tools.JavaCompiler on generated .java files
│
├── (internal) parseToAST(expression)       → Groovy CompilationUnit → ModuleNode
├── (internal) visitExpression(ASTNode)     → String (Java code fragment)
├── (internal) visitMethodCall(node)        → method chain translation
├── (internal) visitBinary(node)            → arithmetic with operand-swap logic
├── (internal) visitClosure(node, type)     → Java lambda
├── (internal) visitProperty(node)          → tags.get("key") or samples.get("name")
└── (internal) visitConstant(node)          → literals, enums
```

---

## Generated Class Template (Expressions)

```java
package org.apache.skywalking.oap.server.core.source.oal.rt.mal;
import java.util.*;
import org.apache.skywalking.oap.meter.analyzer.dsl.*;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyFunctions.*;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;

public class MalExpr_meter_xxx implements MalExpression {
    @Override
    public SampleFamily run(Map<String, SampleFamily> samples) {
        ExpressionParsingContext.get().ifPresent(ctx -> {
            ctx.samples.add("metric_name");
        });
        SampleFamily v0 = samples.getOrDefault("metric_name", SampleFamily.EMPTY);
        return v0.sum(List.of("a", "b")).service(List.of("svc"), Layer.GENERAL);
    }
}
```

**ExpressionParsingContext tracking**: Generated `run()` adds all referenced sample names to `ExpressionParsingContext.samples`. Needed for `Expression.parse()` which calls `run(ImmutableMap.of())`. SampleFamily methods already update scope/function/labels/histogram/downsampling in the parsing context.

**metricName on RunningContext**: `Expression.run()` sets `sf.context.setMetricName(metricName)` on all input SampleFamily objects before calling `MalExpression.run()`.

---

## AST Node → Java Mapping

### Core Nodes

| Groovy AST | Java Output | Example |
|---|---|---|
| `VariableExpression` (sample name) | `samples.getOrDefault("name", SampleFamily.EMPTY)` | `metric_name` → local var |
| `VariableExpression` (DownsamplingType) | `DownsamplingType.X` | `SUM` → `DownsamplingType.SUM` |
| `MethodCallExpression` | Direct method call | `.sum(['a'])` → `.sum(List.of("a"))` |
| `ConstantExpression` (string) | `"string"` | `'PT1M'` → `"PT1M"` |
| `ConstantExpression` (number) | number literal | `100` → `100` |
| `ListExpression` (strings) | `List.of(...)` | `['a','b']` → `List.of("a", "b")` |
| `ListExpression` (integers) | `List.of(...)` | `[50,75,90]` → `List.of(50, 75, 90)` |
| `PropertyExpression` (enum) | Qualified enum | `Layer.GENERAL` → `Layer.GENERAL` |

### Binary Operations

| Groovy | Java | Notes |
|---|---|---|
| `SF + SF` | `left.plus(right)` | |
| `SF - SF` | `left.minus(right)` | |
| `SF * SF` | `left.multiply(right)` | |
| `SF / SF` | `left.div(right)` | |
| `SF + N` | `sf.plus(N)` | |
| `SF - N` | `sf.minus(N)` | |
| `SF * N` | `sf.multiply(N)` | |
| `SF / N` | `sf.div(N)` | |
| `N + SF` | `sf.plus(N)` | Swap operands |
| `N - SF` | `sf.minus(N).negative()` | From ExpandoMetaClass |
| `N * SF` | `sf.multiply(N)` | Swap operands |
| `N / SF` | `sf.newValue(v -> N / v)` | From ExpandoMetaClass |

### Closure Patterns

#### `.tag(Closure)` → `TagFunction` lambda

| Groovy inside closure | Java output |
|---|---|
| `tags.key` (read) | `tags.get("key")` |
| `tags['key']` (subscript read) | `tags.get("key")` |
| `tags[expr]` (dynamic subscript) | `tags.get(expr)` |
| `tags.key = val` (write) | `tags.put("key", val)` |
| `tags[expr] = val` (dynamic write) | `tags.put(expr, val)` |
| `tags.remove('key')` | `tags.remove("key")` |
| `tags.ApiId ? A : B` | `(tags.get("ApiId") != null && !tags.get("ApiId").isEmpty()) ? A : B` |
| `tags['x']?.trim()` | `tags.get("x") != null ? tags.get("x").trim() : null` |
| `expr ?: 'default'` | `(expr != null ? expr : "default")` |
| `if (...) { ... }` | Same in Java |
| `if (...) { ... } else { ... }` | Same in Java |

#### File-level `filter:` → `MalFilter` class

| Groovy | Java |
|---|---|
| `tags.key == 'val'` | `"val".equals(tags.get("key"))` |
| `tags.key in ['a', 'b']` | `List.of("a", "b").contains(tags.get("key"))` |
| `tags.key` (truthiness) | `tags.get("key") != null && !tags.get("key").isEmpty()` |
| `!tags.key` (negated) | `tags.get("key") == null \|\| tags.get("key").isEmpty()` |
| `{compound && (a \|\| b)}` | Unwrap block, translate body |

#### `.forEach(List, Closure)` → `ForEachFunction` lambda

| Groovy | Java |
|---|---|
| `tags[prefix + '_key']` | `tags.get(prefix + "_key")` |
| `tags[prefix + '_key'] = val` | `tags.put(prefix + "_key", val)` |
| `tags.service` | `tags.get("service")` |
| `return` (early exit) | `return` (same in BiConsumer lambda) |
| `ProcessRegistry.method(...)` | Same static call |

#### `.instance(..., Closure)` propertiesExtractor → `PropertiesExtractor` lambda

`{tags -> ['pod': tags.pod, 'namespace': tags.namespace]}` → `tags -> Map.of("pod", tags.get("pod"), "namespace", tags.get("namespace"))`

---

## Known DownsamplingType Constants

Bare names in Groovy → qualified in Java:
- `AVG` → `DownsamplingType.AVG`
- `SUM` → `DownsamplingType.SUM`
- `LATEST` → `DownsamplingType.LATEST`
- `SUM_PER_MIN` → `DownsamplingType.SUM_PER_MIN`
- `MAX` → `DownsamplingType.MAX`
- `MIN` → `DownsamplingType.MIN`

---

## Expression.java Replacement

Same FQCN as upstream. Uses `MalExpression` instead of `DelegatingScript`.

No `empower()`, no `ExpandoMetaClass`, no `ExpressionDelegate`, no `ThreadLocal`.

```java
public class Expression {
    private final String metricName;
    private final String literal;
    private final MalExpression expression;

    public Expression(String metricName, String literal, MalExpression expression) { ... }

    public ExpressionParsingContext parse() {
        try (ExpressionParsingContext ctx = ExpressionParsingContext.create()) {
            Result r = run(ImmutableMap.of());
            if (!r.isSuccess() && r.isThrowable()) {
                throw new ExpressionParsingException(...);
            }
            ctx.validate(literal);
            return ctx;
        }
    }

    public Result run(Map<String, SampleFamily> sampleFamilies) {
        for (SampleFamily sf : sampleFamilies.values()) {
            if (sf != null && sf != SampleFamily.EMPTY) {
                sf.context.setMetricName(metricName);
            }
        }
        try {
            SampleFamily sf = expression.run(sampleFamilies);
            if (sf == SampleFamily.EMPTY) { return Result.fail(...); }
            return Result.success(sf);
        } catch (Throwable t) {
            return Result.fail(t);
        }
    }
}
```

---

## Precompiler Integration

`compileMAL()` changes:
1. Still runs MetricConvert for Javassist meter class generation (unchanged)
2. Groovy scripts → temp dir (not exported)
3. Transpiler generates Java expression .class files → output dir
4. Manifests: `mal-expressions.txt` (replaces `mal-groovy-scripts.txt`), `mal-filter-expressions.properties` (replaces `mal-filter-scripts.properties`)

**Combination pattern**: Transpiler tracks duplicate metric names, appends `_1`, `_2` suffixes.

---

## Files Summary

### New Files
| File | Purpose |
|---|---|
| `build-tools/precompiler/.../MalToJavaTranspiler.java` | AST-walking transpiler |
| `oap-libs-for-graalvm/meter-analyzer-for-graalvm/.../dsl/Expression.java` | Same-FQCN: MalExpression instead of DelegatingScript |

### Modified Files
| File | Change |
|---|---|
| `.../meter-analyzer-for-graalvm/.../dsl/DSL.java` | Load MalExpression; manifest: mal-expressions.txt |
| `.../meter-analyzer-for-graalvm/.../dsl/FilterExpression.java` | Load MalFilter; manifest: mal-filter-expressions.properties |
| `.../meter-analyzer-for-graalvm/.../dsl/SampleFamily.java` | `newValue()` → `public` |
| `.../meter-analyzer-for-graalvm/pom.xml` | Add Expression.class to shade excludes |
| `build-tools/precompiler/.../Precompiler.java` | Add transpiler call in compileMAL() |
