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
 * Comparison test for otel-rules/oap.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagMatch — GC collector names (PS Scavenge|Copy|...), thread states (RUNNABLE|BLOCKED|...)</li>
 *   <li>tagEqual — dimensionality='minute'</li>
 *   <li>Conditional tag rewrite — GC names to young_gc_count/old_gc_count,
 *       level '1'/'2' to 'L1 aggregation'/'L2 aggregation'</li>
 *   <li>11 histogram metrics with histogram_percentile</li>
 *   <li>rate/increase/irate — on counters and histograms</li>
 *   <li>Scope: instance(['service'], ['host_name'], Layer.SO11Y_OAP)</li>
 * </ul>
 */
class OapTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/oap.yaml";
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
     * Build the full input map covering all sample names in oap.yaml.
     *
     * <pre>
     * # Scope labels (required by expSuffix):
     * #   service="oap-svc", host_name="oap-host-1"
     *
     * # --- CPU ---
     * process_cpu_seconds_total{service="oap-svc",host_name="oap-host-1"} 500.0
     *
     * # --- JVM Memory ---
     * jvm_memory_bytes_used{service="oap-svc",host_name="oap-host-1",area="heap"} 536870912.0
     * jvm_buffer_pool_used_bytes{service="oap-svc",host_name="oap-host-1",pool="direct"} 1048576.0
     *
     * # --- JVM GC (tagMatch + conditional rewrite) ---
     * jvm_gc_collection_seconds_count{service="oap-svc",host_name="oap-host-1",gc="PS Scavenge"} 1200.0
     * jvm_gc_collection_seconds_count{service="oap-svc",host_name="oap-host-1",gc="PS MarkSweep"} 50.0
     * jvm_gc_collection_seconds_sum{service="oap-svc",host_name="oap-host-1",gc="PS Scavenge"} 12.5
     * jvm_gc_collection_seconds_sum{service="oap-svc",host_name="oap-host-1",gc="PS MarkSweep"} 3.2
     *
     * # --- Trace ---
     * trace_in_latency_count{service="oap-svc",host_name="oap-host-1",protocol="grpc"} 5000.0
     * trace_in_latency{...le buckets...,protocol="grpc"} histogram
     * trace_analysis_error_count{service="oap-svc",host_name="oap-host-1",protocol="grpc"} 10.0
     * spans_dropped_count{service="oap-svc",host_name="oap-host-1",protocol="grpc"} 5.0
     *
     * # --- Mesh ---
     * mesh_analysis_latency_count{service="oap-svc",host_name="oap-host-1"} 3000.0
     * mesh_analysis_latency{...le buckets...} histogram
     * mesh_analysis_error_count{service="oap-svc",host_name="oap-host-1"} 8.0
     *
     * # --- Metrics Aggregation (tagEqual + conditional rewrite) ---
     * metrics_aggregation{...,dimensionality="minute",level="1"} 100.0
     * metrics_aggregation{...,dimensionality="minute",level="2"} 50.0
     * metrics_aggregation_queue_used_percentage{...,level="1",kind="metrics",metricName="test_metric"} 75.0
     *
     * # --- Persistence ---
     * persistence_timer_bulk_execute_latency{...le buckets...} histogram
     * persistence_timer_bulk_prepare_latency{...le buckets...} histogram
     * persistence_timer_bulk_error_count{...} 2.0
     * persistence_timer_bulk_execute_latency_count{...} 800.0
     * persistence_timer_bulk_prepare_latency_count{...} 800.0
     *
     * # --- Metrics Cache ---
     * metrics_persistent_cache{...,status="hit"} 9000.0
     * metrics_persistent_collection_cached_size{...,dimensionality="minute",kind="metrics",metricName="test_metric"} 1000.0
     *
     * # --- JVM Threads ---
     * jvm_threads_current{...} 200.0
     * jvm_threads_daemon{...} 50.0
     * jvm_threads_peak{...} 250.0
     * jvm_threads_state{...,state="RUNNABLE"} 80.0
     * jvm_threads_state{...,state="BLOCKED"} 5.0
     * jvm_threads_state{...,state="WAITING"} 60.0
     * jvm_threads_state{...,state="TIMED_WAITING"} 55.0
     *
     * # --- JVM Classes ---
     * jvm_classes_loaded{...} 15000.0
     * jvm_classes_unloaded_total{...} 100.0
     * jvm_classes_loaded_total{...} 15100.0
     *
     * # --- K8s ALS ---
     * k8s_als_in_count{...} 2000.0
     * k8s_als_drop_count{...} 10.0
     * k8s_als_in_latency{...le buckets...} histogram
     * k8s_als_in_latency_count{...} 2000.0
     * k8s_als_error_streams{...} 3.0
     *
     * # --- OTel ---
     * otel_metrics_latency_count{...} 10000.0
     * otel_logs_latency_count{...} 5000.0
     * otel_spans_latency_count{...} 8000.0
     * otel_spans_dropped{...} 20.0
     * otel_metrics_latency{...le buckets...} histogram
     * otel_logs_latency{...le buckets...} histogram
     * otel_spans_latency{...le buckets...} histogram
     *
     * # --- GraphQL ---
     * graphql_query_latency{...le buckets...} histogram
     * graphql_query_latency_count{...} 1500.0
     * graphql_query_error_count{...} 5.0
     *
     * # --- Circuit Breaker ---
     * watermark_circuit_breaker_break_count{...,listener="metrics",event="break"} 2.0
     * watermark_circuit_breaker_recover_count{...,listener="metrics"} 1.0
     *
     * # --- Storage Write Latency ---
     * elasticsearch_write_latency{...le buckets...,operation="index"} histogram
     * banyandb_write_latency{...le buckets...,catalog="stream",operation="write"} histogram
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "service", "oap-svc",
            "host_name", "oap-host-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- CPU ---
            .put("process_cpu_seconds_total",
                single("process_cpu_seconds_total", scope,
                    500.0 * scale, timestamp))

            // --- JVM Memory ---
            .put("jvm_memory_bytes_used",
                SampleFamilyBuilder.newBuilder(
                    labeled("jvm_memory_bytes_used", scope, "area", "heap",
                        536870912.0 * scale, timestamp)
                ).build())
            .put("jvm_buffer_pool_used_bytes",
                SampleFamilyBuilder.newBuilder(
                    labeled("jvm_buffer_pool_used_bytes", scope, "pool", "direct",
                        1048576.0 * scale, timestamp)
                ).build())

            // --- JVM GC (tagMatch + conditional tag rewrite) ---
            .put("jvm_gc_collection_seconds_count",
                SampleFamilyBuilder.newBuilder(
                    labeled("jvm_gc_collection_seconds_count", scope,
                        "gc", "PS Scavenge", 1200.0 * scale, timestamp),
                    labeled("jvm_gc_collection_seconds_count", scope,
                        "gc", "PS MarkSweep", 50.0 * scale, timestamp)
                ).build())
            .put("jvm_gc_collection_seconds_sum",
                SampleFamilyBuilder.newBuilder(
                    labeled("jvm_gc_collection_seconds_sum", scope,
                        "gc", "PS Scavenge", 12.5 * scale, timestamp),
                    labeled("jvm_gc_collection_seconds_sum", scope,
                        "gc", "PS MarkSweep", 3.2 * scale, timestamp)
                ).build())

            // --- Trace ---
            .put("trace_in_latency_count",
                SampleFamilyBuilder.newBuilder(
                    labeled("trace_in_latency_count", scope,
                        "protocol", "grpc", 5000.0 * scale, timestamp)
                ).build())
            .put("trace_in_latency",
                histogramSamples("trace_in_latency",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope).put("protocol", "grpc").build(),
                    scale, timestamp))
            .put("trace_analysis_error_count",
                SampleFamilyBuilder.newBuilder(
                    labeled("trace_analysis_error_count", scope,
                        "protocol", "grpc", 10.0 * scale, timestamp)
                ).build())
            .put("spans_dropped_count",
                SampleFamilyBuilder.newBuilder(
                    labeled("spans_dropped_count", scope,
                        "protocol", "grpc", 5.0 * scale, timestamp)
                ).build())

            // --- Mesh ---
            .put("mesh_analysis_latency_count",
                single("mesh_analysis_latency_count", scope,
                    3000.0 * scale, timestamp))
            .put("mesh_analysis_latency",
                histogramSamples("mesh_analysis_latency", scope,
                    scale, timestamp))
            .put("mesh_analysis_error_count",
                single("mesh_analysis_error_count", scope,
                    8.0 * scale, timestamp))

            // --- Metrics Aggregation (tagEqual + conditional rewrite) ---
            .put("metrics_aggregation",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("metrics_aggregation")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("dimensionality", "minute")
                            .put("level", "1")
                            .build())
                        .value(100.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("metrics_aggregation")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("dimensionality", "minute")
                            .put("level", "2")
                            .build())
                        .value(50.0 * scale).timestamp(timestamp).build()
                ).build())
            .put("metrics_aggregation_queue_used_percentage",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("metrics_aggregation_queue_used_percentage")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("level", "1")
                            .put("kind", "metrics")
                            .put("metricName", "test_metric")
                            .build())
                        .value(75.0 * scale).timestamp(timestamp).build()
                ).build())

            // --- Persistence ---
            .put("persistence_timer_bulk_execute_latency",
                histogramSamples("persistence_timer_bulk_execute_latency",
                    scope, scale, timestamp))
            .put("persistence_timer_bulk_prepare_latency",
                histogramSamples("persistence_timer_bulk_prepare_latency",
                    scope, scale, timestamp))
            .put("persistence_timer_bulk_error_count",
                single("persistence_timer_bulk_error_count", scope,
                    2.0 * scale, timestamp))
            .put("persistence_timer_bulk_execute_latency_count",
                single("persistence_timer_bulk_execute_latency_count", scope,
                    800.0 * scale, timestamp))
            .put("persistence_timer_bulk_prepare_latency_count",
                single("persistence_timer_bulk_prepare_latency_count", scope,
                    800.0 * scale, timestamp))

            // --- Metrics Cache ---
            .put("metrics_persistent_cache",
                SampleFamilyBuilder.newBuilder(
                    labeled("metrics_persistent_cache", scope,
                        "status", "hit", 9000.0 * scale, timestamp)
                ).build())
            .put("metrics_persistent_collection_cached_size",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("metrics_persistent_collection_cached_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("dimensionality", "minute")
                            .put("kind", "metrics")
                            .put("metricName", "test_metric")
                            .build())
                        .value(1000.0 * scale).timestamp(timestamp).build()
                ).build())

            // --- JVM Threads ---
            .put("jvm_threads_current",
                single("jvm_threads_current", scope,
                    200.0 * scale, timestamp))
            .put("jvm_threads_daemon",
                single("jvm_threads_daemon", scope,
                    50.0 * scale, timestamp))
            .put("jvm_threads_peak",
                single("jvm_threads_peak", scope,
                    250.0 * scale, timestamp))
            .put("jvm_threads_state",
                SampleFamilyBuilder.newBuilder(
                    labeled("jvm_threads_state", scope,
                        "state", "RUNNABLE", 80.0 * scale, timestamp),
                    labeled("jvm_threads_state", scope,
                        "state", "BLOCKED", 5.0 * scale, timestamp),
                    labeled("jvm_threads_state", scope,
                        "state", "WAITING", 60.0 * scale, timestamp),
                    labeled("jvm_threads_state", scope,
                        "state", "TIMED_WAITING", 55.0 * scale, timestamp)
                ).build())

            // --- JVM Classes ---
            .put("jvm_classes_loaded",
                single("jvm_classes_loaded", scope,
                    15000.0 * scale, timestamp))
            .put("jvm_classes_unloaded_total",
                single("jvm_classes_unloaded_total", scope,
                    100.0 * scale, timestamp))
            .put("jvm_classes_loaded_total",
                single("jvm_classes_loaded_total", scope,
                    15100.0 * scale, timestamp))

            // --- K8s ALS ---
            .put("k8s_als_in_count",
                single("k8s_als_in_count", scope,
                    2000.0 * scale, timestamp))
            .put("k8s_als_drop_count",
                single("k8s_als_drop_count", scope,
                    10.0 * scale, timestamp))
            .put("k8s_als_in_latency",
                histogramSamples("k8s_als_in_latency", scope,
                    scale, timestamp))
            .put("k8s_als_in_latency_count",
                single("k8s_als_in_latency_count", scope,
                    2000.0 * scale, timestamp))
            .put("k8s_als_error_streams",
                single("k8s_als_error_streams", scope,
                    3.0 * scale, timestamp))

            // --- OTel ---
            .put("otel_metrics_latency_count",
                single("otel_metrics_latency_count", scope,
                    10000.0 * scale, timestamp))
            .put("otel_logs_latency_count",
                single("otel_logs_latency_count", scope,
                    5000.0 * scale, timestamp))
            .put("otel_spans_latency_count",
                single("otel_spans_latency_count", scope,
                    8000.0 * scale, timestamp))
            .put("otel_spans_dropped",
                single("otel_spans_dropped", scope,
                    20.0 * scale, timestamp))
            .put("otel_metrics_latency",
                histogramSamples("otel_metrics_latency", scope,
                    scale, timestamp))
            .put("otel_logs_latency",
                histogramSamples("otel_logs_latency", scope,
                    scale, timestamp))
            .put("otel_spans_latency",
                histogramSamples("otel_spans_latency", scope,
                    scale, timestamp))

            // --- GraphQL ---
            .put("graphql_query_latency",
                histogramSamples("graphql_query_latency", scope,
                    scale, timestamp))
            .put("graphql_query_latency_count",
                single("graphql_query_latency_count", scope,
                    1500.0 * scale, timestamp))
            .put("graphql_query_error_count",
                single("graphql_query_error_count", scope,
                    5.0 * scale, timestamp))

            // --- Circuit Breaker ---
            .put("watermark_circuit_breaker_break_count",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder()
                        .name("watermark_circuit_breaker_break_count")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("listener", "metrics")
                            .put("event", "break")
                            .build())
                        .value(2.0 * scale).timestamp(timestamp).build()
                ).build())
            .put("watermark_circuit_breaker_recover_count",
                SampleFamilyBuilder.newBuilder(
                    labeled("watermark_circuit_breaker_recover_count", scope,
                        "listener", "metrics", 1.0 * scale, timestamp)
                ).build())

            // --- Storage Write Latency (histograms with extra labels) ---
            .put("elasticsearch_write_latency",
                histogramSamples("elasticsearch_write_latency",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope).put("operation", "index").build(),
                    scale, timestamp))
            .put("banyandb_write_latency",
                histogramSamples("banyandb_write_latency",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("catalog", "stream")
                        .put("operation", "write")
                        .build(),
                    scale, timestamp))

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

    private static Sample labeled(final String name,
                                   final ImmutableMap<String, String> scope,
                                   final String key, final String val,
                                   final double value,
                                   final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put(key, val)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
