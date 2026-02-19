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
 * Comparison test for otel-rules/elasticsearch/elasticsearch-node.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagNotEqual — filtering {@code .tagNotEqual('cluster','unknown_cluster')}
 *       on every metric; samples use {@code cluster="es-cluster"}</li>
 *   <li>tagEqual — filtering by area ({@code 'heap'}, {@code 'non-heap'})
 *       for JVM memory metrics</li>
 *   <li>Extra aggregation labels — {@code es_client_node}, {@code es_data_node},
 *       {@code es_ingest_node}, {@code es_master_node} on the rules metric;
 *       {@code pool} on jvm_memory_pool_peak_used; {@code gc} on jvm_gc metrics;
 *       {@code breaker} on breaker metrics; {@code mount} on disk metrics</li>
 *   <li>Arithmetic with constants — {@code * 100} on os_load, {@code * 1000}
 *       on jvm_gc_time, {@code 100 - (a * 100) / b} on disk_usage_percent</li>
 *   <li>Multi-sample subtraction — disk_usage =
 *       {@code size_bytes - available_bytes}</li>
 *   <li>Complex inverse division — search proc_rate =
 *       {@code 1 / ((query_time + fetch_time + scroll_time + suggest_time).rate / query_total.rate)}</li>
 *   <li>irate() — on translog, disk IO, and network metrics</li>
 *   <li>increase('PT1M') — on breakers_tripped, jvm_gc_count, jvm_gc_time</li>
 *   <li>rate('PT1M') — on indexing/search/merge/flush/refresh/docs metrics</li>
 *   <li>Tag closure in expSuffix —
 *       {@code tags.cluster = 'elasticsearch::' + tags.cluster}</li>
 *   <li>Scope: instance(['cluster'], ['name'], Layer.ELASTICSEARCH)</li>
 * </ul>
 */
class ElasticsearchNodeTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/elasticsearch/elasticsearch-node.yaml";
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
     * Build the full input map covering all sample names in
     * elasticsearch-node.yaml. Values are scaled relative to {@code base}
     * so input1 and input2 differ for rate/increase/irate computations.
     *
     * <pre>
     * # Scope labels (consumed by expSuffix after tag closure):
     * #   cluster="es-cluster"  (transformed to "elasticsearch::es-cluster")
     * #   name="es-node-1"
     *
     * # --- Node rules (extra labels for node role aggregation) ---
     * elasticsearch_process_cpu_percent{cluster="es-cluster",name="es-node-1",es_client_node="false",es_data_node="true",es_ingest_node="true",es_master_node="false"} 45.0
     *
     * # --- Open file count ---
     * elasticsearch_process_open_files_count{cluster="es-cluster",name="es-node-1"} 256.0
     *
     * # --- Disk free space ---
     * elasticsearch_filesystem_data_available_bytes{cluster="es-cluster",name="es-node-1"} 107374182400.0
     *
     * # --- JVM memory (with area label for tagEqual filtering) ---
     * elasticsearch_jvm_memory_used_bytes{cluster="es-cluster",name="es-node-1",area="heap"} 536870912.0
     * elasticsearch_jvm_memory_used_bytes{cluster="es-cluster",name="es-node-1",area="non-heap"} 134217728.0
     * elasticsearch_jvm_memory_max_bytes{cluster="es-cluster",name="es-node-1",area="heap"} 1073741824.0
     * elasticsearch_jvm_memory_committed_bytes{cluster="es-cluster",name="es-node-1",area="heap"} 805306368.0
     * elasticsearch_jvm_memory_committed_bytes{cluster="es-cluster",name="es-node-1",area="non-heap"} 201326592.0
     * elasticsearch_jvm_memory_pool_peak_used_bytes{cluster="es-cluster",name="es-node-1",pool="young"} 268435456.0
     * elasticsearch_jvm_memory_pool_peak_used_bytes{cluster="es-cluster",name="es-node-1",pool="old"} 402653184.0
     *
     * # --- JVM GC (with gc label, increase) ---
     * elasticsearch_jvm_gc_collection_seconds_count{cluster="es-cluster",name="es-node-1",gc="young"} 150.0
     * elasticsearch_jvm_gc_collection_seconds_count{cluster="es-cluster",name="es-node-1",gc="old"} 5.0
     * elasticsearch_jvm_gc_collection_seconds_sum{cluster="es-cluster",name="es-node-1",gc="young"} 3.5
     * elasticsearch_jvm_gc_collection_seconds_sum{cluster="es-cluster",name="es-node-1",gc="old"} 1.2
     *
     * # --- CPU ---
     * elasticsearch_os_cpu_percent{cluster="es-cluster",name="es-node-1"} 60.0
     * elasticsearch_os_load1{cluster="es-cluster",name="es-node-1"} 1.5
     * elasticsearch_os_load5{cluster="es-cluster",name="es-node-1"} 1.2
     * elasticsearch_os_load15{cluster="es-cluster",name="es-node-1"} 0.9
     *
     * # --- Translog (irate) ---
     * elasticsearch_indices_translog_operations{cluster="es-cluster",name="es-node-1"} 50000.0
     * elasticsearch_indices_translog_size_in_bytes{cluster="es-cluster",name="es-node-1"} 104857600.0
     *
     * # --- Breakers (with breaker label) ---
     * elasticsearch_breakers_tripped{cluster="es-cluster",name="es-node-1",breaker="fielddata"} 2.0
     * elasticsearch_breakers_tripped{cluster="es-cluster",name="es-node-1",breaker="request"} 1.0
     * elasticsearch_breakers_estimated_size_bytes{cluster="es-cluster",name="es-node-1",breaker="fielddata"} 10485760.0
     * elasticsearch_breakers_estimated_size_bytes{cluster="es-cluster",name="es-node-1",breaker="request"} 20971520.0
     *
     * # --- Disk (with mount label) ---
     * elasticsearch_filesystem_data_available_bytes{cluster="es-cluster",name="es-node-1",mount="/data"} 107374182400.0
     * elasticsearch_filesystem_data_size_bytes{cluster="es-cluster",name="es-node-1",mount="/data"} 214748364800.0
     * elasticsearch_filesystem_io_stats_device_read_size_kilobytes_sum{cluster="es-cluster",name="es-node-1",mount="/data"} 1048576.0
     * elasticsearch_filesystem_io_stats_device_write_size_kilobytes_sum{cluster="es-cluster",name="es-node-1",mount="/data"} 2097152.0
     *
     * # --- Network (irate) ---
     * elasticsearch_transport_tx_size_bytes_total{cluster="es-cluster",name="es-node-1"} 536870912.0
     * elasticsearch_transport_rx_size_bytes_total{cluster="es-cluster",name="es-node-1"} 268435456.0
     *
     * # --- Search operations (rate) ---
     * elasticsearch_indices_search_query_total{cluster="es-cluster",name="es-node-1"} 10000.0
     * elasticsearch_indices_search_query_time_seconds{cluster="es-cluster",name="es-node-1"} 50.0
     * elasticsearch_indices_search_fetch_total{cluster="es-cluster",name="es-node-1"} 8000.0
     * elasticsearch_indices_search_fetch_time_seconds{cluster="es-cluster",name="es-node-1"} 16.0
     * elasticsearch_indices_search_scroll_total{cluster="es-cluster",name="es-node-1"} 500.0
     * elasticsearch_indices_search_scroll_time_seconds{cluster="es-cluster",name="es-node-1"} 2.5
     * elasticsearch_indices_search_suggest_total{cluster="es-cluster",name="es-node-1"} 100.0
     * elasticsearch_indices_search_suggest_time_seconds{cluster="es-cluster",name="es-node-1"} 0.3
     *
     * # --- Indexing operations (rate) ---
     * elasticsearch_indices_indexing_index_total{cluster="es-cluster",name="es-node-1"} 5000.0
     * elasticsearch_indices_indexing_index_time_seconds_total{cluster="es-cluster",name="es-node-1"} 25.0
     * elasticsearch_indices_indexing_delete_total{cluster="es-cluster",name="es-node-1"} 200.0
     *
     * # --- Other operations (rate) ---
     * elasticsearch_indices_merges_total{cluster="es-cluster",name="es-node-1"} 300.0
     * elasticsearch_indices_refresh_total{cluster="es-cluster",name="es-node-1"} 600.0
     * elasticsearch_indices_flush_total{cluster="es-cluster",name="es-node-1"} 50.0
     * elasticsearch_indices_get_exists_total{cluster="es-cluster",name="es-node-1"} 1000.0
     * elasticsearch_indices_get_missing_total{cluster="es-cluster",name="es-node-1"} 50.0
     * elasticsearch_indices_get_total{cluster="es-cluster",name="es-node-1"} 1500.0
     *
     * # --- Document metrics ---
     * elasticsearch_indices_docs{cluster="es-cluster",name="es-node-1"} 1000000.0
     * elasticsearch_indices_docs_deleted{cluster="es-cluster",name="es-node-1"} 5000.0
     * elasticsearch_indices_merges_docs_total{cluster="es-cluster",name="es-node-1"} 25000.0
     * elasticsearch_indices_merges_total_size_bytes_total{cluster="es-cluster",name="es-node-1"} 1073741824.0
     *
     * # --- Segment metrics ---
     * elasticsearch_indices_segments_count{cluster="es-cluster",name="es-node-1"} 120.0
     * elasticsearch_indices_segments_memory_bytes{cluster="es-cluster",name="es-node-1"} 67108864.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "es-cluster",
            "name", "es-node-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // Node rules — extra labels for role aggregation
            .put("elasticsearch_process_cpu_percent",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder()
                        .name("elasticsearch_process_cpu_percent")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("es_client_node", "false")
                            .put("es_data_node", "true")
                            .put("es_ingest_node", "true")
                            .put("es_master_node", "false")
                            .build())
                        .value(45.0 * scale)
                        .timestamp(timestamp)
                        .build()
                ).build())

            // Open file count
            .put("elasticsearch_process_open_files_count",
                single("elasticsearch_process_open_files_count",
                    scope, 256.0 * scale, timestamp))

            // Disk free space (plain scope for all_disk_free_space)
            .put("elasticsearch_filesystem_data_available_bytes",
                SampleFamilyBuilder.newBuilder(
                    mountSample("elasticsearch_filesystem_data_available_bytes",
                        "/data", 107374182400.0 * scale, scope, timestamp)
                ).build())

            // JVM memory — area label required for tagEqual
            .put("elasticsearch_jvm_memory_used_bytes",
                SampleFamilyBuilder.newBuilder(
                    areaSample("elasticsearch_jvm_memory_used_bytes",
                        "heap", 536870912.0 * scale, scope, timestamp),
                    areaSample("elasticsearch_jvm_memory_used_bytes",
                        "non-heap", 134217728.0 * scale, scope, timestamp)
                ).build())
            .put("elasticsearch_jvm_memory_max_bytes",
                SampleFamilyBuilder.newBuilder(
                    areaSample("elasticsearch_jvm_memory_max_bytes",
                        "heap", 1073741824.0, scope, timestamp)
                ).build())
            .put("elasticsearch_jvm_memory_committed_bytes",
                SampleFamilyBuilder.newBuilder(
                    areaSample("elasticsearch_jvm_memory_committed_bytes",
                        "heap", 805306368.0, scope, timestamp),
                    areaSample("elasticsearch_jvm_memory_committed_bytes",
                        "non-heap", 201326592.0, scope, timestamp)
                ).build())

            // JVM memory pool peak — pool label
            .put("elasticsearch_jvm_memory_pool_peak_used_bytes",
                SampleFamilyBuilder.newBuilder(
                    poolSample("young", 268435456.0 * scale, scope, timestamp),
                    poolSample("old", 402653184.0 * scale, scope, timestamp)
                ).build())

            // JVM GC — gc label, increase
            .put("elasticsearch_jvm_gc_collection_seconds_count",
                SampleFamilyBuilder.newBuilder(
                    gcSample("elasticsearch_jvm_gc_collection_seconds_count",
                        "young", 150.0 * scale, scope, timestamp),
                    gcSample("elasticsearch_jvm_gc_collection_seconds_count",
                        "old", 5.0 * scale, scope, timestamp)
                ).build())
            .put("elasticsearch_jvm_gc_collection_seconds_sum",
                SampleFamilyBuilder.newBuilder(
                    gcSample("elasticsearch_jvm_gc_collection_seconds_sum",
                        "young", 3.5 * scale, scope, timestamp),
                    gcSample("elasticsearch_jvm_gc_collection_seconds_sum",
                        "old", 1.2 * scale, scope, timestamp)
                ).build())

            // CPU metrics
            .put("elasticsearch_os_cpu_percent",
                single("elasticsearch_os_cpu_percent",
                    scope, 60.0 * scale, timestamp))
            .put("elasticsearch_os_load1",
                single("elasticsearch_os_load1",
                    scope, 1.5 * scale, timestamp))
            .put("elasticsearch_os_load5",
                single("elasticsearch_os_load5",
                    scope, 1.2 * scale, timestamp))
            .put("elasticsearch_os_load15",
                single("elasticsearch_os_load15",
                    scope, 0.9 * scale, timestamp))

            // Translog — irate
            .put("elasticsearch_indices_translog_operations",
                single("elasticsearch_indices_translog_operations",
                    scope, 50000.0 * scale, timestamp))
            .put("elasticsearch_indices_translog_size_in_bytes",
                single("elasticsearch_indices_translog_size_in_bytes",
                    scope, 104857600.0 * scale, timestamp))

            // Breakers — breaker label
            .put("elasticsearch_breakers_tripped",
                SampleFamilyBuilder.newBuilder(
                    breakerSample("elasticsearch_breakers_tripped",
                        "fielddata", 2.0 * scale, scope, timestamp),
                    breakerSample("elasticsearch_breakers_tripped",
                        "request", 1.0 * scale, scope, timestamp)
                ).build())
            .put("elasticsearch_breakers_estimated_size_bytes",
                SampleFamilyBuilder.newBuilder(
                    breakerSample("elasticsearch_breakers_estimated_size_bytes",
                        "fielddata", 10485760.0, scope, timestamp),
                    breakerSample("elasticsearch_breakers_estimated_size_bytes",
                        "request", 20971520.0, scope, timestamp)
                ).build())

            // Disk — mount label
            .put("elasticsearch_filesystem_data_size_bytes",
                SampleFamilyBuilder.newBuilder(
                    mountSample("elasticsearch_filesystem_data_size_bytes",
                        "/data", 214748364800.0, scope, timestamp)
                ).build())
            .put("elasticsearch_filesystem_io_stats_device_read_size_kilobytes_sum",
                SampleFamilyBuilder.newBuilder(
                    mountSample("elasticsearch_filesystem_io_stats_device_read_size_kilobytes_sum",
                        "/data", 1048576.0 * scale, scope, timestamp)
                ).build())
            .put("elasticsearch_filesystem_io_stats_device_write_size_kilobytes_sum",
                SampleFamilyBuilder.newBuilder(
                    mountSample("elasticsearch_filesystem_io_stats_device_write_size_kilobytes_sum",
                        "/data", 2097152.0 * scale, scope, timestamp)
                ).build())

            // Network — irate
            .put("elasticsearch_transport_tx_size_bytes_total",
                single("elasticsearch_transport_tx_size_bytes_total",
                    scope, 536870912.0 * scale, timestamp))
            .put("elasticsearch_transport_rx_size_bytes_total",
                single("elasticsearch_transport_rx_size_bytes_total",
                    scope, 268435456.0 * scale, timestamp))

            // Search operations — rate
            .put("elasticsearch_indices_search_query_total",
                single("elasticsearch_indices_search_query_total",
                    scope, 10000.0 * scale, timestamp))
            .put("elasticsearch_indices_search_query_time_seconds",
                single("elasticsearch_indices_search_query_time_seconds",
                    scope, 50.0 * scale, timestamp))
            .put("elasticsearch_indices_search_fetch_total",
                single("elasticsearch_indices_search_fetch_total",
                    scope, 8000.0 * scale, timestamp))
            .put("elasticsearch_indices_search_fetch_time_seconds",
                single("elasticsearch_indices_search_fetch_time_seconds",
                    scope, 16.0 * scale, timestamp))
            .put("elasticsearch_indices_search_scroll_total",
                single("elasticsearch_indices_search_scroll_total",
                    scope, 500.0 * scale, timestamp))
            .put("elasticsearch_indices_search_scroll_time_seconds",
                single("elasticsearch_indices_search_scroll_time_seconds",
                    scope, 2.5 * scale, timestamp))
            .put("elasticsearch_indices_search_suggest_total",
                single("elasticsearch_indices_search_suggest_total",
                    scope, 100.0 * scale, timestamp))
            .put("elasticsearch_indices_search_suggest_time_seconds",
                single("elasticsearch_indices_search_suggest_time_seconds",
                    scope, 0.3 * scale, timestamp))

            // Indexing operations — rate
            .put("elasticsearch_indices_indexing_index_total",
                single("elasticsearch_indices_indexing_index_total",
                    scope, 5000.0 * scale, timestamp))
            .put("elasticsearch_indices_indexing_index_time_seconds_total",
                single("elasticsearch_indices_indexing_index_time_seconds_total",
                    scope, 25.0 * scale, timestamp))
            .put("elasticsearch_indices_indexing_delete_total",
                single("elasticsearch_indices_indexing_delete_total",
                    scope, 200.0 * scale, timestamp))

            // Other operations — rate
            .put("elasticsearch_indices_merges_total",
                single("elasticsearch_indices_merges_total",
                    scope, 300.0 * scale, timestamp))
            .put("elasticsearch_indices_refresh_total",
                single("elasticsearch_indices_refresh_total",
                    scope, 600.0 * scale, timestamp))
            .put("elasticsearch_indices_flush_total",
                single("elasticsearch_indices_flush_total",
                    scope, 50.0 * scale, timestamp))
            .put("elasticsearch_indices_get_exists_total",
                single("elasticsearch_indices_get_exists_total",
                    scope, 1000.0 * scale, timestamp))
            .put("elasticsearch_indices_get_missing_total",
                single("elasticsearch_indices_get_missing_total",
                    scope, 50.0 * scale, timestamp))
            .put("elasticsearch_indices_get_total",
                single("elasticsearch_indices_get_total",
                    scope, 1500.0 * scale, timestamp))

            // Document metrics
            .put("elasticsearch_indices_docs",
                single("elasticsearch_indices_docs",
                    scope, 1000000.0 * scale, timestamp))
            .put("elasticsearch_indices_docs_deleted",
                single("elasticsearch_indices_docs_deleted",
                    scope, 5000.0 * scale, timestamp))
            .put("elasticsearch_indices_merges_docs_total",
                single("elasticsearch_indices_merges_docs_total",
                    scope, 25000.0 * scale, timestamp))
            .put("elasticsearch_indices_merges_total_size_bytes_total",
                single("elasticsearch_indices_merges_total_size_bytes_total",
                    scope, 1073741824.0 * scale, timestamp))

            // Segment metrics
            .put("elasticsearch_indices_segments_count",
                single("elasticsearch_indices_segments_count",
                    scope, 120.0, timestamp))
            .put("elasticsearch_indices_segments_memory_bytes",
                single("elasticsearch_indices_segments_memory_bytes",
                    scope, 67108864.0, timestamp))

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

    private static Sample areaSample(final String name,
                                      final String area,
                                      final double value,
                                      final ImmutableMap<String, String> scope,
                                      final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("area", area)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample poolSample(final String pool,
                                      final double value,
                                      final ImmutableMap<String, String> scope,
                                      final long timestamp) {
        return Sample.builder()
            .name("elasticsearch_jvm_memory_pool_peak_used_bytes")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("pool", pool)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample gcSample(final String name,
                                    final String gc,
                                    final double value,
                                    final ImmutableMap<String, String> scope,
                                    final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("gc", gc)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample breakerSample(final String name,
                                         final String breaker,
                                         final double value,
                                         final ImmutableMap<String, String> scope,
                                         final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("breaker", breaker)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample mountSample(final String name,
                                       final String mount,
                                       final double value,
                                       final ImmutableMap<String, String> scope,
                                       final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("mount", mount)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
