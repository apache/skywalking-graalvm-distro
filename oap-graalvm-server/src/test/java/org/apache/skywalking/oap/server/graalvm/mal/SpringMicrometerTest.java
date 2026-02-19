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

package org.apache.skywalking.oap.server.graalvm.mal;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.stream.Stream;
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparison test for meter-analyzer-config/spring-micrometer.yaml.
 *
 * Loads all 23 metrics from the YAML, runs each through Groovy (Path A) and
 * pre-compiled class (Path B), and verifies identical results.
 *
 * Expression types covered: passthrough, multiply(100), increase("PT1M").
 */
class SpringMicrometerTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "meter-analyzer-config/spring-micrometer.yaml";

    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
        return generateComparisonTests(YAML_PATH);
    }

    // ---------------------------------------------------------------
    // Value correctness spot-checks (expressions loaded from YAML)
    // ---------------------------------------------------------------

    /**
     * Passthrough expression preserves the input value unchanged.
     * <pre>
     * # Input:
     * jvm_memory_committed{service="test-svc",instance="test-inst"} 1024000.0
     * # Expected output: 1024000.0
     * </pre>
     */
    @Test
    void passthroughPreservesValue() {
        Rule rule = loadRule(YAML_PATH);
        String expression = findMetricExp(rule, "meter_jvm_memory_committed");

        Expression e = compileWithGroovy("verify_passthrough", expression);
        Result r = e.run(ImmutableMap.of("jvm_memory_committed",
            buildSingleSample("jvm_memory_committed", 1024000.0,
                System.currentTimeMillis())));

        assertTrue(r.isSuccess(), "Should succeed, got: " + r.getError());
        assertTrue(r.getData().samples.length > 0, "Should have samples");
        assertEquals(1024000.0, r.getData().samples[0].getValue(), 0.001,
            "Passthrough should preserve value");
    }

    /**
     * multiply(100) scales the input value.
     * <pre>
     * # Input:
     * process_cpu_usage{service="test-svc",instance="test-inst"} 0.75
     * # Expected output: 75.0  (0.75 * 100)
     * </pre>
     */
    @Test
    void multiplyActuallyMultipliesValue() {
        Rule rule = loadRule(YAML_PATH);
        String expression = findMetricExp(rule, "meter_process_cpu_usage");

        Expression e = compileWithGroovy("verify_multiply", expression);
        Result r = e.run(ImmutableMap.of("process_cpu_usage",
            buildSingleSample("process_cpu_usage", 0.75,
                System.currentTimeMillis())));

        assertTrue(r.isSuccess(), "Should succeed, got: " + r.getError());
        assertTrue(r.getData().samples.length > 0, "Should have samples");
        assertEquals(75.0, r.getData().samples[0].getValue(), 0.001,
            "0.75 * 100 should equal 75.0");
    }

    /**
     * increase("PT1M") computes the counter delta between two data points.
     * <pre>
     * # Input (t1):
     * http_server_requests_count{service="test-svc",instance="test-inst"} 100.0 1704067200000
     * # Input (t2, 2 min later):
     * http_server_requests_count{service="test-svc",instance="test-inst"} 300.0 1704067320000
     * # Expected output: 200.0  (300 - 100)
     * </pre>
     */
    @Test
    void increaseComputesDelta() {
        Rule rule = loadRule(YAML_PATH);
        String expression = findMetricExp(rule, "meter_http_server_requests_count");
        long ts1 = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        long ts2 = Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

        Expression e = compileWithGroovy("verify_increase", expression);
        e.run(ImmutableMap.of("http_server_requests_count",
            buildSingleSample("http_server_requests_count", 100.0, ts1)));
        Result r = e.run(ImmutableMap.of("http_server_requests_count",
            buildSingleSample("http_server_requests_count", 300.0, ts2)));

        assertTrue(r.isSuccess(), "Should succeed on 2nd run, got: " + r.getError());
        assertTrue(r.getData().samples.length > 0, "Should have samples");
        assertEquals(200.0, r.getData().samples[0].getValue(), 0.001,
            "300 - 100 should equal 200");
    }

    private static SampleFamily buildSingleSample(final String name,
                                                    final double value,
                                                    final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .name(name)
                .labels(ImmutableMap.of(
                    "service", "test-svc",
                    "instance", "test-inst"))
                .value(value)
                .timestamp(timestamp)
                .build()
        ).build();
    }
}
