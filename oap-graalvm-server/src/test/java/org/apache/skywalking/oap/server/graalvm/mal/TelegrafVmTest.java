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
 * Comparison test for telegraf-rules/vm.yaml.
 *
 * <p>This file shares metricPrefix=meter_vm with otel-rules/vm.yaml (combination
 * pattern). Different sample names (telegraf agent format) but same output metrics.
 *
 * <p>Key features:
 * <ul>
 *   <li>Scope: service(['host'], Layer.OS_LINUX)</li>
 *   <li>tagEqual('cpu', 'cpu-total') / tagNotEqual('cpu', 'cpu-total')</li>
 *   <li>.avg(['host', 'cpu']), .avg(['host','device'])</li>
 *   <li>.rate('PT1M'), .irate()</li>
 *   <li>Division: mem_swap_free / mem_swap_total</li>
 * </ul>
 */
class TelegrafVmTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "telegraf-rules/vm.yaml";
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
     * <pre>
     * # cpu metrics — cpu_usage_active has both cpu-total and per-cpu samples
     * cpu_usage_active{host="telegraf-host",cpu="cpu-total"} 45.0
     * cpu_usage_active{host="telegraf-host",cpu="cpu0"} 40.0
     * cpu_usage_active{host="telegraf-host",cpu="cpu1"} 50.0
     * system_load1{host="telegraf-host"} 1.5
     * system_load5{host="telegraf-host"} 2.0
     * system_load15{host="telegraf-host"} 2.5
     *
     * # memory
     * mem_total{host="telegraf-host"} 8589934592.0
     * mem_available{host="telegraf-host"} 4294967296.0
     * mem_used{host="telegraf-host"} 4294967296.0
     *
     * # swap
     * mem_swap_free{host="telegraf-host"} 1073741824.0
     * mem_swap_total{host="telegraf-host"} 2147483648.0
     *
     * # filesystem
     * disk_used_percent{host="telegraf-host",device="sda1"} 60.0
     *
     * # disk I/O — rate needs warmup
     * diskio_read_bytes{host="telegraf-host"} 1048576.0
     * diskio_write_bytes{host="telegraf-host"} 2097152.0
     *
     * # network — irate needs warmup
     * net_bytes_recv{host="telegraf-host"} 5242880.0
     * net_bytes_sent{host="telegraf-host"} 3145728.0
     *
     * # netstat
     * netstat_tcp_established{host="telegraf-host"} 120.0
     * netstat_tcp_time_wait{host="telegraf-host"} 30.0
     * netstat_tcp_listen{host="telegraf-host"} 15.0
     * netstat_udp_socket{host="telegraf-host"} 8.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host", "telegraf-host");

        return ImmutableMap.<String, SampleFamily>builder()
            // cpu_usage_active — needs both cpu-total and per-cpu for tagEqual/tagNotEqual
            .put("cpu_usage_active",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("cpu_usage_active")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("cpu", "cpu-total").build())
                        .value(45.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("cpu_usage_active")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("cpu", "cpu0").build())
                        .value(40.0 * scale).timestamp(timestamp).build(),
                    Sample.builder().name("cpu_usage_active")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("cpu", "cpu1").build())
                        .value(50.0 * scale).timestamp(timestamp).build()
                ).build())

            .put("system_load1", single("system_load1", scope,
                1.5 * scale, timestamp))
            .put("system_load5", single("system_load5", scope,
                2.0 * scale, timestamp))
            .put("system_load15", single("system_load15", scope,
                2.5 * scale, timestamp))

            // memory
            .put("mem_total", single("mem_total", scope,
                8589934592.0 * scale, timestamp))
            .put("mem_available", single("mem_available", scope,
                4294967296.0 * scale, timestamp))
            .put("mem_used", single("mem_used", scope,
                4294967296.0 * scale, timestamp))

            // swap
            .put("mem_swap_free", single("mem_swap_free", scope,
                1073741824.0 * scale, timestamp))
            .put("mem_swap_total", single("mem_swap_total", scope,
                2147483648.0 * scale, timestamp))

            // filesystem
            .put("disk_used_percent",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("disk_used_percent")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope).put("device", "sda1").build())
                        .value(60.0 * scale).timestamp(timestamp).build()
                ).build())

            // disk I/O
            .put("diskio_read_bytes", single("diskio_read_bytes", scope,
                1048576.0 * scale, timestamp))
            .put("diskio_write_bytes", single("diskio_write_bytes", scope,
                2097152.0 * scale, timestamp))

            // network
            .put("net_bytes_recv", single("net_bytes_recv", scope,
                5242880.0 * scale, timestamp))
            .put("net_bytes_sent", single("net_bytes_sent", scope,
                3145728.0 * scale, timestamp))

            // netstat
            .put("netstat_tcp_established", single("netstat_tcp_established",
                scope, 120.0 * scale, timestamp))
            .put("netstat_tcp_time_wait", single("netstat_tcp_time_wait",
                scope, 30.0 * scale, timestamp))
            .put("netstat_tcp_listen", single("netstat_tcp_listen",
                scope, 15.0 * scale, timestamp))
            .put("netstat_udp_socket", single("netstat_udp_socket",
                scope, 8.0 * scale, timestamp))

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
