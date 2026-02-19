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
 * Comparison test for otel-rules/vm.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagNotEqual('mode','idle') — filtering CPU seconds by mode</li>
 *   <li>Multi-sample subtraction — MemTotal - MemAvailable for memory_used</li>
 *   <li>Multi-sample addition — Buffers + Cached for memory_buff_cache</li>
 *   <li>Division with multiplication — (SwapFree * 100) / SwapTotal for swap percentage</li>
 *   <li>sum with aggregation labels — sum(['node_identifier_host_name']),
 *       sum(['node_identifier_host_name','mode']),
 *       sum(['node_identifier_host_name','mountpoint'])</li>
 *   <li>rate('PT1M') — on cpu_seconds_total, disk read/write</li>
 *   <li>irate() — on network receive/transmit</li>
 *   <li>Scope: service(['node_identifier_host_name'], Layer.OS_LINUX)</li>
 * </ul>
 */
class VmTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/vm.yaml";
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
     * Build the full input map covering all sample names in vm.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/irate computations.
     *
     * <pre>
     * # Scope label (required by expSuffix for all samples):
     * #   node_identifier_host_name="vm-node-1"
     *
     * # --- CPU metrics (multiple modes for tagNotEqual/sum) ---
     * node_cpu_seconds_total{node_identifier_host_name="vm-node-1",mode="user"} 5000.0
     * node_cpu_seconds_total{node_identifier_host_name="vm-node-1",mode="system"} 2000.0
     * node_cpu_seconds_total{node_identifier_host_name="vm-node-1",mode="idle"} 80000.0
     * node_cpu_seconds_total{node_identifier_host_name="vm-node-1",mode="iowait"} 500.0
     *
     * # --- CPU load ---
     * node_load1{node_identifier_host_name="vm-node-1"} 1.5
     * node_load5{node_identifier_host_name="vm-node-1"} 2.0
     * node_load15{node_identifier_host_name="vm-node-1"} 1.8
     *
     * # --- Memory metrics ---
     * node_memory_MemTotal_bytes{node_identifier_host_name="vm-node-1"} 17179869184.0
     * node_memory_MemAvailable_bytes{node_identifier_host_name="vm-node-1"} 8589934592.0
     * node_memory_Buffers_bytes{node_identifier_host_name="vm-node-1"} 268435456.0
     * node_memory_Cached_bytes{node_identifier_host_name="vm-node-1"} 2147483648.0
     * node_memory_SwapFree_bytes{node_identifier_host_name="vm-node-1"} 4294967296.0
     * node_memory_SwapTotal_bytes{node_identifier_host_name="vm-node-1"} 8589934592.0
     *
     * # --- Filesystem metrics (need mountpoint label) ---
     * node_filesystem_avail_bytes{node_identifier_host_name="vm-node-1",mountpoint="/"} 53687091200.0
     * node_filesystem_size_bytes{node_identifier_host_name="vm-node-1",mountpoint="/"} 107374182400.0
     *
     * # --- Disk metrics ---
     * node_disk_read_bytes_total{node_identifier_host_name="vm-node-1"} 1073741824.0
     * node_disk_written_bytes_total{node_identifier_host_name="vm-node-1"} 2147483648.0
     *
     * # --- Network metrics ---
     * node_network_receive_bytes_total{node_identifier_host_name="vm-node-1"} 5368709120.0
     * node_network_transmit_bytes_total{node_identifier_host_name="vm-node-1"} 2684354560.0
     *
     * # --- Netstat metrics ---
     * node_netstat_Tcp_CurrEstab{node_identifier_host_name="vm-node-1"} 120.0
     * node_sockstat_TCP_tw{node_identifier_host_name="vm-node-1"} 35.0
     * node_sockstat_TCP_alloc{node_identifier_host_name="vm-node-1"} 200.0
     * node_sockstat_sockets_used{node_identifier_host_name="vm-node-1"} 450.0
     * node_sockstat_UDP_inuse{node_identifier_host_name="vm-node-1"} 12.0
     *
     * # --- File descriptor metrics ---
     * node_filefd_allocated{node_identifier_host_name="vm-node-1"} 3200.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "node_identifier_host_name", "vm-node-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // CPU seconds — multiple mode values for tagNotEqual and sum
            .put("node_cpu_seconds_total",
                SampleFamilyBuilder.newBuilder(
                    cpuSample("user", 5000.0 * scale, scope, timestamp),
                    cpuSample("system", 2000.0 * scale, scope, timestamp),
                    cpuSample("idle", 80000.0 * scale, scope, timestamp),
                    cpuSample("iowait", 500.0 * scale, scope, timestamp)
                ).build())

            // CPU load (passthrough with * 100)
            .put("node_load1",
                single("node_load1", scope, 1.5 * scale, timestamp))
            .put("node_load5",
                single("node_load5", scope, 2.0 * scale, timestamp))
            .put("node_load15",
                single("node_load15", scope, 1.8 * scale, timestamp))

            // Memory — multi-sample subtraction/addition/division
            .put("node_memory_MemTotal_bytes",
                single("node_memory_MemTotal_bytes",
                    scope, 17179869184.0 * scale, timestamp))
            .put("node_memory_MemAvailable_bytes",
                single("node_memory_MemAvailable_bytes",
                    scope, 8589934592.0 * scale, timestamp))
            .put("node_memory_Buffers_bytes",
                single("node_memory_Buffers_bytes",
                    scope, 268435456.0 * scale, timestamp))
            .put("node_memory_Cached_bytes",
                single("node_memory_Cached_bytes",
                    scope, 2147483648.0 * scale, timestamp))
            .put("node_memory_SwapFree_bytes",
                single("node_memory_SwapFree_bytes",
                    scope, 4294967296.0 * scale, timestamp))
            .put("node_memory_SwapTotal_bytes",
                single("node_memory_SwapTotal_bytes",
                    scope, 8589934592.0 * scale, timestamp))

            // Filesystem — need mountpoint label for sum
            .put("node_filesystem_avail_bytes",
                single("node_filesystem_avail_bytes",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope).put("mountpoint", "/").build(),
                    53687091200.0 * scale, timestamp))
            .put("node_filesystem_size_bytes",
                single("node_filesystem_size_bytes",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope).put("mountpoint", "/").build(),
                    107374182400.0 * scale, timestamp))

            // Disk — rate metrics
            .put("node_disk_read_bytes_total",
                single("node_disk_read_bytes_total",
                    scope, 1073741824.0 * scale, timestamp))
            .put("node_disk_written_bytes_total",
                single("node_disk_written_bytes_total",
                    scope, 2147483648.0 * scale, timestamp))

            // Network — irate metrics
            .put("node_network_receive_bytes_total",
                single("node_network_receive_bytes_total",
                    scope, 5368709120.0 * scale, timestamp))
            .put("node_network_transmit_bytes_total",
                single("node_network_transmit_bytes_total",
                    scope, 2684354560.0 * scale, timestamp))

            // Netstat — passthrough
            .put("node_netstat_Tcp_CurrEstab",
                single("node_netstat_Tcp_CurrEstab",
                    scope, 120.0 * scale, timestamp))
            .put("node_sockstat_TCP_tw",
                single("node_sockstat_TCP_tw",
                    scope, 35.0 * scale, timestamp))
            .put("node_sockstat_TCP_alloc",
                single("node_sockstat_TCP_alloc",
                    scope, 200.0 * scale, timestamp))
            .put("node_sockstat_sockets_used",
                single("node_sockstat_sockets_used",
                    scope, 450.0 * scale, timestamp))
            .put("node_sockstat_UDP_inuse",
                single("node_sockstat_UDP_inuse",
                    scope, 12.0 * scale, timestamp))

            // File descriptors — passthrough
            .put("node_filefd_allocated",
                single("node_filefd_allocated",
                    scope, 3200.0 * scale, timestamp))

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

    private static Sample cpuSample(final String mode,
                                     final double value,
                                     final ImmutableMap<String, String> scope,
                                     final long timestamp) {
        return Sample.builder()
            .name("node_cpu_seconds_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("mode", mode)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
