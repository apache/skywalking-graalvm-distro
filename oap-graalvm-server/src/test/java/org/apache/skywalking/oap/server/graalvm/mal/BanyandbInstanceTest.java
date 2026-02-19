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
 * Comparison test for otel-rules/banyandb/banyandb-instance.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagEqual — filtering by kind='total', kind='used', kind='bytes_recv',
 *       kind='bytes_sent', method='query', type='file'</li>
 *   <li>Tag closure in expSuffix — tags.host_name = 'banyandb::' + tags.host_name</li>
 *   <li>Scope: SERVICE + INSTANCE with service(['host_name'], Layer.BANYANDB)
 *       .instance(['host_name'], ['service_instance_id'], Layer.BANYANDB)</li>
 *   <li>Multi-sample addition — write_rate = measure_written + stream_written</li>
 *   <li>Multi-sample division — cpu_usage = cpu_seconds / cpu_num,
 *       rss_memory_usage = resident_bytes / memory_total,
 *       disk_usage_all = disk_used / memory_total,
 *       query_latency = latency / started,
 *       merge_file_latency = merge_latency / merge_loop_started,
 *       merge_file_partitions = merged_parts / merge_loop_started</li>
 *   <li>Multi-term addition — write_and_query_errors_rate sums 4 error metrics</li>
 *   <li>rate('PT15S'), downsampling(MIN), downsampling(MAX) aggregations</li>
 *   <li>Multiplication by constants (e.g. *60, *1000)</li>
 *   <li>group label in aggregation for per-group metrics</li>
 * </ul>
 */
class BanyandbInstanceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/banyandb/banyandb-instance.yaml";
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
     * Build the full input map covering all sample names in banyandb-instance.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   host_name="banyandb-host", service_instance_id="banyandb-node-1:17912"
     * # Some metrics also aggregate by group or method.
     *
     * # --- Write rate (two-sample addition) ---
     * banyandb_measure_total_written{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 50000.0
     * banyandb_stream_tst_total_written{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 30000.0
     *
     * # --- Memory (tagEqual kind='total') ---
     * banyandb_system_memory_state{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",kind="total"} 1.7179869184E10
     *
     * # --- Disk (tagEqual kind='used') ---
     * banyandb_system_disk{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",kind="used"} 5.36870912E9
     *
     * # --- Query rate (needs method label) ---
     * banyandb_liaison_grpc_total_started{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",method="query",group="sw_metric"} 8000.0
     *
     * # --- CPU ---
     * banyandb_system_cpu_num{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 8.0
     *
     * # --- Error metrics ---
     * banyandb_liaison_grpc_total_err{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",method="query"} 50.0
     * banyandb_liaison_grpc_total_stream_msg_sent_err{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 20.0
     * banyandb_liaison_grpc_total_stream_msg_received_err{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 10.0
     * banyandb_queue_sub_total_msg_sent_err{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 5.0
     *
     * # --- etcd operation rate (two-sample addition) ---
     * banyandb_liaison_grpc_total_registry_started{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 1200.0
     *
     * # --- Active instance ---
     * up{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 1.0
     *
     * # --- CPU usage (division: cpu_seconds / cpu_num) ---
     * process_cpu_seconds_total{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 3600.0
     *
     * # --- RSS memory usage (division: resident_bytes / memory_total) ---
     * process_resident_memory_bytes{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912"} 4.294967296E9
     *
     * # --- Network (tagEqual kind='bytes_recv' and kind='bytes_sent') ---
     * banyandb_system_net_state{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",kind="bytes_recv"} 1.048576E7
     * banyandb_system_net_state{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",kind="bytes_sent"} 5242880.0
     *
     * # --- Query latency (division: latency / started, both tagEqual method='query') ---
     * banyandb_liaison_grpc_total_latency{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",method="query",group="sw_metric"} 400000.0
     *
     * # --- Storage metrics (with group label) ---
     * banyandb_measure_total_file_elements{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 100000.0
     * banyandb_measure_total_merge_loop_started{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 500.0
     * banyandb_measure_total_merge_latency{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric",type="file"} 25000.0
     * banyandb_measure_total_merged_parts{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric",type="file"} 1500.0
     *
     * # --- Inverted index metrics (with group label) ---
     * banyandb_measure_inverted_index_total_updates{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 20000.0
     * banyandb_stream_storage_inverted_index_total_term_searchers_started{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 3000.0
     * banyandb_measure_inverted_index_total_doc_count{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 500000.0
     *
     * # --- Stream TST metrics (with group label) ---
     * banyandb_stream_tst_inverted_index_total_updates{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 15000.0
     * banyandb_stream_tst_inverted_index_total_term_searchers_started{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 2000.0
     * banyandb_stream_tst_inverted_index_total_doc_count{host_name="banyandb-host",service_instance_id="banyandb-node-1:17912",group="sw_metric"} 300000.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "banyandb-host",
            "service_instance_id", "banyandb-node-1:17912");

        ImmutableMap<String, String> scopeWithGroup = ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put("group", "sw_metric")
            .build();

        return ImmutableMap.<String, SampleFamily>builder()
            // Write rate — two-sample addition with rate
            .put("banyandb_measure_total_written",
                single("banyandb_measure_total_written",
                    scopeWithGroup, 50000.0 * scale, timestamp))
            .put("banyandb_stream_tst_total_written",
                single("banyandb_stream_tst_total_written",
                    scopeWithGroup, 30000.0 * scale, timestamp))

            // Memory — tagEqual('kind','total')
            .put("banyandb_system_memory_state",
                single("banyandb_system_memory_state",
                    withTag(scope, "kind", "total"),
                    17179869184.0, timestamp))

            // Disk — tagEqual('kind','used')
            .put("banyandb_system_disk",
                single("banyandb_system_disk",
                    withTag(scope, "kind", "used"),
                    5368709120.0 * scale, timestamp))

            // Query rate — needs method label for sum(['method',...])
            // Also used with tagEqual('method','query') in query_latency
            .put("banyandb_liaison_grpc_total_started",
                single("banyandb_liaison_grpc_total_started",
                    withTags(scope, "method", "query", "group", "sw_metric"),
                    8000.0 * scale, timestamp))

            // CPU count
            .put("banyandb_system_cpu_num",
                single("banyandb_system_cpu_num",
                    scope, 8.0, timestamp))

            // Error metrics
            .put("banyandb_liaison_grpc_total_err",
                single("banyandb_liaison_grpc_total_err",
                    withTag(scope, "method", "query"),
                    50.0 * scale, timestamp))
            .put("banyandb_liaison_grpc_total_stream_msg_sent_err",
                single("banyandb_liaison_grpc_total_stream_msg_sent_err",
                    scope, 20.0 * scale, timestamp))
            .put("banyandb_liaison_grpc_total_stream_msg_received_err",
                single("banyandb_liaison_grpc_total_stream_msg_received_err",
                    scope, 10.0 * scale, timestamp))
            .put("banyandb_queue_sub_total_msg_sent_err",
                single("banyandb_queue_sub_total_msg_sent_err",
                    scope, 5.0 * scale, timestamp))

            // etcd operation rate — two-sample addition
            .put("banyandb_liaison_grpc_total_registry_started",
                single("banyandb_liaison_grpc_total_registry_started",
                    scope, 1200.0 * scale, timestamp))

            // Active instance
            .put("up",
                single("up", scope, 1.0, timestamp))

            // CPU usage — division: cpu_seconds / cpu_num
            .put("process_cpu_seconds_total",
                single("process_cpu_seconds_total",
                    scope, 3600.0 * scale, timestamp))

            // RSS memory usage — division: resident_bytes / memory_total
            .put("process_resident_memory_bytes",
                single("process_resident_memory_bytes",
                    scope, 4294967296.0 * scale, timestamp))

            // Network — two tagEqual values in one SampleFamily
            .put("banyandb_system_net_state",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder()
                        .name("banyandb_system_net_state")
                        .labels(withTag(scope, "kind", "bytes_recv"))
                        .value(10485760.0 * scale)
                        .timestamp(timestamp)
                        .build(),
                    Sample.builder()
                        .name("banyandb_system_net_state")
                        .labels(withTag(scope, "kind", "bytes_sent"))
                        .value(5242880.0 * scale)
                        .timestamp(timestamp)
                        .build()
                ).build())

            // Query latency — division: latency / started (both tagEqual method='query')
            .put("banyandb_liaison_grpc_total_latency",
                single("banyandb_liaison_grpc_total_latency",
                    withTags(scope, "method", "query", "group", "sw_metric"),
                    400000.0 * scale, timestamp))

            // Storage metrics — with group label
            .put("banyandb_measure_total_file_elements",
                single("banyandb_measure_total_file_elements",
                    scopeWithGroup, 100000.0 * scale, timestamp))
            .put("banyandb_measure_total_merge_loop_started",
                single("banyandb_measure_total_merge_loop_started",
                    scopeWithGroup, 500.0 * scale, timestamp))
            .put("banyandb_measure_total_merge_latency",
                single("banyandb_measure_total_merge_latency",
                    withTags(scope, "group", "sw_metric", "type", "file"),
                    25000.0 * scale, timestamp))
            .put("banyandb_measure_total_merged_parts",
                single("banyandb_measure_total_merged_parts",
                    withTags(scope, "group", "sw_metric", "type", "file"),
                    1500.0 * scale, timestamp))

            // Inverted index metrics — with group label
            .put("banyandb_measure_inverted_index_total_updates",
                single("banyandb_measure_inverted_index_total_updates",
                    scopeWithGroup, 20000.0 * scale, timestamp))
            .put("banyandb_stream_storage_inverted_index_total_term_searchers_started",
                single("banyandb_stream_storage_inverted_index_total_term_searchers_started",
                    scopeWithGroup, 3000.0 * scale, timestamp))
            .put("banyandb_measure_inverted_index_total_doc_count",
                single("banyandb_measure_inverted_index_total_doc_count",
                    scopeWithGroup, 500000.0 * scale, timestamp))

            // Stream TST metrics — with group label
            .put("banyandb_stream_tst_inverted_index_total_updates",
                single("banyandb_stream_tst_inverted_index_total_updates",
                    scopeWithGroup, 15000.0 * scale, timestamp))
            .put("banyandb_stream_tst_inverted_index_total_term_searchers_started",
                single("banyandb_stream_tst_inverted_index_total_term_searchers_started",
                    scopeWithGroup, 2000.0 * scale, timestamp))
            .put("banyandb_stream_tst_inverted_index_total_doc_count",
                single("banyandb_stream_tst_inverted_index_total_doc_count",
                    scopeWithGroup, 300000.0 * scale, timestamp))

            .build();
    }

    private static ImmutableMap<String, String> withTag(
            final ImmutableMap<String, String> base,
            final String key, final String value) {
        return ImmutableMap.<String, String>builder()
            .putAll(base)
            .put(key, value)
            .build();
    }

    private static ImmutableMap<String, String> withTags(
            final ImmutableMap<String, String> base,
            final String key1, final String value1,
            final String key2, final String value2) {
        return ImmutableMap.<String, String>builder()
            .putAll(base)
            .put(key1, value1)
            .put(key2, value2)
            .build();
    }

    private static SampleFamily single(final String name,
                                        final ImmutableMap<String, String> labels,
                                        final double value,
                                        final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .name(name)
                .labels(labels)
                .value(value)
                .timestamp(timestamp)
                .build()
        ).build();
    }
}
