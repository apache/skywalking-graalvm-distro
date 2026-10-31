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
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Comparison test for otel-rules/vm.yaml.
 *
 * <p>The rule file reads two source families (node-exporter and native OTel Collector
 * hostmetrics) and adds the equivalent branches with {@code +}. The input carries both
 * families on the same host so every branch of every rule sees real samples.
 *
 * This file exercises:
 * <ul>
 *   <li>tagNotEqual('mode','idle') — filtering CPU seconds / utilization by mode</li>
 *   <li>tagEqual — memory/paging/filesystem state, disk/network direction,
 *       TCP connection protocol + state</li>
 *   <li>Multi-sample subtraction — MemTotal - MemAvailable for memory_used</li>
 *   <li>Multi-sample addition — Buffers + Cached for memory_buff_cache</li>
 *   <li>Division with multiplication — (SwapFree * 100) / SwapTotal for swap percentage,
 *       busy utilization / logical CPU count for cpu_norm_percentage</li>
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
        Instant.parse("2024-01-01T00:00:10Z").toEpochMilli();

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
     *
     * # --- hostmetrics: CPU (utilization is a 0..1 ratio gauge) ---
     * system_cpu_logical_count{node_identifier_host_name="vm-node-1"} 4.0
     * system_cpu_utilization{node_identifier_host_name="vm-node-1",mode="user"} 0.30
     * system_cpu_utilization{node_identifier_host_name="vm-node-1",mode="system"} 0.10
     * system_cpu_utilization{node_identifier_host_name="vm-node-1",mode="wait"} 0.05
     * system_cpu_utilization{node_identifier_host_name="vm-node-1",mode="idle"} 0.55
     * system_cpu_load_average_1m{node_identifier_host_name="vm-node-1"} 1.5
     * system_cpu_load_average_5m{node_identifier_host_name="vm-node-1"} 2.0
     * system_cpu_load_average_15m{node_identifier_host_name="vm-node-1"} 1.8
     *
     * # --- hostmetrics: memory / paging ---
     * system_memory_limit{node_identifier_host_name="vm-node-1"} 17179869184.0
     * system_linux_memory_available{node_identifier_host_name="vm-node-1"} 8589934592.0
     * system_memory_usage{node_identifier_host_name="vm-node-1",state="buffered"} 268435456.0
     * system_memory_usage{node_identifier_host_name="vm-node-1",state="cached"} 2147483648.0
     * system_memory_usage{node_identifier_host_name="vm-node-1",state="slab_reclaimable"} 134217728.0
     * system_memory_usage{node_identifier_host_name="vm-node-1",state="used"} 6442450944.0
     * system_paging_usage{node_identifier_host_name="vm-node-1",device="/dev/sda2",state="used"} 1073741824.0
     * system_paging_usage{node_identifier_host_name="vm-node-1",device="/dev/sda2",state="free"} 3221225472.0
     *
     * # --- hostmetrics: filesystem (free + reserved + used is the capacity) ---
     * system_filesystem_usage{node_identifier_host_name="vm-node-1",mountpoint="/",state="free"} 48318382080.0
     * system_filesystem_usage{node_identifier_host_name="vm-node-1",mountpoint="/",state="reserved"} 5368709120.0
     * system_filesystem_usage{node_identifier_host_name="vm-node-1",mountpoint="/",state="used"} 53687091200.0
     *
     * # --- hostmetrics: disk / network ---
     * system_disk_io{node_identifier_host_name="vm-node-1",device="sda",direction="read"} 1073741824.0
     * system_disk_io{node_identifier_host_name="vm-node-1",device="sda",direction="write"} 2147483648.0
     * system_network_io{node_identifier_host_name="vm-node-1",device="eth0",direction="receive"} 5368709120.0
     * system_network_io{node_identifier_host_name="vm-node-1",device="eth0",direction="transmit"} 2684354560.0
     *
     * # --- hostmetrics: TCP connections (states normalized to lowercase) ---
     * system_network_connections{node_identifier_host_name="vm-node-1",protocol="tcp",state="established"} 110.0
     * system_network_connections{node_identifier_host_name="vm-node-1",protocol="tcp",state="close_wait"} 10.0
     * system_network_connections{node_identifier_host_name="vm-node-1",protocol="tcp",state="time_wait"} 35.0
     * system_network_connections{node_identifier_host_name="vm-node-1",protocol="tcp",state="listen"} 8.0
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

            // hostmetrics CPU
            .put("system_cpu_logical_count",
                single("system_cpu_logical_count", scope, 4.0, timestamp))
            .put("system_cpu_utilization",
                SampleFamilyBuilder.newBuilder(
                    tagged("system_cpu_utilization", scope, "mode", "user", 0.30 * scale, timestamp),
                    tagged("system_cpu_utilization", scope, "mode", "system", 0.10 * scale, timestamp),
                    tagged("system_cpu_utilization", scope, "mode", "wait", 0.05 * scale, timestamp),
                    tagged("system_cpu_utilization", scope, "mode", "idle", 0.55 / scale, timestamp)
                ).build())
            .put("system_cpu_load_average_1m",
                single("system_cpu_load_average_1m", scope, 1.5 * scale, timestamp))
            .put("system_cpu_load_average_5m",
                single("system_cpu_load_average_5m", scope, 2.0 * scale, timestamp))
            .put("system_cpu_load_average_15m",
                single("system_cpu_load_average_15m", scope, 1.8 * scale, timestamp))

            // hostmetrics memory and paging
            .put("system_memory_limit",
                single("system_memory_limit", scope, 17179869184.0, timestamp))
            .put("system_linux_memory_available",
                single("system_linux_memory_available", scope, 8589934592.0 / scale, timestamp))
            .put("system_memory_usage",
                SampleFamilyBuilder.newBuilder(
                    tagged("system_memory_usage", scope, "state", "buffered", 268435456.0 * scale, timestamp),
                    tagged("system_memory_usage", scope, "state", "cached", 2147483648.0 * scale, timestamp),
                    tagged("system_memory_usage", scope, "state", "slab_reclaimable",
                        134217728.0 * scale, timestamp),
                    tagged("system_memory_usage", scope, "state", "used", 6442450944.0 * scale, timestamp)
                ).build())
            .put("system_paging_usage",
                SampleFamilyBuilder.newBuilder(
                    pagingSample(scope, "used", 1073741824.0 * scale, timestamp),
                    pagingSample(scope, "free", 3221225472.0, timestamp)
                ).build())

            // hostmetrics filesystem — free + reserved + used is the capacity
            .put("system_filesystem_usage",
                SampleFamilyBuilder.newBuilder(
                    mountState(scope, "free", 48318382080.0 / scale, timestamp),
                    mountState(scope, "reserved", 5368709120.0, timestamp),
                    mountState(scope, "used", 53687091200.0 * scale, timestamp)
                ).build())

            // hostmetrics disk / network — per device, filtered by direction
            .put("system_disk_io",
                SampleFamilyBuilder.newBuilder(
                    deviceDirection("system_disk_io", scope, "sda", "read", 1073741824.0 * scale, timestamp),
                    deviceDirection("system_disk_io", scope, "sda", "write", 2147483648.0 * scale, timestamp)
                ).build())
            .put("system_network_io",
                SampleFamilyBuilder.newBuilder(
                    deviceDirection("system_network_io", scope, "eth0", "receive",
                        5368709120.0 * scale, timestamp),
                    deviceDirection("system_network_io", scope, "eth0", "transmit",
                        2684354560.0 * scale, timestamp)
                ).build())

            // hostmetrics TCP connections — protocol + state
            .put("system_network_connections",
                SampleFamilyBuilder.newBuilder(
                    tcpConnections(scope, "established", 110.0 * scale, timestamp),
                    tcpConnections(scope, "close_wait", 10.0 * scale, timestamp),
                    tcpConnections(scope, "time_wait", 35.0 * scale, timestamp),
                    tcpConnections(scope, "listen", 8.0, timestamp)
                ).build())

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

    private static Sample tagged(final String name,
                                 final ImmutableMap<String, String> scope,
                                 final String key,
                                 final String value,
                                 final double sampleValue,
                                 final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put(key, value)
                .build())
            .value(sampleValue)
            .timestamp(timestamp)
            .build();
    }

    private static Sample pagingSample(final ImmutableMap<String, String> scope,
                                       final String state,
                                       final double value,
                                       final long timestamp) {
        return Sample.builder()
            .name("system_paging_usage")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("device", "/dev/sda2")
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample mountState(final ImmutableMap<String, String> scope,
                                     final String state,
                                     final double value,
                                     final long timestamp) {
        return Sample.builder()
            .name("system_filesystem_usage")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("mountpoint", "/")
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample deviceDirection(final String name,
                                          final ImmutableMap<String, String> scope,
                                          final String device,
                                          final String direction,
                                          final double value,
                                          final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("device", device)
                .put("direction", direction)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample tcpConnections(final ImmutableMap<String, String> scope,
                                         final String state,
                                         final double value,
                                         final long timestamp) {
        return Sample.builder()
            .name("system_network_connections")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("protocol", "tcp")
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
