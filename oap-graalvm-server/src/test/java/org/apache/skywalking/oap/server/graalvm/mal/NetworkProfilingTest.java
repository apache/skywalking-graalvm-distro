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
 * Comparison test for meter-analyzer-config/network-profiling.yaml.
 *
 * <p>This file has a complex expPrefix with forEach closures that call
 * ProcessRegistry.generateVirtualLocalProcess/generateVirtualRemoteProcess.
 * To avoid requiring ProcessRegistry initialization, input samples include
 * pre-set client_process_id and server_process_id labels so the forEach
 * skips the generation path.
 *
 * <p>The second forEach sets 'component' based on protocol/is_ssl labels.
 *
 * <p>Key features:
 * <ul>
 *   <li>expPrefix: forEach(['client','server'], ...).forEach(['component'], ...)</li>
 *   <li>expSuffix: processRelation('side', ['service'], ['instance'],
 *       'client_process_id', 'server_process_id', 'component')</li>
 *   <li>.sum([...]).downsampling(SUM_PER_MIN) for counters</li>
 *   <li>.histogram().histogram_percentile([50,75,90,95,99]).downsampling(SUM) for histograms</li>
 * </ul>
 */
class NetworkProfilingTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "meter-analyzer-config/network-profiling.yaml";
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
     * All samples need process relation labels. The expPrefix forEach skips
     * ProcessRegistry calls when client_process_id and server_process_id
     * are already set. Component is always overwritten by the second forEach
     * based on protocol/is_ssl.
     *
     * <pre>
     * # Labels for all samples:
     * #   service="rover-svc", instance="rover-inst-1", side="client",
     * #   client_process_id="proc-a", server_process_id="proc-b",
     * #   protocol="http", is_ssl="false"
     * # (component will be set to "49" by the expression's forEach)
     *
     * # TCP client counters
     * rover_net_p_client_write_counts_counter{...} 500.0
     * rover_net_p_client_write_bytes_counter{...} 102400.0
     * rover_net_p_client_write_exe_time_counter{...} 250.0
     * rover_net_p_client_read_counts_counter{...} 480.0
     * rover_net_p_client_read_bytes_counter{...} 204800.0
     * rover_net_p_client_read_exe_time_counter{...} 200.0
     * rover_net_p_client_write_rtt_exe_time_counter{...} 50.0
     * rover_net_p_client_connect_counts_counter{...} 10.0
     * rover_net_p_client_connect_exe_time_counter{...} 30.0
     * rover_net_p_client_close_counts_counter{...} 8.0
     * rover_net_p_client_close_exe_time_counter{...} 15.0
     * rover_net_p_client_retransmit_counts_counter{...} 3.0
     * rover_net_p_client_drop_counts_counter{...} 1.0
     *
     * # TCP server counters (similar)
     * # HTTP/1.x counters
     * # Histogram samples with le buckets
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        // Base labels needed for processRelation scope + forEach closures
        ImmutableMap<String, String> scope = ImmutableMap.<String, String>builder()
            .put("service", "rover-svc")
            .put("instance", "rover-inst-1")
            .put("side", "client")
            .put("client_process_id", "proc-a")
            .put("server_process_id", "proc-b")
            .put("protocol", "http")
            .put("is_ssl", "false")
            .build();

        ImmutableMap.Builder<String, SampleFamily> builder =
            ImmutableMap.<String, SampleFamily>builder();

        // TCP client counters
        builder.put("rover_net_p_client_write_counts_counter",
            single("rover_net_p_client_write_counts_counter", scope,
                500.0 * scale, timestamp));
        builder.put("rover_net_p_client_write_bytes_counter",
            single("rover_net_p_client_write_bytes_counter", scope,
                102400.0 * scale, timestamp));
        builder.put("rover_net_p_client_write_exe_time_counter",
            single("rover_net_p_client_write_exe_time_counter", scope,
                250.0 * scale, timestamp));
        builder.put("rover_net_p_client_read_counts_counter",
            single("rover_net_p_client_read_counts_counter", scope,
                480.0 * scale, timestamp));
        builder.put("rover_net_p_client_read_bytes_counter",
            single("rover_net_p_client_read_bytes_counter", scope,
                204800.0 * scale, timestamp));
        builder.put("rover_net_p_client_read_exe_time_counter",
            single("rover_net_p_client_read_exe_time_counter", scope,
                200.0 * scale, timestamp));
        builder.put("rover_net_p_client_write_rtt_exe_time_counter",
            single("rover_net_p_client_write_rtt_exe_time_counter", scope,
                50.0 * scale, timestamp));
        builder.put("rover_net_p_client_connect_counts_counter",
            single("rover_net_p_client_connect_counts_counter", scope,
                10.0 * scale, timestamp));
        builder.put("rover_net_p_client_connect_exe_time_counter",
            single("rover_net_p_client_connect_exe_time_counter", scope,
                30.0 * scale, timestamp));
        builder.put("rover_net_p_client_close_counts_counter",
            single("rover_net_p_client_close_counts_counter", scope,
                8.0 * scale, timestamp));
        builder.put("rover_net_p_client_close_exe_time_counter",
            single("rover_net_p_client_close_exe_time_counter", scope,
                15.0 * scale, timestamp));
        builder.put("rover_net_p_client_retransmit_counts_counter",
            single("rover_net_p_client_retransmit_counts_counter", scope,
                3.0 * scale, timestamp));
        builder.put("rover_net_p_client_drop_counts_counter",
            single("rover_net_p_client_drop_counts_counter", scope,
                1.0 * scale, timestamp));

        // TCP client histograms
        builder.put("rover_net_p_client_write_rtt_histogram",
            histogramSamples("rover_net_p_client_write_rtt_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_client_write_exe_time_histogram",
            histogramSamples("rover_net_p_client_write_exe_time_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_client_read_exe_time_histogram",
            histogramSamples("rover_net_p_client_read_exe_time_histogram",
                scope, scale, timestamp));

        // TCP server counters
        builder.put("rover_net_p_server_write_counts_counter",
            single("rover_net_p_server_write_counts_counter", scope,
                400.0 * scale, timestamp));
        builder.put("rover_net_p_server_write_bytes_counter",
            single("rover_net_p_server_write_bytes_counter", scope,
                81920.0 * scale, timestamp));
        builder.put("rover_net_p_server_write_exe_time_counter",
            single("rover_net_p_server_write_exe_time_counter", scope,
                200.0 * scale, timestamp));
        builder.put("rover_net_p_server_read_counts_counter",
            single("rover_net_p_server_read_counts_counter", scope,
                380.0 * scale, timestamp));
        builder.put("rover_net_p_server_read_bytes_counter",
            single("rover_net_p_server_read_bytes_counter", scope,
                163840.0 * scale, timestamp));
        builder.put("rover_net_p_server_read_exe_time_counter",
            single("rover_net_p_server_read_exe_time_counter", scope,
                180.0 * scale, timestamp));
        builder.put("rover_net_p_server_write_rtt_exe_time_counter",
            single("rover_net_p_server_write_rtt_exe_time_counter", scope,
                40.0 * scale, timestamp));
        builder.put("rover_net_p_server_connect_counts_counter",
            single("rover_net_p_server_connect_counts_counter", scope,
                8.0 * scale, timestamp));
        builder.put("rover_net_p_server_connect_exe_time_counter",
            single("rover_net_p_server_connect_exe_time_counter", scope,
                25.0 * scale, timestamp));
        builder.put("rover_net_p_server_close_counts_counter",
            single("rover_net_p_server_close_counts_counter", scope,
                6.0 * scale, timestamp));
        builder.put("rover_net_p_server_close_exe_time_counter",
            single("rover_net_p_server_close_exe_time_counter", scope,
                12.0 * scale, timestamp));
        builder.put("rover_net_p_server_retransmit_counts_counter",
            single("rover_net_p_server_retransmit_counts_counter", scope,
                2.0 * scale, timestamp));
        builder.put("rover_net_p_server_drop_counts_counter",
            single("rover_net_p_server_drop_counts_counter", scope,
                1.0 * scale, timestamp));

        // TCP server histograms
        builder.put("rover_net_p_server_write_rtt_histogram",
            histogramSamples("rover_net_p_server_write_rtt_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_server_write_exe_time_histogram",
            histogramSamples("rover_net_p_server_write_exe_time_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_server_read_exe_time_histogram",
            histogramSamples("rover_net_p_server_read_exe_time_histogram",
                scope, scale, timestamp));

        // HTTP/1.x counters
        builder.put("rover_net_p_http1_request_counter",
            single("rover_net_p_http1_request_counter", scope,
                300.0 * scale, timestamp));

        ImmutableMap<String, String> scopeWithCode =
            ImmutableMap.<String, String>builder()
                .putAll(scope).put("code", "200").build();
        builder.put("rover_net_p_http1_response_status_counter",
            single("rover_net_p_http1_response_status_counter", scopeWithCode,
                280.0 * scale, timestamp));

        builder.put("rover_net_p_http1_request_package_size_avg",
            single("rover_net_p_http1_request_package_size_avg", scope,
                512.0 * scale, timestamp));
        builder.put("rover_net_p_http1_response_package_size_avg",
            single("rover_net_p_http1_response_package_size_avg", scope,
                2048.0 * scale, timestamp));

        // HTTP/1.x histograms
        builder.put("rover_net_p_http1_request_package_size_histogram",
            histogramSamples("rover_net_p_http1_request_package_size_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_http1_response_package_size_histogram",
            histogramSamples("rover_net_p_http1_response_package_size_histogram",
                scope, scale, timestamp));

        builder.put("rover_net_p_http1_client_duration_avg",
            single("rover_net_p_http1_client_duration_avg", scope,
                100.0 * scale, timestamp));
        builder.put("rover_net_p_http1_server_duration_avg",
            single("rover_net_p_http1_server_duration_avg", scope,
                80.0 * scale, timestamp));

        builder.put("rover_net_p_http1_client_duration_histogram",
            histogramSamples("rover_net_p_http1_client_duration_histogram",
                scope, scale, timestamp));
        builder.put("rover_net_p_http1_server_duration_histogram",
            histogramSamples("rover_net_p_http1_server_duration_histogram",
                scope, scale, timestamp));

        return builder.build();
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
