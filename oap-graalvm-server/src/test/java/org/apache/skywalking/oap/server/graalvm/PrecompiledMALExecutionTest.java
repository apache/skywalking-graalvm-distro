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
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyFunctions;
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
 * Verifies that pre-compiled MAL v2 expressions can execute at runtime.
 * Tests the full pipeline: load pre-compiled MalExpression class → wrap in Expression →
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
     * Test that a pre-compiled script can run with SampleFamily data.
     *
     * Uses "meter_jvm_memory_committed" which has expression:
     *   (jvm_memory_committed).instance(['service'], ['instance'], Layer.GENERAL)
     */
    @Test
    void precompiledScriptCanRunWithSampleData() throws Exception {
        String metricName = "meter_jvm_memory_committed";
        String expression = "(jvm_memory_committed).instance(['service'], ['instance'], Layer.GENERAL)";

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

        Expression e = loadFromManifest(metricName, expression);
        assertNotNull(e, "Expression should be created from pre-compiled script");

        Result r = e.run(input);
        assertNotNull(r, "Result should not be null");
        assertTrue(r.isSuccess(), "Expression should execute successfully, got: " + r.getError());
    }

    /**
     * Test a pre-compiled script that uses .multiply().
     */
    @Test
    void precompiledScriptWithMultiplyCanRun() throws Exception {
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

        Expression e = loadFromManifest(metricName, expression);
        Result r = e.run(input);
        assertTrue(r.isSuccess(), "multiply() expression should execute successfully, got: " + r.getError());
    }

    /**
     * Test a pre-compiled script that uses .sum() aggregation with group-by.
     */
    @Test
    void precompiledScriptWithSumAggregationCanRun() throws Exception {
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

        Expression e = loadFromManifest(metricName, expression);
        Result r = e.run(input);
        // increase() with single sample may return EMPTY (needs 2+ data points),
        // but it should NOT throw an exception
        assertNotNull(r, "Result should not be null");
    }

    /**
     * Test loading ALL pre-compiled MalExpression classes into Expression objects.
     * Verifies that every class in the per-file manifests can be instantiated.
     */
    @Test
    void allPrecompiledExpressionsCanBeWrappedInExpression() throws Exception {
        Map<String, String> manifest = loadExpressionMap();
        assertFalse(manifest.isEmpty(), "V2 expression manifest should not be empty");

        int successCount = 0;
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            String expression = entry.getKey();
            String className = entry.getValue();

            Class<?> exprClass = Class.forName(className);
            MalExpression malExpr =
                (MalExpression) exprClass.getDeclaredConstructor().newInstance();

            Expression e = new Expression("test", expression, malExpr);
            assertNotNull(e, "Expression wrapping should succeed for: " + className);
            successCount++;
        }

        assertTrue(successCount == manifest.size(),
            "All " + manifest.size() + " pre-compiled expressions should wrap successfully, got " + successCount);
    }

    /**
     * Load a pre-compiled MalExpression from per-file manifest using expression text lookup.
     */
    private static Expression loadFromManifest(final String metricName,
                                               final String expression) throws Exception {
        Map<String, String> manifest = loadExpressionMap();
        String className = manifest.get(expression);
        assertNotNull(className, "Manifest missing expression for metric: " + metricName);

        Class<?> exprClass = Class.forName(className);
        MalExpression malExpr = (MalExpression) exprClass.getDeclaredConstructor().newInstance();
        wireClosures(exprClass, malExpr);
        return new Expression(metricName, expression, malExpr);
    }

    private static final Map<String, ClosureInfo> CLOSURE_TYPES = new HashMap<>();

    private record ClosureInfo(Class<?> interfaceClass, String samName,
                               MethodType samType, MethodType instantiatedType,
                               MethodType methodType) {
    }

    static {
        CLOSURE_TYPES.put(
            SampleFamilyFunctions.TagFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.TagFunction.class, "apply",
                MethodType.methodType(Object.class, Object.class),
                MethodType.methodType(Map.class, Map.class),
                MethodType.methodType(Map.class, Map.class)));
        CLOSURE_TYPES.put(
            SampleFamilyFunctions.PropertiesExtractor.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.PropertiesExtractor.class, "apply",
                MethodType.methodType(Object.class, Object.class),
                MethodType.methodType(Map.class, Map.class),
                MethodType.methodType(Map.class, Map.class)));
        CLOSURE_TYPES.put(
            SampleFamilyFunctions.ForEachFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.ForEachFunction.class, "accept",
                MethodType.methodType(void.class, String.class, Map.class),
                MethodType.methodType(void.class, String.class, Map.class),
                MethodType.methodType(void.class, String.class, Map.class)));
        CLOSURE_TYPES.put(
            SampleFamilyFunctions.DecorateFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.DecorateFunction.class, "accept",
                MethodType.methodType(void.class, Object.class),
                MethodType.methodType(void.class, Object.class),
                MethodType.methodType(void.class, Object.class)));
    }

    private static void wireClosures(final Class<?> clazz, final Object instance) throws Exception {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            for (Field field : clazz.getFields()) {
                ClosureInfo info = CLOSURE_TYPES.get(field.getType().getName());
                if (info == null) {
                    continue;
                }
                String methodName = field.getName() + "_" + info.samName;
                MethodHandle mh = lookup.findVirtual(clazz, methodName, info.methodType);
                CallSite site = LambdaMetafactory.metafactory(
                    lookup, info.samName,
                    MethodType.methodType(info.interfaceClass, clazz),
                    info.samType, mh, info.instantiatedType);
                field.set(instance, site.getTarget().invoke(instance));
            }
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to wire closures for " + clazz.getName(), t);
        }
    }

    private static Map<String, String> loadExpressionMap() throws Exception {
        Map<String, String> map = new HashMap<>();
        ClassLoader cl = PrecompiledMALExecutionTest.class.getClassLoader();
        try (InputStream mis = cl.getResourceAsStream("META-INF/mal-v2.manifest")) {
            assertNotNull(mis, "META-INF/mal-v2.manifest not found");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(mis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    try (InputStream cis = cl.getResourceAsStream("META-INF/mal-v2/" + line)) {
                        if (cis == null) {
                            continue;
                        }
                        Properties props = new Properties();
                        props.load(cis);
                        for (int i = 0; ; i++) {
                            String exp = props.getProperty("rule." + i + ".exp");
                            if (exp == null) {
                                break;
                            }
                            String className = props.getProperty("rule." + i + ".class", "");
                            if (!className.isEmpty()) {
                                map.put(exp, className);
                            }
                        }
                    }
                }
            }
        }
        return map;
    }
}
