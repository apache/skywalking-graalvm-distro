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
 * Comparison test for otel-rules/kong/kong-endpoint.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix tag closure: tags.host_name = 'kong::' + tags.host_name</li>
 *   <li>Scope: endpoint(['host_name'], ['route'], Layer.KONG)</li>
 *   <li>tagNotEqual — filtering out empty route values on all histogram metrics</li>
 *   <li>histogram — kong_kong_latency_ms, kong_request_latency_ms,
 *       kong_upstream_latency_ms with histogram_percentile([50,75,90,95,99])</li>
 *   <li>rate('PT1M') — on bandwidth, http_status</li>
 *   <li>Aggregation by route instead of service_instance_id</li>
 * </ul>
 */
class KongEndpointTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/kong/kong-endpoint.yaml";
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
     * Build the full input map covering all sample names in kong-endpoint.yaml.
     *
     * <pre>
     * # Scope labels (required by expSuffix tag closure and endpoint scope):
     * #   host_name="kong-gateway" (transformed to "kong::kong-gateway" by script)
     * #   route="/api/v1/users" (endpoint identifier, non-empty for tagNotEqual)
     *
     * # --- Counter: bandwidth by direction ---
     * kong_bandwidth_bytes{host_name="kong-gateway",direction="ingress",route="/api/v1/users"} 2097152.0
     * kong_bandwidth_bytes{host_name="kong-gateway",direction="egress",route="/api/v1/users"} 4194304.0
     *
     * # --- Counter: HTTP status by code ---
     * kong_http_requests_total{host_name="kong-gateway",route="/api/v1/users",code="200"} 8000.0
     * kong_http_requests_total{host_name="kong-gateway",route="/api/v1/users",code="404"} 150.0
     * kong_http_requests_total{host_name="kong-gateway",route="/api/v1/users",code="500"} 30.0
     *
     * # --- Histogram: kong latency (le buckets, tagNotEqual('route','')) ---
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.005"} 10.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.01"} 25.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.025"} 50.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.05"} 80.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.1"} 120.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.25"} 180.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.5"} 220.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="1"} 260.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="2.5"} 285.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="5"} 295.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="10"} 299.0
     * kong_kong_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="+Inf"} 300.0
     *
     * # --- Histogram: request latency (le buckets, tagNotEqual('route','')) ---
     * kong_request_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.005"} 10.0
     * kong_request_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="+Inf"} 300.0
     *
     * # --- Histogram: upstream latency (le buckets, tagNotEqual('route','')) ---
     * kong_upstream_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="0.005"} 10.0
     * kong_upstream_latency_ms{host_name="kong-gateway",route="/api/v1/users",le="+Inf"} 300.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "kong-gateway",
            "route", "/api/v1/users");

        return ImmutableMap.<String, SampleFamily>builder()
            // kong_bandwidth_bytes — bandwidth by direction
            .put("kong_bandwidth_bytes",
                SampleFamilyBuilder.newBuilder(
                    bandwidthSample("ingress",
                        2097152.0 * scale, scope, timestamp),
                    bandwidthSample("egress",
                        4194304.0 * scale, scope, timestamp)
                ).build())

            // kong_http_requests_total — HTTP status by code
            .put("kong_http_requests_total",
                SampleFamilyBuilder.newBuilder(
                    codeSample("200", 8000.0 * scale, scope, timestamp),
                    codeSample("404", 150.0 * scale, scope, timestamp),
                    codeSample("500", 30.0 * scale, scope, timestamp)
                ).build())

            // kong_kong_latency_ms — histogram (non-empty route for tagNotEqual)
            .put("kong_kong_latency_ms",
                histogramSamples("kong_kong_latency_ms",
                    scope, scale, timestamp))

            // kong_request_latency_ms — histogram (non-empty route for tagNotEqual)
            .put("kong_request_latency_ms",
                histogramSamples("kong_request_latency_ms",
                    scope, scale, timestamp))

            // kong_upstream_latency_ms — histogram (non-empty route for tagNotEqual)
            .put("kong_upstream_latency_ms",
                histogramSamples("kong_upstream_latency_ms",
                    scope, scale, timestamp))

            .build();
    }

    private static Sample bandwidthSample(final String direction,
                                           final double value,
                                           final ImmutableMap<String, String> scope,
                                           final long timestamp) {
        return Sample.builder()
            .name("kong_bandwidth_bytes")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("direction", direction)
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
}
