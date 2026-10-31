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
 * Comparison test for otel-rules/windows.yaml.
 *
 * <p>The rule file reads two source families (windows_exporter 0.31.0+ and native OTel
 * Collector hostmetrics) and adds the equivalent branches with {@code +}. The input carries
 * both families on the same host so every branch of every rule sees real samples.
 *
 * This file exercises:
 * <ul>
 *   <li>tagMatch('mode','^(user|privileged)$') / ('^(user|system)$') — busy CPU modes</li>
 *   <li>tagEqual — memory state, disk/network direction, filesystem/pagefile state</li>
 *   <li>Multi-sample subtraction — total - free (memory_used), commit limit - committed</li>
 *   <li>Division with multiplication — committed * 100 / commit limit, pagefile and
 *       filesystem percentages, cpu_norm_percentage</li>
 *   <li>sum with aggregation labels — sum(['node_identifier_host_name']),
 *       sum(['node_identifier_host_name','mode']),
 *       sum(['node_identifier_host_name','mountpoint'])</li>
 *   <li>rate('PT1M') — on cpu time, disk read/write</li>
 *   <li>irate() — on network receive/transmit</li>
 *   <li>Scope: service(['node_identifier_host_name'], Layer.OS_WINDOWS)</li>
 * </ul>
 */
class WindowsTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/windows.yaml";
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
     * Build the full input map covering all sample names in windows.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/irate computations.
     *
     * <pre>
     * # Scope label (required by expSuffix for all samples):
     * #   node_identifier_host_name="win-server-1"
     *
     * # --- windows_exporter: CPU (privileged already includes interrupt and dpc) ---
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="user"} 3000.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="privileged"} 1500.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="idle"} 60000.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="interrupt"} 200.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="dpc"} 100.0
     *
     * # --- windows_exporter: memory collector (0.29.0+) ---
     * windows_memory_physical_total_bytes{node_identifier_host_name="win-server-1"} 17179869184.0
     * windows_memory_physical_free_bytes{node_identifier_host_name="win-server-1"} 8589934592.0
     * windows_memory_commit_limit{node_identifier_host_name="win-server-1"} 21474836480.0
     * windows_memory_committed_bytes{node_identifier_host_name="win-server-1"} 8589934592.0
     *
     * # --- windows_exporter: disk / network ---
     * windows_logical_disk_read_bytes_total{node_identifier_host_name="win-server-1"} 1073741824.0
     * windows_logical_disk_write_bytes_total{node_identifier_host_name="win-server-1"} 2147483648.0
     * windows_net_bytes_received_total{node_identifier_host_name="win-server-1"} 5368709120.0
     * windows_net_bytes_sent_total{node_identifier_host_name="win-server-1"} 2684354560.0
     *
     * # --- hostmetrics: CPU (system already includes interrupt) ---
     * system_cpu_logical_count{node_identifier_host_name="win-server-1"} 8.0
     * system_cpu_time{node_identifier_host_name="win-server-1",mode="user"} 2000.0
     * system_cpu_time{node_identifier_host_name="win-server-1",mode="system"} 1200.0
     * system_cpu_time{node_identifier_host_name="win-server-1",mode="interrupt"} 80.0
     * system_cpu_time{node_identifier_host_name="win-server-1",mode="idle"} 6000.0
     * system_cpu_load_average_1m{node_identifier_host_name="win-server-1"} 1.25
     * system_cpu_load_average_5m{node_identifier_host_name="win-server-1"} 0.75
     * system_cpu_load_average_15m{node_identifier_host_name="win-server-1"} 0.5
     *
     * # --- hostmetrics: memory / virtual memory (Memory\Commit Limit, Memory\Committed Bytes) ---
     * system_memory_limit{node_identifier_host_name="win-server-1"} 17179869184.0
     * system_memory_usage{node_identifier_host_name="win-server-1",state="used"} 10737418240.0
     * system_memory_usage{node_identifier_host_name="win-server-1",state="free"} 6442450944.0
     * system_virtual_memory_commit_limit{node_identifier_host_name="win-server-1"} 21474836480.0
     * system_virtual_memory_committed{node_identifier_host_name="win-server-1"} 12884901888.0
     *
     * # --- hostmetrics: disk / network ---
     * system_disk_io{node_identifier_host_name="win-server-1",device="C:",direction="read"} 1073741824.0
     * system_disk_io{node_identifier_host_name="win-server-1",device="C:",direction="write"} 2147483648.0
     * system_network_io{node_identifier_host_name="win-server-1",device="Ethernet",direction="receive"} 5368709120.0
     * system_network_io{node_identifier_host_name="win-server-1",device="Ethernet",direction="transmit"} 2684354560.0
     *
     * # --- hostmetrics: filesystem / file handles / pagefile ---
     * system_filesystem_usage{node_identifier_host_name="win-server-1",mountpoint="C:",state="free"} 64424509440.0
     * system_filesystem_usage{node_identifier_host_name="win-server-1",mountpoint="C:",state="reserved"} 1073741824.0
     * system_filesystem_usage{node_identifier_host_name="win-server-1",mountpoint="C:",state="used"} 42949672960.0
     * system_filehandles_allocated{node_identifier_host_name="win-server-1"} 32000.0
     * system_paging_usage{node_identifier_host_name="win-server-1",device="C:\\pagefile.sys",state="used"} 1073741824.0
     * system_paging_usage{node_identifier_host_name="win-server-1",device="C:\\pagefile.sys",state="free"} 3221225472.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "node_identifier_host_name", "win-server-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // windows_exporter CPU time — busy modes are user + privileged
            .put("windows_cpu_time_total",
                SampleFamilyBuilder.newBuilder(
                    tagged("windows_cpu_time_total", scope, "mode", "user", 3000.0 * scale, timestamp),
                    tagged("windows_cpu_time_total", scope, "mode", "privileged", 1500.0 * scale, timestamp),
                    tagged("windows_cpu_time_total", scope, "mode", "idle", 60000.0 * scale, timestamp),
                    tagged("windows_cpu_time_total", scope, "mode", "interrupt", 200.0 * scale, timestamp),
                    tagged("windows_cpu_time_total", scope, "mode", "dpc", 100.0 * scale, timestamp)
                ).build())

            // windows_exporter memory collector — physical and commit
            .put("windows_memory_physical_total_bytes",
                single("windows_memory_physical_total_bytes",
                    scope, 17179869184.0, timestamp))
            .put("windows_memory_physical_free_bytes",
                single("windows_memory_physical_free_bytes",
                    scope, 8589934592.0 * scale / 2, timestamp))
            .put("windows_memory_commit_limit",
                single("windows_memory_commit_limit",
                    scope, 21474836480.0, timestamp))
            .put("windows_memory_committed_bytes",
                single("windows_memory_committed_bytes",
                    scope, 8589934592.0 * scale, timestamp))

            // windows_exporter disk — rate metrics
            .put("windows_logical_disk_read_bytes_total",
                single("windows_logical_disk_read_bytes_total",
                    scope, 1073741824.0 * scale, timestamp))
            .put("windows_logical_disk_write_bytes_total",
                single("windows_logical_disk_write_bytes_total",
                    scope, 2147483648.0 * scale, timestamp))

            // windows_exporter network — irate metrics
            .put("windows_net_bytes_received_total",
                single("windows_net_bytes_received_total",
                    scope, 5368709120.0 * scale, timestamp))
            .put("windows_net_bytes_sent_total",
                single("windows_net_bytes_sent_total",
                    scope, 2684354560.0 * scale, timestamp))

            // hostmetrics CPU — busy modes are user + system
            .put("system_cpu_logical_count",
                single("system_cpu_logical_count", scope, 8.0, timestamp))
            .put("system_cpu_time",
                SampleFamilyBuilder.newBuilder(
                    tagged("system_cpu_time", scope, "mode", "user", 2000.0 * scale, timestamp),
                    tagged("system_cpu_time", scope, "mode", "system", 1200.0 * scale, timestamp),
                    tagged("system_cpu_time", scope, "mode", "interrupt", 80.0 * scale, timestamp),
                    tagged("system_cpu_time", scope, "mode", "idle", 6000.0 * scale, timestamp)
                ).build())
            .put("system_cpu_load_average_1m",
                single("system_cpu_load_average_1m", scope, 1.25 * scale, timestamp))
            .put("system_cpu_load_average_5m",
                single("system_cpu_load_average_5m", scope, 0.75 * scale, timestamp))
            .put("system_cpu_load_average_15m",
                single("system_cpu_load_average_15m", scope, 0.5 * scale, timestamp))

            // hostmetrics memory and virtual memory
            .put("system_memory_limit",
                single("system_memory_limit", scope, 17179869184.0, timestamp))
            .put("system_memory_usage",
                SampleFamilyBuilder.newBuilder(
                    tagged("system_memory_usage", scope, "state", "used", 10737418240.0, timestamp),
                    tagged("system_memory_usage", scope, "state", "free", 6442450944.0, timestamp)
                ).build())
            .put("system_virtual_memory_commit_limit",
                single("system_virtual_memory_commit_limit", scope, 21474836480.0, timestamp))
            .put("system_virtual_memory_committed",
                single("system_virtual_memory_committed", scope, 12884901888.0 * scale / 2, timestamp))

            // hostmetrics disk / network — per device, filtered by direction
            .put("system_disk_io",
                SampleFamilyBuilder.newBuilder(
                    deviceDirection("system_disk_io", scope, "C:", "read", 1073741824.0 * scale, timestamp),
                    deviceDirection("system_disk_io", scope, "C:", "write", 2147483648.0 * scale, timestamp)
                ).build())
            .put("system_network_io",
                SampleFamilyBuilder.newBuilder(
                    deviceDirection("system_network_io", scope, "Ethernet", "receive",
                        5368709120.0 * scale, timestamp),
                    deviceDirection("system_network_io", scope, "Ethernet", "transmit",
                        2684354560.0 * scale, timestamp)
                ).build())

            // hostmetrics filesystem (free + reserved + used is the capacity)
            .put("system_filesystem_usage",
                SampleFamilyBuilder.newBuilder(
                    mountState(scope, "free", 64424509440.0, timestamp),
                    mountState(scope, "reserved", 1073741824.0, timestamp),
                    mountState(scope, "used", 42949672960.0, timestamp)
                ).build())
            .put("system_filehandles_allocated",
                single("system_filehandles_allocated", scope, 32000.0 * scale, timestamp))

            // hostmetrics pagefile
            .put("system_paging_usage",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder()
                        .name("system_paging_usage")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("device", "C:\\pagefile.sys").put("state", "used").build())
                        .value(1073741824.0 * scale)
                        .timestamp(timestamp)
                        .build(),
                    Sample.builder()
                        .name("system_paging_usage")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("device", "C:\\pagefile.sys").put("state", "free").build())
                        .value(3221225472.0)
                        .timestamp(timestamp)
                        .build()
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

    private static Sample mountState(final ImmutableMap<String, String> scope,
                                     final String state,
                                     final double value,
                                     final long timestamp) {
        return Sample.builder()
            .name("system_filesystem_usage")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("mountpoint", "C:")
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
