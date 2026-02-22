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

package org.apache.skywalking.oap.server.graalvm;

import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.skywalking.oap.meter.analyzer.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.dsl.MalExpression;
import org.apache.skywalking.oap.meter.analyzer.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that transpiled MAL expressions can execute at runtime.
 * Tests the full pipeline: load transpiled MalExpression class → wrap in Expression →
 * run with SampleFamily data → verify result.
 */
class PrecompiledMALExecutionTest {

    @BeforeAll
    static void initMeterEntity() {
        MeterEntity.setNamingControl(
            new NamingControl(512, 512, 512, new EndpointNameGrouping()));
    }

    @AfterAll
    static void cleanupMeterEntity() {
        MeterEntity.setNamingControl(null);
    }

    /**
     * Test that our runtime DSL.parse() loads a pre-compiled script and
     * Expression.run() successfully processes SampleFamily data.
     *
     * Uses "meter_jvm_memory_committed" which has expression:
     *   (jvm_memory_committed).instance(['service'], ['instance'], Layer.GENERAL)
     *
     * The script references sample "jvm_memory_committed" via propertyMissing(),
     * then chains .instance() which is a SampleFamily method.
     */
    @Test
    void precompiledScriptCanRunWithSampleData() {
        // This is the actual metric name from the manifest
        String metricName = "meter_jvm_memory_committed";

        // The actual compiled expression (exp + expSuffix from spring-micrometer.yaml)
        String expression = "(jvm_memory_committed).instance(['service'], ['instance'], Layer.GENERAL)";

        // Create test SampleFamily data matching the expected sample name
        SampleFamily sf = SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .labels(ImmutableMap.of("service", "test-service", "instance", "test-instance"))
                .value(1024000.0)
                .name("jvm_memory_committed")
                .build()
        ).build();

        ImmutableMap<String, SampleFamily> input = ImmutableMap.of(
            "jvm_memory_committed", sf
        );

        // This calls our runtime DSL which loads the pre-compiled class
        Expression e = DSL.parse(metricName, expression);
        assertNotNull(e, "Expression should be created from pre-compiled script");

        // Run with sample data — exercises propertyMissing, delegate, and method chaining
        Result r = e.run(input);
        assertNotNull(r, "Result should not be null");
        assertTrue(r.isSuccess(), "Expression should execute successfully, got: " + r.getError());
    }

    /**
     * Test a pre-compiled script that uses .multiply() — exercises ExpandoMetaClass.
     *
     * Uses "meter_process_cpu_usage" which has expression:
     *   (process_cpu_usage.multiply(100)).instance(['service'], ['instance'], Layer.GENERAL)
     */
    @Test
    void precompiledScriptWithMultiplyCanRun() {
        String metricName = "meter_process_cpu_usage";
        String expression = "(process_cpu_usage.multiply(100)).instance(['service'], ['instance'], Layer.GENERAL)";

        SampleFamily sf = SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .labels(ImmutableMap.of("service", "test-service", "instance", "test-instance"))
                .value(0.75)
                .name("process_cpu_usage")
                .build()
        ).build();

        ImmutableMap<String, SampleFamily> input = ImmutableMap.of(
            "process_cpu_usage", sf
        );

        Expression e = DSL.parse(metricName, expression);
        Result r = e.run(input);
        assertTrue(r.isSuccess(), "multiply() expression should execute successfully, got: " + r.getError());
    }

    /**
     * Test a pre-compiled script that uses .sum() aggregation with group-by.
     *
     * Uses a java-agent metric with sum(['...', 'service', 'instance']).increase().
     */
    @Test
    void precompiledScriptWithSumAggregationCanRun() {
        String metricName = "meter_java_agent_finished_tracing_context_count";
        String expression = "(finished_tracing_context_counter.sum(['service', 'instance']).increase('PT1M'))"
            + ".instance(['service'], ['instance'], Layer.SO11Y_JAVA_AGENT)";

        SampleFamily sf = SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .labels(ImmutableMap.of("service", "test-service", "instance", "test-instance"))
                .value(100.0)
                .name("finished_tracing_context_counter")
                .timestamp(System.currentTimeMillis())
                .build()
        ).build();

        ImmutableMap<String, SampleFamily> input = ImmutableMap.of(
            "finished_tracing_context_counter", sf
        );

        Expression e = DSL.parse(metricName, expression);
        Result r = e.run(input);
        // increase() with single sample may return EMPTY (needs 2+ data points),
        // but it should NOT throw an exception
        assertNotNull(r, "Result should not be null");
    }

    /**
     * Test loading ALL transpiled MalExpression classes into Expression objects.
     * Verifies that every class in the manifest can be instantiated.
     */
    @Test
    void allTranspiledExpressionsCanBeWrappedInExpression() throws Exception {
        Map<String, String> manifest = loadTranspiledManifest();
        assertFalse(manifest.isEmpty(), "Transpiled manifest should not be empty");

        int successCount = 0;
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String metricName = entry.getKey();
            String className = entry.getValue();

            Class<?> exprClass = Class.forName(className);
            MalExpression malExpr =
                (MalExpression) exprClass.getDeclaredConstructor().newInstance();

            Expression e = new Expression(metricName, "transpiled", malExpr);
            assertNotNull(e, "Expression wrapping should succeed for: " + metricName);
            successCount++;
        }

        assertTrue(successCount == manifest.size(),
            "All " + manifest.size() + " transpiled expressions should wrap successfully, got " + successCount);
    }

    private static Map<String, String> loadTranspiledManifest() throws Exception {
        Map<String, String> map = new HashMap<>();
        try (InputStream is = PrecompiledMALExecutionTest.class.getClassLoader()
                .getResourceAsStream("META-INF/mal-expressions.txt")) {
            assertNotNull(is, "Transpiled manifest not found");
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        String simpleName = line.substring(line.lastIndexOf('.') + 1);
                        if (simpleName.startsWith("MalExpr_")) {
                            String metric = simpleName.substring("MalExpr_".length());
                            map.put(metric, line);
                        }
                    }
                }
            }
        }
        return map;
    }
}
