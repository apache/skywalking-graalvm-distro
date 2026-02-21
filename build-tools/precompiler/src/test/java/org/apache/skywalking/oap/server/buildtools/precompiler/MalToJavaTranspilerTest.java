/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MalToJavaTranspiler.
 */
class MalToJavaTranspilerTest {

    private MalToJavaTranspiler transpiler;

    @BeforeEach
    void setUp() {
        transpiler = new MalToJavaTranspiler();
    }

    // ---- AST Parsing + Simple Variable References ----

    @Test
    void simpleVariableReference() {
        String java = transpiler.transpileExpression("MalExpr_test", "metric_name");
        assertNotNull(java);

        // Verify package and class structure
        assertTrue(java.contains("package " + MalToJavaTranspiler.GENERATED_PACKAGE),
            "Should have correct package");
        assertTrue(java.contains("public class MalExpr_test implements MalExpression"),
            "Should implement MalExpression");
        assertTrue(java.contains("public SampleFamily run(Map<String, SampleFamily> samples)"),
            "Should have run method");

        // Verify sample name tracking in ExpressionParsingContext
        assertTrue(java.contains("ctx.getSamples().add(\"metric_name\")"),
            "Should track sample name in parsing context");

        // Verify sample lookup
        assertTrue(java.contains("samples.getOrDefault(\"metric_name\", SampleFamily.EMPTY)"),
            "Should look up sample family from map");
    }

    @Test
    void downsamplingConstantNotTrackedAsSample() {
        // DownsamplingType constants like SUM should not be treated as sample names
        String java = transpiler.transpileExpression("MalExpr_test", "SUM");
        assertNotNull(java);

        // Should NOT track SUM as a sample name
        assertTrue(!java.contains("ctx.getSamples().add(\"SUM\")"),
            "Should not track DownsamplingType constant as sample");

        // Should resolve to DownsamplingType.SUM
        assertTrue(java.contains("DownsamplingType.SUM"),
            "Should resolve to DownsamplingType.SUM");
    }

    @Test
    void parseToAST_producesModuleNode() {
        var ast = transpiler.parseToAST("some_metric");
        assertNotNull(ast, "Should produce a ModuleNode");
        assertNotNull(ast.getStatementBlock(), "Should have a statement block");
    }

    @Test
    void constantString() {
        // A bare string constant — not typical in MAL but tests ConstantExpression
        String java = transpiler.transpileExpression("MalExpr_test", "'hello'");
        assertNotNull(java);
        assertTrue(java.contains("\"hello\""),
            "Should convert Groovy string to Java string");
    }

    @Test
    void constantNumber() {
        String java = transpiler.transpileExpression("MalExpr_test", "42");
        assertNotNull(java);
        assertTrue(java.contains("42"),
            "Should preserve number literal");
    }

    // ---- Method Chains + List Literals + Enum Properties ----

    @Test
    void simpleMethodChain() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric_name.sum(['a', 'b']).service(['svc'], Layer.GENERAL)");
        assertNotNull(java);

        assertTrue(java.contains(".sum(List.of(\"a\", \"b\"))"),
            "Should translate ['a','b'] to List.of(\"a\", \"b\")");
        assertTrue(java.contains(".service(List.of(\"svc\"), Layer.GENERAL)"),
            "Should translate Layer.GENERAL as enum");
    }

    @Test
    void tagEqualChain() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "cpu_seconds.tagNotEqual('mode', 'idle').sum(['host']).rate('PT1M')");
        assertNotNull(java);

        assertTrue(java.contains(".tagNotEqual(\"mode\", \"idle\")"),
            "Should translate tagNotEqual with string args");
        assertTrue(java.contains(".sum(List.of(\"host\"))"),
            "Should translate single-element list");
        assertTrue(java.contains(".rate(\"PT1M\")"),
            "Should translate rate with string arg");
    }

    @Test
    void downsamplingMethod() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.downsampling(SUM)");
        assertNotNull(java);

        assertTrue(java.contains(".downsampling(DownsamplingType.SUM)"),
            "Should resolve SUM to DownsamplingType.SUM");
    }

    @Test
    void retagByK8sMeta() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.retagByK8sMeta('service', K8sRetagType.Pod2Service, 'pod', 'namespace')");
        assertNotNull(java);

        assertTrue(java.contains(".retagByK8sMeta(\"service\", K8sRetagType.Pod2Service, \"pod\", \"namespace\")"),
            "Should translate K8sRetagType enum and string args");
    }

    @Test
    void histogramPercentile() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.sum(['le', 'svc']).histogram().histogram_percentile([50, 75, 90])");
        assertNotNull(java);

        assertTrue(java.contains(".histogram()"),
            "Should translate no-arg histogram()");
        assertTrue(java.contains(".histogram_percentile(List.of(50, 75, 90))"),
            "Should translate integer list");
    }

    @Test
    void sampleNameCollectionThroughChain() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "my_metric.sum(['a']).service(['svc'], Layer.GENERAL)");
        assertNotNull(java);

        assertTrue(java.contains("ctx.getSamples().add(\"my_metric\")"),
            "Should collect sample name from root of method chain");
        assertTrue(!java.contains("ctx.getSamples().add(\"a\")"),
            "Should NOT collect 'a' (it's a string constant arg, not a sample)");
    }

    @Test
    void detectPointEnum() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.serviceRelation(DetectPoint.CLIENT, ['src'], ['dst'], Layer.MESH_DP)");
        assertNotNull(java);

        assertTrue(java.contains("DetectPoint.CLIENT"),
            "Should translate DetectPoint enum");
        assertTrue(java.contains("Layer.MESH_DP"),
            "Should translate Layer enum");
    }

    @Test
    void enumImportsPresent() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.service(['svc'], Layer.GENERAL)");
        assertNotNull(java);

        assertTrue(java.contains("import org.apache.skywalking.oap.server.core.analysis.Layer;"),
            "Should import Layer");
        assertTrue(java.contains("import org.apache.skywalking.oap.server.core.source.DetectPoint;"),
            "Should import DetectPoint");
        assertTrue(java.contains("import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;"),
            "Should import K8sRetagType");
    }

    // ---- Binary Arithmetic with Operand-Swap ----

    @Test
    void sfTimesNumber() {
        // metric * 100 → samples.getOrDefault(...).multiply(100)
        String java = transpiler.transpileExpression("MalExpr_test", "metric * 100");
        assertNotNull(java);
        assertTrue(java.contains(".multiply(100)"),
            "SF * N should call .multiply(N)");
    }

    @Test
    void sfDivNumber() {
        String java = transpiler.transpileExpression("MalExpr_test", "metric / 1024");
        assertNotNull(java);
        assertTrue(java.contains(".div(1024)"),
            "SF / N should call .div(N)");
    }

    @Test
    void numberMinusSf() {
        // 100 - metric → metric.minus(100).negative()
        String java = transpiler.transpileExpression("MalExpr_test", "100 - metric");
        assertNotNull(java);
        assertTrue(java.contains(".minus(100).negative()"),
            "N - SF should produce sf.minus(N).negative()");
    }

    @Test
    void numberDivSf() {
        // 1 / metric → metric.newValue(v -> 1 / v)
        String java = transpiler.transpileExpression("MalExpr_test", "1 / metric");
        assertNotNull(java);
        assertTrue(java.contains(".newValue(v -> 1 / v)"),
            "N / SF should produce sf.newValue(v -> N / v)");
    }

    @Test
    void numberPlusSf() {
        // 10 + metric → metric.plus(10)
        String java = transpiler.transpileExpression("MalExpr_test", "10 + metric");
        assertNotNull(java);
        assertTrue(java.contains(".plus(10)"),
            "N + SF should swap to sf.plus(N)");
    }

    @Test
    void numberTimesSf() {
        // 100 * metric → metric.multiply(100)
        String java = transpiler.transpileExpression("MalExpr_test", "100 * metric");
        assertNotNull(java);
        assertTrue(java.contains(".multiply(100)"),
            "N * SF should swap to sf.multiply(N)");
    }

    @Test
    void sfMinusSf() {
        // metricA - metricB → metricA.minus(metricB)
        String java = transpiler.transpileExpression("MalExpr_test", "mem_total - mem_avail");
        assertNotNull(java);
        assertTrue(java.contains("ctx.getSamples().add(\"mem_total\")"),
            "Should collect both sample names");
        assertTrue(java.contains("ctx.getSamples().add(\"mem_avail\")"),
            "Should collect both sample names");
        assertTrue(java.contains(".minus("),
            "SF - SF should call .minus()");
    }

    @Test
    void sfDivSfTimesNumber() {
        // metricA / metricB * 100
        String java = transpiler.transpileExpression("MalExpr_test",
            "used_bytes / max_bytes * 100");
        assertNotNull(java);
        assertTrue(java.contains(".div("),
            "Should have .div() for SF / SF");
        assertTrue(java.contains(".multiply(100)"),
            "Should have .multiply(100) for result * 100");
    }

    @Test
    void nestedParenArithmetic() {
        // 100 - ((mem_free * 100) / mem_total)
        // Parens are just AST precedence — this is:
        //   100 - ( (mem_free * 100) / mem_total )
        String java = transpiler.transpileExpression("MalExpr_test",
            "100 - ((mem_free * 100) / mem_total)");
        assertNotNull(java);
        // Inner: mem_free.multiply(100) → .div(mem_total) → then 100 - result
        assertTrue(java.contains(".multiply(100)"),
            "Should have inner multiply");
        assertTrue(java.contains(".negative()"),
            "100 - SF should produce .negative()");
    }

    @Test
    void parenthesizedWithMethodChain() {
        // (metric * 100).tagNotEqual('mode', 'idle').sum(['host']).rate('PT1M')
        String java = transpiler.transpileExpression("MalExpr_test",
            "(metric * 100).tagNotEqual('mode', 'idle').sum(['host']).rate('PT1M')");
        assertNotNull(java);
        assertTrue(java.contains(".multiply(100)"),
            "Should have multiply inside parens");
        assertTrue(java.contains(".tagNotEqual(\"mode\", \"idle\")"),
            "Should chain tagNotEqual after parens");
        assertTrue(java.contains(".rate(\"PT1M\")"),
            "Should chain rate at the end");
    }

    // ---- tag() Closure — Simple Cases ----

    @Test
    void tagAssignmentWithStringConcat() {
        // .tag({tags -> tags.route = 'route/' + tags['route']})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.route = 'route/' + tags['route']})");
        assertNotNull(java);

        assertTrue(java.contains(".tag((TagFunction)"),
            "Should cast closure to TagFunction");
        assertTrue(java.contains("tags.put(\"route\", \"route/\" + tags.get(\"route\"))"),
            "Should translate assignment with string concat and subscript read");
        assertTrue(java.contains("return tags;"),
            "Should return tags at end of lambda");
    }

    @Test
    void tagRemove() {
        // .tag({tags -> tags.remove('condition')})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.remove('condition')})");
        assertNotNull(java);

        assertTrue(java.contains("tags.remove(\"condition\")"),
            "Should translate remove call");
    }

    @Test
    void tagPropertyToProperty() {
        // .tag({tags -> tags.rs_nm = tags.set})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.rs_nm = tags.set})");
        assertNotNull(java);

        assertTrue(java.contains("tags.put(\"rs_nm\", tags.get(\"set\"))"),
            "Should translate property read on RHS to tags.get()");
    }

    @Test
    void tagStringConcatWithPropertyRead() {
        // .tag({tags -> tags.cluster = 'es::' + tags.cluster})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.cluster = 'es::' + tags.cluster})");
        assertNotNull(java);

        assertTrue(java.contains("tags.put(\"cluster\", \"es::\" + tags.get(\"cluster\"))"),
            "Should translate string concat with property read");
    }

    @Test
    void tagClosureLambdaStructure() {
        // Verify the overall lambda structure
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.x = 'y'})");
        assertNotNull(java);

        assertTrue(java.contains("(tags -> {"),
            "Should have lambda opening");
        assertTrue(java.contains("return tags;"),
            "Should return tags variable");
    }

    @Test
    void tagWithSubscriptWrite() {
        // .tag({tags -> tags['service_name'] = tags['svc']})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags['service_name'] = tags['svc']})");
        assertNotNull(java);

        assertTrue(java.contains("tags.put(\"service_name\", tags.get(\"svc\"))"),
            "Should translate subscript write and read");
    }

    @Test
    void tagChainAfterTag() {
        // .tag({...}).sum(['host']).service(['svc'], Layer.GENERAL)
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.x = 'y'}).sum(['host']).service(['svc'], Layer.GENERAL)");
        assertNotNull(java);

        assertTrue(java.contains(".tag((TagFunction)"),
            "Should have tag call");
        assertTrue(java.contains(".sum(List.of(\"host\"))"),
            "Should chain sum after tag");
        assertTrue(java.contains(".service(List.of(\"svc\"), Layer.GENERAL)"),
            "Should chain service after sum");
    }

    // ---- tag() Closure — if/else + Compound Conditions ----

    @Test
    void ifOnlyWithChainedOr() {
        // Real pattern from otel-rules/oap.yaml: GC type classification
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['gc'] == 'PS Scavenge' || tags['gc'] == 'Copy' || tags['gc'] == 'ParNew' || tags['gc'] == 'G1 Young Generation') {tags.gc = 'young_gc_count'} })");
        assertNotNull(java);

        assertTrue(java.contains("if (\"PS Scavenge\".equals(tags.get(\"gc\"))"),
            "Should translate first == with constant on left for null-safety");
        assertTrue(java.contains("|| \"Copy\".equals(tags.get(\"gc\"))"),
            "Should chain || for second comparison");
        assertTrue(java.contains("|| \"ParNew\".equals(tags.get(\"gc\"))"),
            "Should chain || for third comparison");
        assertTrue(java.contains("|| \"G1 Young Generation\".equals(tags.get(\"gc\"))"),
            "Should chain || for fourth comparison");
        assertTrue(java.contains("tags.put(\"gc\", \"young_gc_count\")"),
            "Should translate assignment in if body");
    }

    @Test
    void ifElse() {
        // Real pattern from elasticsearch-index.yaml: primary/replica classification
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['primary'] == 'true') {tags.primary = 'primary'} else {tags.primary = 'replica'} })");
        assertNotNull(java);

        assertTrue(java.contains("if (\"true\".equals(tags.get(\"primary\"))"),
            "Should translate == comparison in condition");
        assertTrue(java.contains("tags.put(\"primary\", \"primary\")"),
            "Should translate if-branch assignment");
        assertTrue(java.contains("} else {"),
            "Should have else clause");
        assertTrue(java.contains("tags.put(\"primary\", \"replica\")"),
            "Should translate else-branch assignment");
    }

    @Test
    void ifOnlyNoElse() {
        // if-only, no else — the else block should be empty
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['level'] == '1') {tags.level = 'L1 aggregation'} })");
        assertNotNull(java);

        assertTrue(java.contains("if (\"1\".equals(tags.get(\"level\"))"),
            "Should translate condition");
        assertTrue(java.contains("tags.put(\"level\", \"L1 aggregation\")"),
            "Should translate if-body");
        assertTrue(!java.contains("else"),
            "Should NOT have else clause");
    }

    @Test
    void notEqualComparison() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['status'] != 'ok') {tags.status = 'error'} })");
        assertNotNull(java);

        assertTrue(java.contains("!\"ok\".equals(tags.get(\"status\"))"),
            "Should translate != with negated .equals()");
    }

    @Test
    void logicalAndCondition() {
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['a'] == 'x' && tags['b'] == 'y') {tags.c = 'z'} })");
        assertNotNull(java);

        assertTrue(java.contains("\"x\".equals(tags.get(\"a\")) && \"y\".equals(tags.get(\"b\"))"),
            "Should translate && with .equals() on both sides");
    }

    @Test
    void chainedTagClosuresWithIf() {
        // Real pattern from oap.yaml: two chained tag closures
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['level'] == '1') {tags.level = 'L1 aggregation'} })" +
            ".tag({tags -> if (tags['level'] == '2') {tags.level = 'L2 aggregation'} })");
        assertNotNull(java);

        assertTrue(java.contains("\"1\".equals(tags.get(\"level\"))"),
            "Should translate first tag closure condition");
        assertTrue(java.contains("\"2\".equals(tags.get(\"level\"))"),
            "Should translate second tag closure condition");
    }

    @Test
    void ifWithMethodChainAfter() {
        // tag closure with if, followed by method chain
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> if (tags['gc'] == 'Copy') {tags.gc = 'young'} }).sum(['host']).service(['svc'], Layer.GENERAL)");
        assertNotNull(java);

        assertTrue(java.contains(".tag((TagFunction)"),
            "Should have TagFunction cast");
        assertTrue(java.contains("\"Copy\".equals(tags.get(\"gc\"))"),
            "Should have if condition");
        assertTrue(java.contains(".sum(List.of(\"host\"))"),
            "Should chain sum after tag");
    }

    // ---- Filter Closures ----

    @Test
    void simpleEqualityFilter() {
        // Real pattern: { tags -> tags.job_name == 'vm-monitoring' }
        String java = transpiler.transpileFilter("MalFilter_0",
            "{ tags -> tags.job_name == 'vm-monitoring' }");
        assertNotNull(java);

        assertTrue(java.contains("public class MalFilter_0 implements MalFilter"),
            "Should implement MalFilter");
        assertTrue(java.contains("public boolean test(Map<String, String> tags)"),
            "Should have test method");
        assertTrue(java.contains("\"vm-monitoring\".equals(tags.get(\"job_name\"))"),
            "Should translate == with constant on left");
    }

    @Test
    void filterPackageAndImports() {
        String java = transpiler.transpileFilter("MalFilter_0",
            "{ tags -> tags.job_name == 'x' }");
        assertNotNull(java);

        assertTrue(java.contains("package " + MalToJavaTranspiler.GENERATED_PACKAGE),
            "Should have correct package");
        assertTrue(java.contains("import java.util.*;"),
            "Should import java.util");
        assertTrue(java.contains("import org.apache.skywalking.oap.meter.analyzer.dsl.*;"),
            "Should import dsl package");
    }

    @Test
    void orFilter() {
        // Real pattern: tags.job_name == 'x' || tags.job_name == 'y'
        String java = transpiler.transpileFilter("MalFilter_1",
            "{ tags -> tags.job_name == 'flink-jobManager-monitoring' || tags.job_name == 'flink-taskManager-monitoring' }");
        assertNotNull(java);

        assertTrue(java.contains("\"flink-jobManager-monitoring\".equals(tags.get(\"job_name\"))"),
            "Should translate first ==");
        assertTrue(java.contains("|| \"flink-taskManager-monitoring\".equals(tags.get(\"job_name\"))"),
            "Should translate || with second ==");
    }

    @Test
    void inListFilter() {
        // Real pattern: tags.job_name in ['kubernetes-cadvisor', 'kube-state-metrics']
        String java = transpiler.transpileFilter("MalFilter_2",
            "{ tags -> tags.job_name in ['kubernetes-cadvisor', 'kube-state-metrics'] }");
        assertNotNull(java);

        assertTrue(java.contains("List.of(\"kubernetes-cadvisor\", \"kube-state-metrics\").contains(tags.get(\"job_name\"))"),
            "Should translate 'in' to List.of().contains()");
    }

    @Test
    void compoundAndFilter() {
        // Real pattern: tags.cloud_provider == 'aws' && tags.Namespace == 'AWS/S3'
        String java = transpiler.transpileFilter("MalFilter_3",
            "{ tags -> tags.cloud_provider == 'aws' && tags.Namespace == 'AWS/S3' }");
        assertNotNull(java);

        assertTrue(java.contains("\"aws\".equals(tags.get(\"cloud_provider\"))"),
            "Should translate first ==");
        assertTrue(java.contains("&& \"AWS/S3\".equals(tags.get(\"Namespace\"))"),
            "Should translate && with second ==");
    }

    @Test
    void truthinessFilter() {
        // Real pattern: tags.ApiId used as boolean (truthiness)
        String java = transpiler.transpileFilter("MalFilter_4",
            "{ tags -> tags.cloud_provider == 'aws' && tags.Stage }");
        assertNotNull(java);

        assertTrue(java.contains("\"aws\".equals(tags.get(\"cloud_provider\"))"),
            "Should translate ==");
        assertTrue(java.contains("(tags.get(\"Stage\") != null && !tags.get(\"Stage\").isEmpty())"),
            "Should translate bare tags.Stage as truthiness check");
    }

    @Test
    void negatedTruthinessFilter() {
        // Real pattern: !tags.Method — negated truthiness
        String java = transpiler.transpileFilter("MalFilter_5",
            "{ tags -> tags.cloud_provider == 'aws' && !tags.Method }");
        assertNotNull(java);

        assertTrue(java.contains("(tags.get(\"Method\") == null || tags.get(\"Method\").isEmpty())"),
            "Should translate !tags.Method as negated truthiness");
    }

    @Test
    void compoundWithTruthinessAndNegation() {
        // Real AWS pattern: compound with ==, truthiness, and negation
        String java = transpiler.transpileFilter("MalFilter_6",
            "{ tags -> tags.cloud_provider == 'aws' && tags.Namespace == 'AWS/ApiGateway' && tags.Stage && !tags.Method }");
        assertNotNull(java);

        assertTrue(java.contains("\"aws\".equals(tags.get(\"cloud_provider\"))"),
            "Should translate first ==");
        assertTrue(java.contains("\"AWS/ApiGateway\".equals(tags.get(\"Namespace\"))"),
            "Should translate second ==");
        assertTrue(java.contains("(tags.get(\"Stage\") != null && !tags.get(\"Stage\").isEmpty())"),
            "Should translate truthiness");
        assertTrue(java.contains("(tags.get(\"Method\") == null || tags.get(\"Method\").isEmpty())"),
            "Should translate negated truthiness");
    }

    @Test
    void wrappedBlockFilter() {
        // Real pattern: { tags -> {compound} } — inner {} needs unwrapping
        String java = transpiler.transpileFilter("MalFilter_7",
            "{ tags -> {tags.cloud_provider == 'aws' && tags.Namespace == 'AWS/S3'} }");
        assertNotNull(java);

        assertTrue(java.contains("\"aws\".equals(tags.get(\"cloud_provider\"))"),
            "Should unwrap inner block and translate ==");
        assertTrue(java.contains("\"AWS/S3\".equals(tags.get(\"Namespace\"))"),
            "Should translate second == after unwrapping");
    }

    @Test
    void truthinessWithOrInParens() {
        // Real pattern: (tags.ApiId || tags.ApiName)
        String java = transpiler.transpileFilter("MalFilter_8",
            "{ tags -> tags.cloud_provider == 'aws' && (tags.ApiId || tags.ApiName) }");
        assertNotNull(java);

        assertTrue(java.contains("(tags.get(\"ApiId\") != null && !tags.get(\"ApiId\").isEmpty())"),
            "Should translate ApiId truthiness");
        assertTrue(java.contains("(tags.get(\"ApiName\") != null && !tags.get(\"ApiName\").isEmpty())"),
            "Should translate ApiName truthiness");
    }

    // ---- forEach() Closure ----

    @Test
    void forEachBasicStructure() {
        // Simple forEach with tag write
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['client', 'server'], { prefix, tags -> tags[prefix + '_id'] = 'test' })");
        assertNotNull(java);

        assertTrue(java.contains(".forEach(List.of(\"client\", \"server\"), (ForEachFunction)"),
            "Should cast closure to ForEachFunction");
        assertTrue(java.contains("(prefix, tags) -> {"),
            "Should have two-parameter lambda");
        assertTrue(java.contains("tags.put(prefix + \"_id\", \"test\")"),
            "Should translate dynamic subscript write");
    }

    @Test
    void forEachNullCheckWithEarlyReturn() {
        // Real pattern 1: null check with early return
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['client'], { prefix, tags -> if (tags[prefix + '_process_id'] != null) { return } })");
        assertNotNull(java);

        assertTrue(java.contains("tags.get(prefix + \"_process_id\") != null"),
            "Should translate null check");
        assertTrue(java.contains("return;"),
            "Should have void return for early exit");
    }

    @Test
    void forEachWithProcessRegistry() {
        // Real pattern: static method call with tag reads
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['client'], { prefix, tags -> " +
            "tags[prefix + '_process_id'] = ProcessRegistry.generateVirtualLocalProcess(tags.service, tags.instance) })");
        assertNotNull(java);

        assertTrue(java.contains("ProcessRegistry.generateVirtualLocalProcess(tags.get(\"service\"), tags.get(\"instance\"))"),
            "Should translate static method call with tag reads as args");
        assertTrue(java.contains("tags.put(prefix + \"_process_id\","),
            "Should translate dynamic subscript write");
    }

    @Test
    void forEachVarDeclaration() {
        // Variable declaration inside forEach
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['component'], { key, tags -> String result = '' })");
        assertNotNull(java);

        assertTrue(java.contains("String result = \"\""),
            "Should translate variable declaration with empty string");
    }

    @Test
    void forEachVarDeclWithTagRead() {
        // Variable declaration with tag value initialization
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['component'], { key, tags -> String protocol = tags['protocol'] })");
        assertNotNull(java);

        assertTrue(java.contains("String protocol = tags.get(\"protocol\")"),
            "Should translate var decl with tag read");
    }

    @Test
    void forEachIfElseIfChain() {
        // Real pattern 2: chained if/else-if/else with local variables
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['component'], { key, tags -> " +
            "String protocol = tags['protocol']\n" +
            "String ssl = tags['is_ssl']\n" +
            "String result = ''\n" +
            "if (protocol == 'http' && ssl == 'true') { result = '129' } " +
            "else if (protocol == 'http') { result = '49' } " +
            "else if (ssl == 'true') { result = '130' } " +
            "else { result = '110' }\n" +
            "tags[key] = result })");
        assertNotNull(java);

        // Variable declarations
        assertTrue(java.contains("String protocol = tags.get(\"protocol\")"),
            "Should declare protocol");
        assertTrue(java.contains("String ssl = tags.get(\"is_ssl\")"),
            "Should declare ssl");

        // If condition with local vars
        assertTrue(java.contains("\"http\".equals(protocol)"),
            "Should compare local var with .equals()");
        assertTrue(java.contains("\"true\".equals(ssl)"),
            "Should compare ssl with .equals()");

        // Else-if chain (not nested else { if ... })
        assertTrue(java.contains("} else if ("),
            "Should produce else-if, not nested else { if }");

        // Final else
        assertTrue(java.contains("} else {"),
            "Should have final else");
        assertTrue(java.contains("result = \"110\""),
            "Should assign default value in else");

        // Tag write with variable key
        assertTrue(java.contains("tags.put(key, result)"),
            "Should write result to tags[key]");
    }

    @Test
    void forEachLocalVarAssignment() {
        // Local variable reassignment
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['x'], { key, tags -> " +
            "String r = ''\n" +
            "r = 'abc'\n" +
            "tags[key] = r })");
        assertNotNull(java);

        assertTrue(java.contains("r = \"abc\""),
            "Should translate local var reassignment");
    }

    @Test
    void forEachEqualsOnStringComparison() {
        // Equality with dynamic subscript in condition
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['client'], { prefix, tags -> " +
            "if (tags[prefix + '_local'] == 'true') { tags[prefix + '_id'] = 'local' } })");
        assertNotNull(java);

        assertTrue(java.contains("\"true\".equals(tags.get(prefix + \"_local\"))"),
            "Should translate dynamic subscript comparison with .equals()");
        assertTrue(java.contains("tags.put(prefix + \"_id\", \"local\")"),
            "Should translate dynamic subscript assignment");
    }

    @Test
    void chainedForEach() {
        // Two forEach calls chained
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.forEach(['a'], { k1, tags -> tags[k1] = 'x' })" +
            ".forEach(['b'], { k2, tags -> tags[k2] = 'y' })");
        assertNotNull(java);

        assertTrue(java.contains("(ForEachFunction) (k1, tags)"),
            "Should have first forEach with k1");
        assertTrue(java.contains("(ForEachFunction) (k2, tags)"),
            "Should have second forEach with k2");
    }

    // ---- Elvis (?:), Safe Navigation (?.), Ternary (? :) ----

    @Test
    void safeNavigation() {
        // tags['skywalking_service']?.trim()
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.svc = tags['skywalking_service']?.trim() })");
        assertNotNull(java);

        assertTrue(java.contains("(tags.get(\"skywalking_service\") != null ? tags.get(\"skywalking_service\").trim() : null)"),
            "Should translate ?.trim() to null-checked call");
    }

    @Test
    void elvisOperator() {
        // expr ?: 'default'
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.svc = tags['name'] ?: 'unknown' })");
        assertNotNull(java);

        assertTrue(java.contains("(tags.get(\"name\") != null ? tags.get(\"name\") : \"unknown\")"),
            "Should translate ?: to null-check with default");
    }

    @Test
    void safeNavPlusElvis() {
        // Real pattern: tags['skywalking_service']?.trim()?:'APISIX'
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.service_name = 'APISIX::'+(tags['skywalking_service']?.trim()?:'APISIX') })");
        assertNotNull(java);

        // The safe nav produces a null-checked trim
        assertTrue(java.contains("tags.get(\"skywalking_service\") != null ? tags.get(\"skywalking_service\").trim() : null"),
            "Should have safe nav for trim");
        // The elvis wraps it with default
        assertTrue(java.contains("!= null ?") && java.contains(": \"APISIX\""),
            "Should have elvis default to APISIX");
        // String concat prefix
        assertTrue(java.contains("\"APISIX::\" + "),
            "Should have string prefix concatenation");
    }

    @Test
    void ternaryOperator() {
        // Real pattern: tags.ApiId ? exprA : exprB
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.tag({tags -> tags.service_name = tags.ApiId ? 'gw::'+tags.ApiId : 'gw::'+tags.ApiName })");
        assertNotNull(java);

        // Condition should be truthiness check
        assertTrue(java.contains("tags.get(\"ApiId\") != null && !tags.get(\"ApiId\").isEmpty()"),
            "Should translate ternary condition as truthiness check");
        // True branch
        assertTrue(java.contains("\"gw::\" + tags.get(\"ApiId\")"),
            "Should have true branch expression");
        // False branch
        assertTrue(java.contains("\"gw::\" + tags.get(\"ApiName\")"),
            "Should have false branch expression");
    }

    @Test
    void safeNavInFilterCondition() {
        // Real pattern: tags.Service?.trim() in filter
        String java = transpiler.transpileFilter("MalFilter_test",
            "{ tags -> tags.job_name == 'eks-monitoring' && tags.Service?.trim() }");
        assertNotNull(java);

        assertTrue(java.contains("\"eks-monitoring\".equals(tags.get(\"job_name\"))"),
            "Should translate == comparison");
        // tags.Service?.trim() in boolean context → truthiness of the safe nav result
        assertTrue(java.contains("tags.get(\"Service\") != null ? tags.get(\"Service\").trim() : null"),
            "Should have safe nav for Service?.trim()");
    }

    // ---- instance() with PropertiesExtractor, MapExpression ----

    @Test
    void instanceWithPropertiesExtractor() {
        // Real pattern: instance([...], '::', [...], '', Layer.K8S_SERVICE, {tags -> ['pod': tags.pod]})
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.instance(['cluster', 'service'], '::', ['pod'], '', Layer.K8S_SERVICE, " +
            "{tags -> ['pod': tags.pod, 'namespace': tags.namespace]})");
        assertNotNull(java);

        assertTrue(java.contains(".instance("),
            "Should have instance call");
        assertTrue(java.contains("(PropertiesExtractor)"),
            "Should cast closure to PropertiesExtractor");
        assertTrue(java.contains("Map.of(\"pod\", tags.get(\"pod\"), \"namespace\", tags.get(\"namespace\"))"),
            "Should translate map literal to Map.of()");
    }

    @Test
    void mapExpressionInTagValue() {
        // Map literal standalone
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.instance(['svc'], ['inst'], Layer.GENERAL, {tags -> ['key': tags.val]})");
        assertNotNull(java);

        assertTrue(java.contains("Map.of(\"key\", tags.get(\"val\"))"),
            "Should translate single-entry map");
        assertTrue(java.contains("(PropertiesExtractor)"),
            "Should have PropertiesExtractor cast");
    }

    @Test
    void processRelationNoClosures() {
        // processRelation is just a regular method call — no special handling needed
        String java = transpiler.transpileExpression("MalExpr_test",
            "metric.processRelation('side', ['service'], ['instance'], " +
            "'client_process_id', 'server_process_id', 'component')");
        assertNotNull(java);

        assertTrue(java.contains(".processRelation(\"side\", List.of(\"service\"), List.of(\"instance\"), " +
            "\"client_process_id\", \"server_process_id\", \"component\")"),
            "Should translate processRelation as regular method call");
    }

    // ---- Compilation + Manifests ----

    @Test
    void sourceWrittenForCompilation(@TempDir Path tempDir) throws Exception {
        // Verify source files are written correctly (compilation needs full classpath
        // including meter-analyzer-for-graalvm — tested in integration build)
        String source = transpiler.transpileExpression("MalExpr_compile_test",
            "metric.sum(['host']).service(['svc'], Layer.GENERAL)");
        transpiler.registerExpression("MalExpr_compile_test", source);

        File sourceDir = tempDir.resolve("src").toFile();
        File outputDir = tempDir.resolve("classes").toFile();

        // Try compilation — may fail if runtime classes not on classpath
        try {
            transpiler.compileAll(sourceDir, outputDir, System.getProperty("java.class.path"));

            // If compilation succeeds, verify .class file
            String classPath = MalToJavaTranspiler.GENERATED_PACKAGE.replace('.', File.separatorChar)
                + File.separator + "MalExpr_compile_test.class";
            assertTrue(new File(outputDir, classPath).exists(),
                "Compiled .class file should exist");
        } catch (RuntimeException e) {
            if (e.getMessage().contains("compilation failed")) {
                // Expected when MalExpression etc. not on test classpath
                // Verify source was written correctly instead
                String pkgPath = MalToJavaTranspiler.GENERATED_PACKAGE.replace('.', File.separatorChar);
                File javaFile = new File(sourceDir, pkgPath + "/MalExpr_compile_test.java");
                assertTrue(javaFile.exists(), "Source .java file should be written");
                String written = Files.readString(javaFile.toPath());
                assertTrue(written.contains("implements MalExpression"),
                    "Written source should implement MalExpression");
            } else {
                throw e;
            }
        }
    }

    @Test
    void expressionManifest(@TempDir Path tempDir) throws Exception {
        transpiler.registerExpression("MalExpr_a",
            transpiler.transpileExpression("MalExpr_a", "metric_a"));
        transpiler.registerExpression("MalExpr_b",
            transpiler.transpileExpression("MalExpr_b", "metric_b"));

        File outputDir = tempDir.toFile();
        transpiler.writeExpressionManifest(outputDir);

        File manifest = new File(outputDir, "META-INF/mal-expressions.txt");
        assertTrue(manifest.exists(), "Manifest file should exist");

        List<String> lines = Files.readAllLines(manifest.toPath());
        assertTrue(lines.contains(MalToJavaTranspiler.GENERATED_PACKAGE + ".MalExpr_a"),
            "Should contain MalExpr_a FQCN");
        assertTrue(lines.contains(MalToJavaTranspiler.GENERATED_PACKAGE + ".MalExpr_b"),
            "Should contain MalExpr_b FQCN");
    }

    @Test
    void filterManifest(@TempDir Path tempDir) throws Exception {
        String literal = "{ tags -> tags.job == 'x' }";
        transpiler.registerFilter("MalFilter_0", literal,
            transpiler.transpileFilter("MalFilter_0", literal));

        File outputDir = tempDir.toFile();
        transpiler.writeFilterManifest(outputDir);

        File manifest = new File(outputDir, "META-INF/mal-filter-expressions.properties");
        assertTrue(manifest.exists(), "Filter manifest should exist");

        String content = Files.readString(manifest.toPath());
        assertTrue(content.contains(MalToJavaTranspiler.GENERATED_PACKAGE + ".MalFilter_0"),
            "Should contain MalFilter_0 FQCN");
    }
}
