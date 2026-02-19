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
 * Comparison test for meter-analyzer-config/go-runtime.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>Passthrough gauge metrics (heap_alloc, stack_used, os_threads_num, etc.)</li>
 *   <li>increase('PT1M') — gc_pause_time, gc_count, heap_alloc_size, heap_frees,
 *       heap_frees_objects, cgo_calls</li>
 *   <li>sum with aggregation labels — sum(['service', 'instance', 'type'])
 *       for gc_count_labeled, memory_heap_labeled, metadata_mcache_labeled,
 *       metadata_mspan_labeled</li>
 *   <li>histogram().histogram_percentile([50,75,90,95,99]).downsampling(SUM)
 *       for gc_heap_allocs_by_size, gc_heap_frees_by_size, gc_pauses,
 *       sched_latencies</li>
 *   <li>Scope: instance(['service'], ['instance'], Layer.GENERAL)</li>
 * </ul>
 */
class GoRuntimeTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "meter-analyzer-config/go-runtime.yaml";
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
     * Build the full input map covering all sample names in go-runtime.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   service="test-svc", instance="test-inst"
     *
     * # --- Passthrough gauge metrics ---
     * instance_golang_heap_alloc{service="test-svc",instance="test-inst"} 67108864.0
     * instance_golang_stack_used{service="test-svc",instance="test-inst"} 4194304.0
     * instance_golang_os_threads_num{service="test-svc",instance="test-inst"} 12.0
     * instance_golang_live_goroutines_num{service="test-svc",instance="test-inst"} 150.0
     * instance_host_cpu_used_rate{service="test-svc",instance="test-inst"} 0.35
     * instance_host_mem_used_rate{service="test-svc",instance="test-inst"} 0.65
     * instance_golang_heap_alloc_objects{service="test-svc",instance="test-inst"} 50000.0
     * instance_golang_gc_heap_goal{service="test-svc",instance="test-inst"} 134217728.0
     * instance_golang_gc_heap_objects{service="test-svc",instance="test-inst"} 25000.0
     * instance_golang_gc_heap_tiny_allocs{service="test-svc",instance="test-inst"} 8000.0
     * instance_golang_gc_limiter_last_enabled{service="test-svc",instance="test-inst"} 0.0
     * instance_golang_gc_stack_starting_size{service="test-svc",instance="test-inst"} 2048.0
     * instance_golang_memory_metadata_other{service="test-svc",instance="test-inst"} 1048576.0
     * instance_golang_memory_os_stacks{service="test-svc",instance="test-inst"} 524288.0
     * instance_golang_memory_other{service="test-svc",instance="test-inst"} 2097152.0
     * instance_golang_memory_profiling_buckets{service="test-svc",instance="test-inst"} 131072.0
     * instance_golang_memory_total{service="test-svc",instance="test-inst"} 268435456.0
     *
     * # --- Increase metrics (counters) ---
     * instance_golang_gc_pause_time{service="test-svc",instance="test-inst"} 5000.0
     * instance_golang_gc_count{service="test-svc",instance="test-inst"} 120.0
     * instance_golang_heap_alloc_size{service="test-svc",instance="test-inst"} 536870912.0
     * instance_golang_heap_frees{service="test-svc",instance="test-inst"} 268435456.0
     * instance_golang_heap_frees_objects{service="test-svc",instance="test-inst"} 40000.0
     * instance_golang_cgo_calls{service="test-svc",instance="test-inst"} 500.0
     *
     * # --- Labeled sum metrics (need 'type' label) ---
     * instance_golang_gc_count_labeled{service="test-svc",instance="test-inst",type="forced"} 10.0
     * instance_golang_gc_count_labeled{service="test-svc",instance="test-inst",type="background"} 110.0
     * instance_golang_memory_heap_labeled{service="test-svc",instance="test-inst",type="released"} 33554432.0
     * instance_golang_memory_heap_labeled{service="test-svc",instance="test-inst",type="in-use"} 67108864.0
     * instance_golang_metadata_mcache_labeled{service="test-svc",instance="test-inst",type="free"} 8192.0
     * instance_golang_metadata_mcache_labeled{service="test-svc",instance="test-inst",type="in-use"} 16384.0
     * instance_golang_metadata_mspan_labeled{service="test-svc",instance="test-inst",type="free"} 32768.0
     * instance_golang_metadata_mspan_labeled{service="test-svc",instance="test-inst",type="in-use"} 65536.0
     *
     * # --- Histogram metrics (need 'le' bucket labels, cumulative values) ---
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.01"} 25.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.025"} 50.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.05"} 80.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.1"} 120.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.25"} 180.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="0.5"} 220.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="1"} 260.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="2.5"} 285.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="5"} 295.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="10"} 299.0
     * instance_golang_gc_heap_allocs_by_size{service="test-svc",instance="test-inst",le="+Inf"} 300.0
     *
     * instance_golang_gc_heap_frees_by_size{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * # ... (same bucket structure as above)
     * instance_golang_gc_heap_frees_by_size{service="test-svc",instance="test-inst",le="+Inf"} 300.0
     *
     * instance_golang_gc_pauses{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * # ... (same bucket structure as above)
     * instance_golang_gc_pauses{service="test-svc",instance="test-inst",le="+Inf"} 300.0
     *
     * instance_golang_sched_latencies{service="test-svc",instance="test-inst",le="0.005"} 10.0
     * # ... (same bucket structure as above)
     * instance_golang_sched_latencies{service="test-svc",instance="test-inst",le="+Inf"} 300.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "service", "test-svc",
            "instance", "test-inst");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Passthrough gauge metrics ---
            .put("instance_golang_heap_alloc",
                single("instance_golang_heap_alloc",
                    scope, 67108864.0 * scale, timestamp))
            .put("instance_golang_stack_used",
                single("instance_golang_stack_used",
                    scope, 4194304.0 * scale, timestamp))
            .put("instance_golang_os_threads_num",
                single("instance_golang_os_threads_num",
                    scope, 12.0 * scale, timestamp))
            .put("instance_golang_live_goroutines_num",
                single("instance_golang_live_goroutines_num",
                    scope, 150.0 * scale, timestamp))
            .put("instance_host_cpu_used_rate",
                single("instance_host_cpu_used_rate",
                    scope, 0.35 * scale, timestamp))
            .put("instance_host_mem_used_rate",
                single("instance_host_mem_used_rate",
                    scope, 0.65 * scale, timestamp))
            .put("instance_golang_heap_alloc_objects",
                single("instance_golang_heap_alloc_objects",
                    scope, 50000.0 * scale, timestamp))
            .put("instance_golang_gc_heap_goal",
                single("instance_golang_gc_heap_goal",
                    scope, 134217728.0 * scale, timestamp))
            .put("instance_golang_gc_heap_objects",
                single("instance_golang_gc_heap_objects",
                    scope, 25000.0 * scale, timestamp))
            .put("instance_golang_gc_heap_tiny_allocs",
                single("instance_golang_gc_heap_tiny_allocs",
                    scope, 8000.0 * scale, timestamp))
            .put("instance_golang_gc_limiter_last_enabled",
                single("instance_golang_gc_limiter_last_enabled",
                    scope, 0.0, timestamp))
            .put("instance_golang_gc_stack_starting_size",
                single("instance_golang_gc_stack_starting_size",
                    scope, 2048.0 * scale, timestamp))
            .put("instance_golang_memory_metadata_other",
                single("instance_golang_memory_metadata_other",
                    scope, 1048576.0 * scale, timestamp))
            .put("instance_golang_memory_os_stacks",
                single("instance_golang_memory_os_stacks",
                    scope, 524288.0 * scale, timestamp))
            .put("instance_golang_memory_other",
                single("instance_golang_memory_other",
                    scope, 2097152.0 * scale, timestamp))
            .put("instance_golang_memory_profiling_buckets",
                single("instance_golang_memory_profiling_buckets",
                    scope, 131072.0 * scale, timestamp))
            .put("instance_golang_memory_total",
                single("instance_golang_memory_total",
                    scope, 268435456.0 * scale, timestamp))

            // --- Increase metrics (counters) ---
            .put("instance_golang_gc_pause_time",
                single("instance_golang_gc_pause_time",
                    scope, 5000.0 * scale, timestamp))
            .put("instance_golang_gc_count",
                single("instance_golang_gc_count",
                    scope, 120.0 * scale, timestamp))
            .put("instance_golang_heap_alloc_size",
                single("instance_golang_heap_alloc_size",
                    scope, 536870912.0 * scale, timestamp))
            .put("instance_golang_heap_frees",
                single("instance_golang_heap_frees",
                    scope, 268435456.0 * scale, timestamp))
            .put("instance_golang_heap_frees_objects",
                single("instance_golang_heap_frees_objects",
                    scope, 40000.0 * scale, timestamp))
            .put("instance_golang_cgo_calls",
                single("instance_golang_cgo_calls",
                    scope, 500.0 * scale, timestamp))

            // --- Labeled sum metrics (need 'type' label) ---
            .put("instance_golang_gc_count_labeled",
                labeledSamples("instance_golang_gc_count_labeled",
                    scope, "type",
                    new String[]{"forced", "background"},
                    new double[]{10.0, 110.0},
                    scale, timestamp))
            .put("instance_golang_memory_heap_labeled",
                labeledSamples("instance_golang_memory_heap_labeled",
                    scope, "type",
                    new String[]{"released", "in-use"},
                    new double[]{33554432.0, 67108864.0},
                    scale, timestamp))
            .put("instance_golang_metadata_mcache_labeled",
                labeledSamples("instance_golang_metadata_mcache_labeled",
                    scope, "type",
                    new String[]{"free", "in-use"},
                    new double[]{8192.0, 16384.0},
                    scale, timestamp))
            .put("instance_golang_metadata_mspan_labeled",
                labeledSamples("instance_golang_metadata_mspan_labeled",
                    scope, "type",
                    new String[]{"free", "in-use"},
                    new double[]{32768.0, 65536.0},
                    scale, timestamp))

            // --- Histogram metrics ---
            .put("instance_golang_gc_heap_allocs_by_size",
                histogramSamples("instance_golang_gc_heap_allocs_by_size",
                    scope, scale, timestamp))
            .put("instance_golang_gc_heap_frees_by_size",
                histogramSamples("instance_golang_gc_heap_frees_by_size",
                    scope, scale, timestamp))
            .put("instance_golang_gc_pauses",
                histogramSamples("instance_golang_gc_pauses",
                    scope, scale, timestamp))
            .put("instance_golang_sched_latencies",
                histogramSamples("instance_golang_sched_latencies",
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
