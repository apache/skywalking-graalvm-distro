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
 * Comparison test for otel-rules/mongodb/mongodb-node.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagEqual — filtering by conn_type='current'</li>
 *   <li>Tag closure in expSuffix — tags.cluster = 'mongodb::' + tags.cluster</li>
 *   <li>Scope: SERVICE + INSTANCE with service(['cluster'], Layer.MONGODB)
 *       .instance(['cluster'], ['service_instance_id'], Layer.MONGODB)</li>
 *   <li>Multi-sample arithmetic — (MemTotal - MemAvailable) / MemTotal * 100
 *       for memory_usage</li>
 *   <li>Multi-sample CPU arithmetic — sum of 7 CPU component metrics / 10</li>
 *   <li>rate('PT1M'), irate(), increase('PT1M') aggregations</li>
 *   <li>Tagged aggregation labels — doc_op_type, legacy_op_type, op_type,
 *       csr_type, assert_type, count_type, edition, mongodb, member_state</li>
 * </ul>
 */
class MongodbNodeTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/mongodb/mongodb-node.yaml";
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
     * Build the full input map covering all sample names in mongodb-node.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase/irate computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="test-cluster", service_instance_id="mongo-node-1:27017"
     *
     * # --- Uptime ---
     * mongodb_ss_uptime{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 86400.0
     *
     * # --- Operation counters (needs legacy_op_type) ---
     * mongodb_ss_opcounters{cluster="test-cluster",service_instance_id="mongo-node-1:27017",legacy_op_type="insert"} 5000.0
     * mongodb_ss_opcountersRepl{cluster="test-cluster",service_instance_id="mongo-node-1:27017",legacy_op_type="insert"} 1200.0
     *
     * # --- Operation latency (needs op_type) ---
     * mongodb_ss_opLatencies_ops{cluster="test-cluster",service_instance_id="mongo-node-1:27017",op_type="reads"} 8000.0
     * mongodb_ss_opLatencies_latency{cluster="test-cluster",service_instance_id="mongo-node-1:27017",op_type="reads"} 120000.0
     *
     * # --- Memory ---
     * mongodb_sys_memory_MemTotal_kb{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 16777216.0
     * mongodb_sys_memory_MemAvailable_kb{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 8388608.0
     * mongodb_sys_memory_MemFree_kb{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 4194304.0
     * mongodb_sys_memory_SwapFree_kb{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 2097152.0
     *
     * # --- Version info (needs edition, mongodb labels) ---
     * mongodb_version_info{cluster="test-cluster",service_instance_id="mongo-node-1:27017",edition="Community",mongodb="7.0.4"} 1.0
     *
     * # --- Replica set state (needs member_state) ---
     * mongodb_members_self{cluster="test-cluster",service_instance_id="mongo-node-1:27017",member_state="PRIMARY"} 1.0
     *
     * # --- CPU components (7 metrics for cpu_total_percentage) ---
     * mongodb_sys_cpu_user_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 50000.0
     * mongodb_sys_cpu_iowait_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 5000.0
     * mongodb_sys_cpu_system_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 20000.0
     * mongodb_sys_cpu_irq_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 1000.0
     * mongodb_sys_cpu_softirq_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 500.0
     * mongodb_sys_cpu_nice_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 200.0
     * mongodb_sys_cpu_steal_ms{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 100.0
     *
     * # --- Network ---
     * mongodb_ss_network_bytesIn{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 10485760.0
     * mongodb_ss_network_bytesOut{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 5242880.0
     *
     * # --- Filesystem ---
     * mongodb_dbstats_fsUsedSize{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 1.073741824E10
     * mongodb_dbstats_fsTotalSize{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 5.36870912E10
     *
     * # --- Connections (tagEqual conn_type='current') ---
     * mongodb_ss_connections{cluster="test-cluster",service_instance_id="mongo-node-1:27017",conn_type="current"} 150.0
     *
     * # --- Global lock active clients ---
     * mongodb_ss_globalLock_activeClients_total{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 25.0
     * mongodb_ss_globalLock_activeClients_readers{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 15.0
     * mongodb_ss_globalLock_activeClients_writers{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 10.0
     *
     * # --- Transactions ---
     * mongodb_ss_transactions_currentActive{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 3.0
     * mongodb_ss_transactions_currentInactive{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 7.0
     *
     * # --- Document metrics (needs doc_op_type) ---
     * mongodb_ss_metrics_document{cluster="test-cluster",service_instance_id="mongo-node-1:27017",doc_op_type="inserted"} 3000.0
     *
     * # --- Cursor (needs csr_type) ---
     * mongodb_ss_metrics_cursor_open{cluster="test-cluster",service_instance_id="mongo-node-1:27017",csr_type="total"} 42.0
     *
     * # --- Memory stats ---
     * mongodb_ss_mem_virtual{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 4096.0
     * mongodb_ss_mem_resident{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 2048.0
     *
     * # --- Asserts (needs assert_type) ---
     * mongodb_ss_asserts{cluster="test-cluster",service_instance_id="mongo-node-1:27017",assert_type="regular"} 5.0
     *
     * # --- Replication buffer ---
     * mongodb_ss_metrics_repl_buffer_count{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 100.0
     * mongodb_ss_metrics_repl_buffer_sizeBytes{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 1048576.0
     * mongodb_ss_metrics_repl_buffer_maxSizeBytes{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 268435456.0
     *
     * # --- Global lock queue (needs count_type) ---
     * mongodb_ss_globalLock_currentQueue{cluster="test-cluster",service_instance_id="mongo-node-1:27017",count_type="readers"} 2.0
     *
     * # --- Write wait ---
     * mongodb_ss_metrics_getLastError_wtime_num{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 50.0
     * mongodb_ss_metrics_getLastError_wtimeouts{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 3.0
     * mongodb_ss_metrics_getLastError_wtime_totalMillis{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 12000.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "test-cluster",
            "service_instance_id", "mongo-node-1:27017");

        return ImmutableMap.<String, SampleFamily>builder()
            // Uptime
            .put("mongodb_ss_uptime",
                single("mongodb_ss_uptime", scope, 86400.0 * scale, timestamp))

            // Operation counters — needs legacy_op_type
            .put("mongodb_ss_opcounters",
                single("mongodb_ss_opcounters",
                    withTag(scope, "legacy_op_type", "insert"),
                    5000.0 * scale, timestamp))
            .put("mongodb_ss_opcountersRepl",
                single("mongodb_ss_opcountersRepl",
                    withTag(scope, "legacy_op_type", "insert"),
                    1200.0 * scale, timestamp))

            // Operation latency — needs op_type
            .put("mongodb_ss_opLatencies_ops",
                single("mongodb_ss_opLatencies_ops",
                    withTag(scope, "op_type", "reads"),
                    8000.0 * scale, timestamp))
            .put("mongodb_ss_opLatencies_latency",
                single("mongodb_ss_opLatencies_latency",
                    withTag(scope, "op_type", "reads"),
                    120000.0 * scale, timestamp))

            // Memory
            .put("mongodb_sys_memory_MemTotal_kb",
                single("mongodb_sys_memory_MemTotal_kb",
                    scope, 16777216.0, timestamp))
            .put("mongodb_sys_memory_MemAvailable_kb",
                single("mongodb_sys_memory_MemAvailable_kb",
                    scope, 8388608.0, timestamp))
            .put("mongodb_sys_memory_MemFree_kb",
                single("mongodb_sys_memory_MemFree_kb",
                    scope, 4194304.0 * scale, timestamp))
            .put("mongodb_sys_memory_SwapFree_kb",
                single("mongodb_sys_memory_SwapFree_kb",
                    scope, 2097152.0 * scale, timestamp))

            // Version info — needs edition, mongodb labels
            .put("mongodb_version_info",
                single("mongodb_version_info",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("edition", "Community")
                        .put("mongodb", "7.0.4")
                        .build(),
                    1.0, timestamp))

            // Replica set state — needs member_state
            .put("mongodb_members_self",
                single("mongodb_members_self",
                    withTag(scope, "member_state", "PRIMARY"),
                    1.0, timestamp))

            // CPU components (7 metrics summed then / 10)
            .put("mongodb_sys_cpu_user_ms",
                single("mongodb_sys_cpu_user_ms",
                    scope, 50000.0 * scale, timestamp))
            .put("mongodb_sys_cpu_iowait_ms",
                single("mongodb_sys_cpu_iowait_ms",
                    scope, 5000.0 * scale, timestamp))
            .put("mongodb_sys_cpu_system_ms",
                single("mongodb_sys_cpu_system_ms",
                    scope, 20000.0 * scale, timestamp))
            .put("mongodb_sys_cpu_irq_ms",
                single("mongodb_sys_cpu_irq_ms",
                    scope, 1000.0 * scale, timestamp))
            .put("mongodb_sys_cpu_softirq_ms",
                single("mongodb_sys_cpu_softirq_ms",
                    scope, 500.0 * scale, timestamp))
            .put("mongodb_sys_cpu_nice_ms",
                single("mongodb_sys_cpu_nice_ms",
                    scope, 200.0 * scale, timestamp))
            .put("mongodb_sys_cpu_steal_ms",
                single("mongodb_sys_cpu_steal_ms",
                    scope, 100.0 * scale, timestamp))

            // Network
            .put("mongodb_ss_network_bytesIn",
                single("mongodb_ss_network_bytesIn",
                    scope, 10485760.0 * scale, timestamp))
            .put("mongodb_ss_network_bytesOut",
                single("mongodb_ss_network_bytesOut",
                    scope, 5242880.0 * scale, timestamp))

            // Filesystem
            .put("mongodb_dbstats_fsUsedSize",
                single("mongodb_dbstats_fsUsedSize",
                    scope, 10737418240.0 * scale, timestamp))
            .put("mongodb_dbstats_fsTotalSize",
                single("mongodb_dbstats_fsTotalSize",
                    scope, 53687091200.0 * scale, timestamp))

            // Connections — tagEqual('conn_type','current')
            .put("mongodb_ss_connections",
                single("mongodb_ss_connections",
                    withTag(scope, "conn_type", "current"),
                    150.0 * scale, timestamp))

            // Global lock active clients
            .put("mongodb_ss_globalLock_activeClients_total",
                single("mongodb_ss_globalLock_activeClients_total",
                    scope, 25.0 * scale, timestamp))
            .put("mongodb_ss_globalLock_activeClients_readers",
                single("mongodb_ss_globalLock_activeClients_readers",
                    scope, 15.0 * scale, timestamp))
            .put("mongodb_ss_globalLock_activeClients_writers",
                single("mongodb_ss_globalLock_activeClients_writers",
                    scope, 10.0 * scale, timestamp))

            // Transactions
            .put("mongodb_ss_transactions_currentActive",
                single("mongodb_ss_transactions_currentActive",
                    scope, 3.0 * scale, timestamp))
            .put("mongodb_ss_transactions_currentInactive",
                single("mongodb_ss_transactions_currentInactive",
                    scope, 7.0 * scale, timestamp))

            // Document metrics — needs doc_op_type
            .put("mongodb_ss_metrics_document",
                single("mongodb_ss_metrics_document",
                    withTag(scope, "doc_op_type", "inserted"),
                    3000.0 * scale, timestamp))

            // Cursor — needs csr_type
            .put("mongodb_ss_metrics_cursor_open",
                single("mongodb_ss_metrics_cursor_open",
                    withTag(scope, "csr_type", "total"),
                    42.0 * scale, timestamp))

            // Memory stats
            .put("mongodb_ss_mem_virtual",
                single("mongodb_ss_mem_virtual",
                    scope, 4096.0 * scale, timestamp))
            .put("mongodb_ss_mem_resident",
                single("mongodb_ss_mem_resident",
                    scope, 2048.0 * scale, timestamp))

            // Asserts — needs assert_type
            .put("mongodb_ss_asserts",
                single("mongodb_ss_asserts",
                    withTag(scope, "assert_type", "regular"),
                    5.0 * scale, timestamp))

            // Replication buffer
            .put("mongodb_ss_metrics_repl_buffer_count",
                single("mongodb_ss_metrics_repl_buffer_count",
                    scope, 100.0 * scale, timestamp))
            .put("mongodb_ss_metrics_repl_buffer_sizeBytes",
                single("mongodb_ss_metrics_repl_buffer_sizeBytes",
                    scope, 1048576.0 * scale, timestamp))
            .put("mongodb_ss_metrics_repl_buffer_maxSizeBytes",
                single("mongodb_ss_metrics_repl_buffer_maxSizeBytes",
                    scope, 268435456.0 * scale, timestamp))

            // Global lock queue — needs count_type
            .put("mongodb_ss_globalLock_currentQueue",
                single("mongodb_ss_globalLock_currentQueue",
                    withTag(scope, "count_type", "readers"),
                    2.0 * scale, timestamp))

            // Write wait metrics
            .put("mongodb_ss_metrics_getLastError_wtime_num",
                single("mongodb_ss_metrics_getLastError_wtime_num",
                    scope, 50.0 * scale, timestamp))
            .put("mongodb_ss_metrics_getLastError_wtimeouts",
                single("mongodb_ss_metrics_getLastError_wtimeouts",
                    scope, 3.0 * scale, timestamp))
            .put("mongodb_ss_metrics_getLastError_wtime_totalMillis",
                single("mongodb_ss_metrics_getLastError_wtime_totalMillis",
                    scope, 12000.0 * scale, timestamp))

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
