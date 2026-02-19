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
 * Comparison test for otel-rules/kong/kong-service.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix tag closure: tags.host_name = 'kong::' + tags.host_name</li>
 *   <li>Scope: service(['host_name'], Layer.KONG)</li>
 *   <li>No expPrefix — tag closure is embedded in expSuffix</li>
 *   <li>histogram — kong_kong_latency_ms, kong_request_latency_ms,
 *       kong_upstream_latency_ms with histogram_percentile([50,75,90,95,99])</li>
 *   <li>rate('PT1M') — on bandwidth, http_status, http_requests, nginx_connections</li>
 *   <li>sum with aggregation labels — direction, route, code, state</li>
 *   <li>Passthrough gauges — datastore_reachable, nginx_metric_errors_total,
 *       nginx_timers</li>
 * </ul>
 */
class KongServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/kong/kong-service.yaml";
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
     * Build the full input map covering all sample names in kong-service.yaml.
     *
     * <pre>
     * # Scope labels (required by expSuffix tag closure and service scope):
     * #   host_name="kong-gateway" (transformed to "kong::kong-gateway" by script)
     * #   service_instance_id="kong-inst-1" (aggregation label)
     *
     * # --- Counter: bandwidth by direction and route ---
     * kong_bandwidth_bytes{host_name="kong-gateway",service_instance_id="kong-inst-1",direction="ingress",route="/api/v1"} 2097152.0
     * kong_bandwidth_bytes{host_name="kong-gateway",service_instance_id="kong-inst-1",direction="egress",route="/api/v1"} 4194304.0
     *
     * # --- Counter: HTTP status by code ---
     * kong_http_requests_total{host_name="kong-gateway",service_instance_id="kong-inst-1",code="200"} 8000.0
     * kong_http_requests_total{host_name="kong-gateway",service_instance_id="kong-inst-1",code="404"} 150.0
     * kong_http_requests_total{host_name="kong-gateway",service_instance_id="kong-inst-1",code="500"} 30.0
     *
     * # --- Counter: nginx metric errors ---
     * kong_nginx_metric_errors_total{host_name="kong-gateway",service_instance_id="kong-inst-1"} 5.0
     *
     * # --- Gauge: datastore reachable ---
     * kong_datastore_reachable{host_name="kong-gateway",service_instance_id="kong-inst-1"} 1.0
     *
     * # --- Gauge: total nginx requests ---
     * kong_nginx_requests_total{host_name="kong-gateway",service_instance_id="kong-inst-1"} 10000.0
     *
     * # --- Gauge: nginx connections by state ---
     * kong_nginx_connections_total{host_name="kong-gateway",service_instance_id="kong-inst-1",state="active"} 120.0
     * kong_nginx_connections_total{host_name="kong-gateway",service_instance_id="kong-inst-1",state="reading"} 8.0
     * kong_nginx_connections_total{host_name="kong-gateway",service_instance_id="kong-inst-1",state="writing"} 15.0
     * kong_nginx_connections_total{host_name="kong-gateway",service_instance_id="kong-inst-1",state="waiting"} 97.0
     *
     * # --- Gauge: nginx timers by state ---
     * kong_nginx_timers{host_name="kong-gateway",service_instance_id="kong-inst-1",state="pending"} 3.0
     * kong_nginx_timers{host_name="kong-gateway",service_instance_id="kong-inst-1",state="running"} 7.0
     *
     * # --- Histogram: kong latency (le buckets) ---
     * kong_kong_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="0.005"} 10.0
     * kong_kong_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="+Inf"} 300.0
     *
     * # --- Histogram: request latency (le buckets) ---
     * kong_request_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="0.005"} 10.0
     * kong_request_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="+Inf"} 300.0
     *
     * # --- Histogram: upstream latency (le buckets) ---
     * kong_upstream_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="0.005"} 10.0
     * kong_upstream_latency_ms{host_name="kong-gateway",service_instance_id="kong-inst-1",le="+Inf"} 300.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "kong-gateway",
            "service_instance_id", "kong-inst-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // kong_bandwidth_bytes — bandwidth by direction and route
            .put("kong_bandwidth_bytes",
                SampleFamilyBuilder.newBuilder(
                    bandwidthSample("ingress", "/api/v1",
                        2097152.0 * scale, scope, timestamp),
                    bandwidthSample("egress", "/api/v1",
                        4194304.0 * scale, scope, timestamp)
                ).build())

            // kong_http_requests_total — HTTP status by code
            .put("kong_http_requests_total",
                SampleFamilyBuilder.newBuilder(
                    codeSample("200", 8000.0 * scale, scope, timestamp),
                    codeSample("404", 150.0 * scale, scope, timestamp),
                    codeSample("500", 30.0 * scale, scope, timestamp)
                ).build())

            // kong_nginx_metric_errors_total — error count
            .put("kong_nginx_metric_errors_total",
                single("kong_nginx_metric_errors_total",
                    scope, 5.0 * scale, timestamp))

            // kong_datastore_reachable — gauge
            .put("kong_datastore_reachable",
                single("kong_datastore_reachable",
                    scope, 1.0, timestamp))

            // kong_nginx_requests_total — total requests
            .put("kong_nginx_requests_total",
                single("kong_nginx_requests_total",
                    scope, 10000.0 * scale, timestamp))

            // kong_nginx_connections_total — connections by state
            .put("kong_nginx_connections_total",
                SampleFamilyBuilder.newBuilder(
                    connectionSample("active", 120.0 * scale, scope, timestamp),
                    connectionSample("reading", 8.0 * scale, scope, timestamp),
                    connectionSample("writing", 15.0 * scale, scope, timestamp),
                    connectionSample("waiting", 97.0 * scale, scope, timestamp)
                ).build())

            // kong_nginx_timers — timers by state
            .put("kong_nginx_timers",
                SampleFamilyBuilder.newBuilder(
                    timerSample("pending", 3.0 * scale, scope, timestamp),
                    timerSample("running", 7.0 * scale, scope, timestamp)
                ).build())

            // kong_kong_latency_ms — histogram
            .put("kong_kong_latency_ms",
                histogramSamples("kong_kong_latency_ms",
                    scope, scale, timestamp))

            // kong_request_latency_ms — histogram
            .put("kong_request_latency_ms",
                histogramSamples("kong_request_latency_ms",
                    scope, scale, timestamp))

            // kong_upstream_latency_ms — histogram
            .put("kong_upstream_latency_ms",
                histogramSamples("kong_upstream_latency_ms",
                    scope, scale, timestamp))

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

    private static Sample bandwidthSample(final String direction,
                                           final String route,
                                           final double value,
                                           final ImmutableMap<String, String> scope,
                                           final long timestamp) {
        return Sample.builder()
            .name("kong_bandwidth_bytes")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("direction", direction)
                .put("route", route)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample codeSample(final String code,
                                      final double value,
                                      final ImmutableMap<String, String> scope,
                                      final long timestamp) {
        return Sample.builder()
            .name("kong_http_requests_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("code", code)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample connectionSample(final String state,
                                            final double value,
                                            final ImmutableMap<String, String> scope,
                                            final long timestamp) {
        return Sample.builder()
            .name("kong_nginx_connections_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample timerSample(final String state,
                                       final double value,
                                       final ImmutableMap<String, String> scope,
                                       final long timestamp) {
        return Sample.builder()
            .name("kong_nginx_timers")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
