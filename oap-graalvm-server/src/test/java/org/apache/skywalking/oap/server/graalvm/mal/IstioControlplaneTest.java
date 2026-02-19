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
 * Comparison test for otel-rules/istio-controlplane.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix tag closure — tags.cluster = 'istio-ctrl::' + tags.cluster
 *       (cluster modified in-place)</li>
 *   <li>Scope: service(['cluster', 'app'], Layer.MESH_CP)</li>
 *   <li>tagEqual — app='istiod', component='pilot'</li>
 *   <li>tagMatch — type in lds|cds|rds|eds</li>
 *   <li>histogram — pilot_proxy_convergence_time with histogram_percentile</li>
 *   <li>rate/irate — on various counters</li>
 * </ul>
 */
class IstioControlplaneTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/istio-controlplane.yaml";
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
     * Build the full input map covering all sample names in istio-controlplane.yaml.
     *
     * <pre>
     * # Scope labels: cluster="test-cluster", app="istiod"
     * # expSuffix transforms cluster to "istio-ctrl::test-cluster"
     *
     * # --- Resource usage ---
     * istio_build{cluster="test-cluster",app="istiod",component="pilot",tag="1.20.0"} 1.0
     * process_virtual_memory_bytes{cluster="test-cluster",app="istiod"} 2147483648.0
     * process_resident_memory_bytes{cluster="test-cluster",app="istiod"} 536870912.0
     * go_memstats_alloc_bytes{cluster="test-cluster",app="istiod"} 268435456.0
     * go_memstats_heap_inuse_bytes{cluster="test-cluster",app="istiod"} 201326592.0
     * go_memstats_stack_inuse_bytes{cluster="test-cluster",app="istiod"} 8388608.0
     * process_cpu_seconds_total{cluster="test-cluster",app="istiod"} 500.0
     * go_goroutines{cluster="test-cluster",app="istiod"} 150.0
     *
     * # --- Pilot push (tagMatch type) ---
     * pilot_xds_pushes{cluster="test-cluster",app="istiod",type="lds"} 100.0
     * pilot_xds_pushes{cluster="test-cluster",app="istiod",type="cds"} 120.0
     * pilot_xds_pushes{cluster="test-cluster",app="istiod",type="rds"} 80.0
     * pilot_xds_pushes{cluster="test-cluster",app="istiod",type="eds"} 200.0
     *
     * # --- Pilot errors ---
     * pilot_xds_cds_reject{cluster="test-cluster",app="istiod"} 2.0
     * pilot_xds_eds_reject{...} 1.0
     * pilot_xds_rds_reject{...} 0.0
     * pilot_xds_lds_reject{...} 3.0
     * pilot_xds_write_timeout{...} 5.0
     * pilot_total_xds_internal_errors{...} 1.0
     * pilot_total_xds_rejects{...} 6.0
     * pilot_xds_push_context_errors{...} 0.0
     * pilot_xds_push_timeout{...} 2.0
     *
     * # --- Proxy push time (histogram) ---
     * pilot_proxy_convergence_time{cluster="test-cluster",app="istiod",le=...} histogram
     *
     * # --- Conflicts ---
     * pilot_conflict_inbound_listener{...} 1.0
     * pilot_conflict_outbound_listener_http_over_current_tcp{...} 0.0
     * pilot_conflict_outbound_listener_tcp_over_current_tcp{...} 0.0
     * pilot_conflict_outbound_listener_tcp_over_current_http{...} 0.0
     *
     * # --- ADS monitoring ---
     * pilot_virt_services{...} 50.0
     * pilot_services{...} 100.0
     * pilot_xds{...} 30.0
     *
     * # --- Webhooks ---
     * galley_validation_passed{...} 500.0
     * galley_validation_failed{...} 5.0
     * sidecar_injection_success_total{...} 200.0
     * sidecar_injection_failure_total{...} 1.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "test-cluster",
            "app", "istiod");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Resource usage ---
            .put("istio_build",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("istio_build")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("component", "pilot")
                            .put("tag", "1.20.0")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())
            .put("process_virtual_memory_bytes",
                single("process_virtual_memory_bytes", scope,
                    2147483648.0 * scale, timestamp))
            .put("process_resident_memory_bytes",
                single("process_resident_memory_bytes", scope,
                    536870912.0 * scale, timestamp))
            .put("go_memstats_alloc_bytes",
                single("go_memstats_alloc_bytes", scope,
                    268435456.0 * scale, timestamp))
            .put("go_memstats_heap_inuse_bytes",
                single("go_memstats_heap_inuse_bytes", scope,
                    201326592.0 * scale, timestamp))
            .put("go_memstats_stack_inuse_bytes",
                single("go_memstats_stack_inuse_bytes", scope,
                    8388608.0 * scale, timestamp))
            .put("process_cpu_seconds_total",
                single("process_cpu_seconds_total", scope,
                    500.0 * scale, timestamp))
            .put("go_goroutines",
                single("go_goroutines", scope,
                    150.0 * scale, timestamp))

            // --- Pilot push (tagMatch type) ---
            .put("pilot_xds_pushes",
                SampleFamilyBuilder.newBuilder(
                    labeled("pilot_xds_pushes", scope, "type", "lds",
                        100.0 * scale, timestamp),
                    labeled("pilot_xds_pushes", scope, "type", "cds",
                        120.0 * scale, timestamp),
                    labeled("pilot_xds_pushes", scope, "type", "rds",
                        80.0 * scale, timestamp),
                    labeled("pilot_xds_pushes", scope, "type", "eds",
                        200.0 * scale, timestamp)
                ).build())

            // --- Pilot errors ---
            .put("pilot_xds_cds_reject",
                single("pilot_xds_cds_reject", scope,
                    2.0 * scale, timestamp))
            .put("pilot_xds_eds_reject",
                single("pilot_xds_eds_reject", scope,
                    1.0 * scale, timestamp))
            .put("pilot_xds_rds_reject",
                single("pilot_xds_rds_reject", scope,
                    0.0, timestamp))
            .put("pilot_xds_lds_reject",
                single("pilot_xds_lds_reject", scope,
                    3.0 * scale, timestamp))
            .put("pilot_xds_write_timeout",
                single("pilot_xds_write_timeout", scope,
                    5.0 * scale, timestamp))
            .put("pilot_total_xds_internal_errors",
                single("pilot_total_xds_internal_errors", scope,
                    1.0 * scale, timestamp))
            .put("pilot_total_xds_rejects",
                single("pilot_total_xds_rejects", scope,
                    6.0 * scale, timestamp))
            .put("pilot_xds_push_context_errors",
                single("pilot_xds_push_context_errors", scope,
                    0.0, timestamp))
            .put("pilot_xds_push_timeout",
                single("pilot_xds_push_timeout", scope,
                    2.0 * scale, timestamp))

            // --- Proxy push time (histogram) ---
            .put("pilot_proxy_convergence_time",
                histogramSamples("pilot_proxy_convergence_time", scope,
                    scale, timestamp))

            // --- Conflicts ---
            .put("pilot_conflict_inbound_listener",
                single("pilot_conflict_inbound_listener", scope,
                    1.0, timestamp))
            .put("pilot_conflict_outbound_listener_http_over_current_tcp",
                single("pilot_conflict_outbound_listener_http_over_current_tcp",
                    scope, 0.0, timestamp))
            .put("pilot_conflict_outbound_listener_tcp_over_current_tcp",
                single("pilot_conflict_outbound_listener_tcp_over_current_tcp",
                    scope, 0.0, timestamp))
            .put("pilot_conflict_outbound_listener_tcp_over_current_http",
                single("pilot_conflict_outbound_listener_tcp_over_current_http",
                    scope, 0.0, timestamp))

            // --- ADS monitoring ---
            .put("pilot_virt_services",
                single("pilot_virt_services", scope,
                    50.0 * scale, timestamp))
            .put("pilot_services",
                single("pilot_services", scope,
                    100.0 * scale, timestamp))
            .put("pilot_xds",
                single("pilot_xds", scope,
                    30.0 * scale, timestamp))

            // --- Webhooks ---
            .put("galley_validation_passed",
                single("galley_validation_passed", scope,
                    500.0 * scale, timestamp))
            .put("galley_validation_failed",
                single("galley_validation_failed", scope,
                    5.0 * scale, timestamp))
            .put("sidecar_injection_success_total",
                single("sidecar_injection_success_total", scope,
                    200.0 * scale, timestamp))
            .put("sidecar_injection_failure_total",
                single("sidecar_injection_failure_total", scope,
                    1.0 * scale, timestamp))

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

    private static Sample labeled(final String name,
                                   final ImmutableMap<String, String> scope,
                                   final String key, final String val,
                                   final double value,
                                   final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put(key, val)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
