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
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Comparison test for meter-analyzer-config/go-agent.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>sum with aggregation labels — sum(['created_by', 'service', 'instance'])
 *       for created_tracing_context_counter, created_ignored_context_counter</li>
 *   <li>sum(['service', 'instance']) — for finished counters</li>
 *   <li>sum(['source', 'service', 'instance']) — for possible_leaked_context_counter</li>
 *   <li>sum(['plugin_name', 'service', 'instance']) — for interceptor_error_counter</li>
 *   <li>increase('PT1M') — on all counter metrics</li>
 *   <li>sum(['le', 'service', 'instance']).histogram().histogram_percentile([50,75,90,95,99])
 *       for sw_go_tracing_context_performance</li>
 *   <li>Scope: instance(['service'], ['instance'], Layer.SO11Y_GO_AGENT)</li>
 * </ul>
 */
class GoAgentTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "meter-analyzer-config/go-agent.yaml";
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
     * Build the full input map covering all sample names in go-agent.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   service="test-svc", instance="test-inst"
     *
     * # --- Counter metrics with created_by label ---
     * sw_go_created_tracing_context_counter{service="test-svc",instance="test-inst",created_by="propagation"} 500.0
     * sw_go_created_tracing_context_counter{service="test-svc",instance="test-inst",created_by="new"} 300.0
     * sw_go_created_ignored_context_counter{service="test-svc",instance="test-inst",created_by="propagation"} 50.0
     * sw_go_created_ignored_context_counter{service="test-svc",instance="test-inst",created_by="new"} 30.0
     *
     * # --- Counter metrics with only scope labels ---
     * sw_go_finished_tracing_context_counter{service="test-svc",instance="test-inst"} 780.0
     * sw_go_finished_ignored_context_counter{service="test-svc",instance="test-inst"} 75.0
     *
     * # --- Counter metrics with source label ---
     * sw_go_possible_leaked_context_counter{service="test-svc",instance="test-inst",source="tracing"} 3.0
     * sw_go_possible_leaked_context_counter{service="test-svc",instance="test-inst",source="ignored"} 2.0
     *
     * # --- Counter metrics with plugin_name label ---
     * sw_go_interceptor_error_counter{service="test-svc",instance="test-inst",plugin_name="http"} 10.0
     * sw_go_interceptor_error_counter{service="test-svc",instance="test-inst",plugin_name="grpc"} 5.0
     *
     * # --- Histogram metric (need 'le' bucket labels, cumulative values) ---
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.01"} 25.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.025"} 50.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.05"} 80.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.1"} 120.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.25"} 180.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="0.5"} 220.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="1"} 260.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="2.5"} 285.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="5"} 295.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="10"} 299.0
     * sw_go_tracing_context_performance{service="test-svc",instance="test-inst",le="+Inf"} 300.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "service", "test-svc",
            "instance", "test-inst");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Counter metrics with created_by label ---
            .put("sw_go_created_tracing_context_counter",
                labeledSamples("sw_go_created_tracing_context_counter",
                    scope, "created_by",
                    new String[]{"propagation", "new"},
                    new double[]{500.0, 300.0},
                    scale, timestamp))
            .put("sw_go_created_ignored_context_counter",
                labeledSamples("sw_go_created_ignored_context_counter",
                    scope, "created_by",
                    new String[]{"propagation", "new"},
                    new double[]{50.0, 30.0},
                    scale, timestamp))

            // --- Counter metrics with only scope labels ---
            .put("sw_go_finished_tracing_context_counter",
                single("sw_go_finished_tracing_context_counter",
                    scope, 780.0 * scale, timestamp))
            .put("sw_go_finished_ignored_context_counter",
                single("sw_go_finished_ignored_context_counter",
                    scope, 75.0 * scale, timestamp))

            // --- Counter metrics with source label ---
            .put("sw_go_possible_leaked_context_counter",
                labeledSamples("sw_go_possible_leaked_context_counter",
                    scope, "source",
                    new String[]{"tracing", "ignored"},
                    new double[]{3.0, 2.0},
                    scale, timestamp))

            // --- Counter metrics with plugin_name label ---
            .put("sw_go_interceptor_error_counter",
                labeledSamples("sw_go_interceptor_error_counter",
                    scope, "plugin_name",
                    new String[]{"http", "grpc"},
                    new double[]{10.0, 5.0},
                    scale, timestamp))

            // --- Histogram metric ---
            .put("sw_go_tracing_context_performance",
                histogramSamples("sw_go_tracing_context_performance",
                    scope, scale, timestamp))

            .build();
    }

    private static SampleFamily single(final String name,
                                        final ImmutableMap<String, String> scope,
                                        final double value,
                                        final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .name(name)
                .labels(scope)
                .value(value)
                .timestamp(timestamp)
                .build()
        ).build();
    }

    private static SampleFamily labeledSamples(final String name,
                                                final ImmutableMap<String, String> scope,
                                                final String labelKey,
                                                final String[] labelValues,
                                                final double[] baseValues,
                                                final double scale,
                                                final long timestamp) {
        Sample[] samples = new Sample[labelValues.length];
        for (int i = 0; i < labelValues.length; i++) {
            samples[i] = Sample.builder()
                .name(name)
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(scope)
                    .put(labelKey, labelValues[i])
                    .build())
                .value(baseValues[i] * scale)
                .timestamp(timestamp)
                .build();
        }
        return SampleFamilyBuilder.newBuilder(samples).build();
    }
}
