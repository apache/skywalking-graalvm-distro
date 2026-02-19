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
 * Comparison test for otel-rules/windows.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagNotEqual('mode','idle') — filtering CPU time by mode</li>
 *   <li>Multi-sample subtraction — cs_physical_memory_bytes -
 *       os_physical_memory_free_bytes for memory_used</li>
 *   <li>Division with multiplication — (virtual_memory_free * 100) /
 *       virtual_memory_bytes for virtual memory percentage</li>
 *   <li>sum with aggregation labels — sum(['node_identifier_host_name']),
 *       sum(['node_identifier_host_name','mode'])</li>
 *   <li>rate('PT1M') — on cpu_time_total, disk read/write</li>
 *   <li>irate() — on network receive/transmit</li>
 *   <li>Scope: service(['node_identifier_host_name'], Layer.OS_WINDOWS)</li>
 * </ul>
 */
class WindowsTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/windows.yaml";
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
     * Build the full input map covering all sample names in windows.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/irate computations.
     *
     * <pre>
     * # Scope label (required by expSuffix for all samples):
     * #   node_identifier_host_name="win-server-1"
     *
     * # --- CPU metrics (multiple modes for tagNotEqual/sum) ---
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="user"} 3000.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="privileged"} 1500.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="idle"} 60000.0
     * windows_cpu_time_total{node_identifier_host_name="win-server-1",mode="interrupt"} 200.0
     *
     * # --- Physical memory metrics ---
     * windows_cs_physical_memory_bytes{node_identifier_host_name="win-server-1"} 17179869184.0
     * windows_os_physical_memory_free_bytes{node_identifier_host_name="win-server-1"} 8589934592.0
     *
     * # --- Virtual memory metrics ---
     * windows_os_virtual_memory_free_bytes{node_identifier_host_name="win-server-1"} 12884901888.0
     * windows_os_virtual_memory_bytes{node_identifier_host_name="win-server-1"} 21474836480.0
     *
     * # --- Disk metrics ---
     * windows_logical_disk_read_bytes_total{node_identifier_host_name="win-server-1"} 1073741824.0
     * windows_logical_disk_write_bytes_total{node_identifier_host_name="win-server-1"} 2147483648.0
     *
     * # --- Network metrics ---
     * windows_net_bytes_received_total{node_identifier_host_name="win-server-1"} 5368709120.0
     * windows_net_bytes_sent_total{node_identifier_host_name="win-server-1"} 2684354560.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "node_identifier_host_name", "win-server-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // CPU time — multiple mode values for tagNotEqual and sum
            .put("windows_cpu_time_total",
                SampleFamilyBuilder.newBuilder(
                    cpuSample("user", 3000.0 * scale, scope, timestamp),
                    cpuSample("privileged", 1500.0 * scale, scope, timestamp),
                    cpuSample("idle", 60000.0 * scale, scope, timestamp),
                    cpuSample("interrupt", 200.0 * scale, scope, timestamp)
                ).build())

            // Physical memory — multi-sample subtraction
            .put("windows_cs_physical_memory_bytes",
                single("windows_cs_physical_memory_bytes",
                    scope, 17179869184.0 * scale, timestamp))
            .put("windows_os_physical_memory_free_bytes",
                single("windows_os_physical_memory_free_bytes",
                    scope, 8589934592.0 * scale, timestamp))

            // Virtual memory — division with multiplication
            .put("windows_os_virtual_memory_free_bytes",
                single("windows_os_virtual_memory_free_bytes",
                    scope, 12884901888.0 * scale, timestamp))
            .put("windows_os_virtual_memory_bytes",
                single("windows_os_virtual_memory_bytes",
                    scope, 21474836480.0 * scale, timestamp))

            // Disk — rate metrics
            .put("windows_logical_disk_read_bytes_total",
                single("windows_logical_disk_read_bytes_total",
                    scope, 1073741824.0 * scale, timestamp))
            .put("windows_logical_disk_write_bytes_total",
                single("windows_logical_disk_write_bytes_total",
                    scope, 2147483648.0 * scale, timestamp))

            // Network — irate metrics
            .put("windows_net_bytes_received_total",
                single("windows_net_bytes_received_total",
                    scope, 5368709120.0 * scale, timestamp))
            .put("windows_net_bytes_sent_total",
                single("windows_net_bytes_sent_total",
                    scope, 2684354560.0 * scale, timestamp))

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
            .name("windows_cpu_time_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("mode", mode)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
