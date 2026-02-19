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
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Comparison test for zabbix-rules/agent.yaml.
 *
 * <p>Zabbix uses 'metrics' field instead of 'metricsRules' in its YAML.
 * Uses loadZabbixRule() for custom parsing.
 *
 * <p>Shares metricPrefix=meter_vm with otel-rules/vm.yaml and
 * telegraf-rules/vm.yaml (combination pattern).
 *
 * <p>Key features:
 * <ul>
 *   <li>Scope: service(['host'], Layer.OS_LINUX)</li>
 *   <li>tagEqual('2', 'avg1'/'avg5'/'avg15') for cpu_load</li>
 *   <li>tagNotEqual('2', 'idle') for cpu_total_percentage</li>
 *   <li>tagEqual('1', 'total'/'available') for memory</li>
 *   <li>tagEqual('2', 'free'/'total'/'pused') for swap</li>
 *   <li>Multi-sample subtraction: memory_used = total - available</li>
 * </ul>
 */
class ZabbixAgentTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "zabbix-rules/agent.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
        Rule rule = loadZabbixRule(YAML_PATH);
        return generateComparisonTests(rule,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    /**
     * Zabbix agent uses numeric tag labels ('1', '2') for item key components.
     * <pre>
     * # CPU load — tag '2' has avg interval
     * system_cpu_load{host="zabbix-host",2="avg1"} 1.5
     * system_cpu_load{host="zabbix-host",2="avg5"} 2.0
     * system_cpu_load{host="zabbix-host",2="avg15"} 2.5
     *
     * # CPU util — tag '2' has mode, need idle + non-idle
     * system_cpu_util{host="zabbix-host",2="user"} 30.0
     * system_cpu_util{host="zabbix-host",2="system"} 10.0
     * system_cpu_util{host="zabbix-host",2="idle"} 55.0
     * system_cpu_util{host="zabbix-host",2="iowait"} 5.0
     *
     * # Memory — tag '1' has size type
     * vm_memory_size{host="zabbix-host",1="total"} 8589934592.0
     * vm_memory_size{host="zabbix-host",1="available"} 4294967296.0
     *
     * # Swap — tag '2' has size type
     * system_swap_size{host="zabbix-host",2="free"} 1073741824.0
     * system_swap_size{host="zabbix-host",2="total"} 2147483648.0
     * system_swap_size{host="zabbix-host",2="pused"} 50.0
     *
     * # Filesystem
     * vfs_fs_inode{host="zabbix-host",1="/",2="pused"} 15.0
     * vfs_fs_size{host="zabbix-host",1="/",2="total"} 107374182400.0
     * vfs_fs_size{host="zabbix-host",1="/",2="used"} 53687091200.0
     *
     * # Disk I/O
     * vfs_dev_read{host="zabbix-host"} 1024.0
     * vfs_dev_write{host="zabbix-host"} 2048.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host", "zabbix-host");

        return ImmutableMap.<String, SampleFamily>builder()
            // CPU load with tag '2' for interval
            .put("system_cpu_load",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("system_cpu_load")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "avg1").build())
                        .value(1.5 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_cpu_load")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "avg5").build())
                        .value(2.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_cpu_load")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "avg15").build())
                        .value(2.5 * scale).timestamp(timestamp).build()
                ).build())

            // CPU util with tag '2' for mode
            .put("system_cpu_util",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("system_cpu_util")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "user").build())
                        .value(30.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_cpu_util")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "system").build())
                        .value(10.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_cpu_util")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "idle").build())
                        .value(55.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_cpu_util")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "iowait").build())
                        .value(5.0 * scale).timestamp(timestamp).build()
                ).build())

            // Memory with tag '1' for type
            .put("vm_memory_size",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("vm_memory_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("1", "total").build())
                        .value(8589934592.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("vm_memory_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("1", "available").build())
                        .value(4294967296.0 * scale).timestamp(timestamp).build()
                ).build())

            // Swap with tag '2' for type
            .put("system_swap_size",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("system_swap_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "free").build())
                        .value(1073741824.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_swap_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "total").build())
                        .value(2147483648.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("system_swap_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("2", "pused").build())
                        .value(50.0 * scale).timestamp(timestamp).build()
                ).build())

            // Filesystem inode
            .put("vfs_fs_inode",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("vfs_fs_inode")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("1", "/").put("2", "pused").build())
                        .value(15.0 * scale).timestamp(timestamp).build()
                ).build())

            // Filesystem size
            .put("vfs_fs_size",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("vfs_fs_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("1", "/").put("2", "total").build())
                        .value(107374182400.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("vfs_fs_size")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("1", "/").put("2", "used").build())
                        .value(53687091200.0 * scale).timestamp(timestamp).build()
                ).build())

            // Disk I/O
            .put("vfs_dev_read", single("vfs_dev_read", scope,
                1024.0 * scale, timestamp))
            .put("vfs_dev_write", single("vfs_dev_write", scope,
                2048.0 * scale, timestamp))

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
}
