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
 * Comparison test for meter-analyzer-config/java-agent.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>sum with aggregation labels — sum(['created_by', 'service', 'instance'])
 *       for created_tracing_context_counter, created_ignored_context_counter</li>
 *   <li>sum(['service', 'instance']) — for finished counters</li>
 *   <li>sum(['source', 'service', 'instance']) — for possible_leaked_context_counter</li>
 *   <li>sum(['plugin_name', 'inter_type', 'service', 'instance']) — for
 *       interceptor_error_counter (note: two extra labels vs go-agent)</li>
 *   <li>increase('PT1M') — on all counter metrics</li>
 *   <li>sum(['le', 'service', 'instance']).histogram().histogram_percentile([50,75,90,95,99])
 *       for tracing_context_performance</li>
 *   <li>Scope: instance(['service'], ['instance'], Layer.SO11Y_JAVA_AGENT)</li>
 * </ul>
 */
class JavaAgentTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "meter-analyzer-config/java-agent.yaml";
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
     * Build the full input map covering all sample names in java-agent.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   service="test-svc", instance="test-inst"
     *
     * # --- Counter metrics with created_by label ---
     * created_tracing_context_counter{service="test-svc",instance="test-inst",created_by="propagation"} 500.0
     * created_tracing_context_counter{service="test-svc",instance="test-inst",created_by="new"} 300.0
     * created_ignored_context_counter{service="test-svc",instance="test-inst",created_by="propagation"} 50.0
     * created_ignored_context_counter{service="test-svc",instance="test-inst",created_by="new"} 30.0
     *
     * # --- Counter metrics with only scope labels ---
     * finished_tracing_context_counter{service="test-svc",instance="test-inst"} 780.0
     * finished_ignored_context_counter{service="test-svc",instance="test-inst"} 75.0
     *
     * # --- Counter metrics with source label ---
     * possible_leaked_context_counter{service="test-svc",instance="test-inst",source="tracing"} 3.0
     * possible_leaked_context_counter{service="test-svc",instance="test-inst",source="ignored"} 2.0
     *
     * # --- Counter metrics with plugin_name and inter_type labels ---
     * interceptor_error_counter{service="test-svc",instance="test-inst",plugin_name="http",inter_type="entry"} 10.0
     * interceptor_error_counter{service="test-svc",instance="test-inst",plugin_name="grpc",inter_type="exit"} 5.0
     *
     * # --- Histogram metric (need 'le' bucket labels, cumulative values) ---
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.01"} 25.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.025"} 50.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.05"} 80.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.1"} 120.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.25"} 180.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="0.5"} 220.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="1"} 260.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="2.5"} 285.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="5"} 295.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="10"} 299.0
     * tracing_context_performance{service="test-svc",instance="test-inst",le="+Inf"} 300.0
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
            .put("created_tracing_context_counter",
                labeledSamples("created_tracing_context_counter",
                    scope, "created_by",
                    new String[]{"propagation", "new"},
                    new double[]{500.0, 300.0},
                    scale, timestamp))
            .put("created_ignored_context_counter",
                labeledSamples("created_ignored_context_counter",
                    scope, "created_by",
                    new String[]{"propagation", "new"},
                    new double[]{50.0, 30.0},
                    scale, timestamp))

            // --- Counter metrics with only scope labels ---
            .put("finished_tracing_context_counter",
                single("finished_tracing_context_counter",
                    scope, 780.0 * scale, timestamp))
            .put("finished_ignored_context_counter",
                single("finished_ignored_context_counter",
                    scope, 75.0 * scale, timestamp))

            // --- Counter metrics with source label ---
            .put("possible_leaked_context_counter",
                labeledSamples("possible_leaked_context_counter",
                    scope, "source",
                    new String[]{"tracing", "ignored"},
                    new double[]{3.0, 2.0},
                    scale, timestamp))

            // --- Counter metrics with plugin_name and inter_type labels ---
            .put("interceptor_error_counter",
                dualLabeledSamples("interceptor_error_counter",
                    scope,
                    new String[]{"http", "grpc"},
                    new String[]{"entry", "exit"},
                    new double[]{10.0, 5.0},
                    scale, timestamp))

            // --- Histogram metric ---
            .put("tracing_context_performance",
                histogramSamples("tracing_context_performance",
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

    /**
     * Build a SampleFamily with two extra labels (plugin_name + inter_type).
     * Each sample has a unique combination of both label values.
     */
    private static SampleFamily dualLabeledSamples(
            final String name,
            final ImmutableMap<String, String> scope,
            final String[] pluginNames,
            final String[] interTypes,
            final double[] baseValues,
            final double scale,
            final long timestamp) {
        Sample[] samples = new Sample[pluginNames.length];
        for (int i = 0; i < pluginNames.length; i++) {
            samples[i] = Sample.builder()
                .name(name)
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(scope)
                    .put("plugin_name", pluginNames[i])
                    .put("inter_type", interTypes[i])
                    .build())
                .value(baseValues[i] * scale)
                .timestamp(timestamp)
                .build();
        }
        return SampleFamilyBuilder.newBuilder(samples).build();
    }
}
