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
 * Comparison test for otel-rules/nginx/nginx-service.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expPrefix — tag closure: tags.service = 'nginx::' + tags.service</li>
 *   <li>Scope: service(['service'], Layer.NGINX)</li>
 *   <li>tagMatch — regex filtering for 4xx (400|401|403|404|405)
 *       and 5xx (500|502|503|504) status codes</li>
 *   <li>histogram — nginx_http_latency with histogram_percentile([50,75,90,95,99])</li>
 *   <li>rate('PT1M') — on http_requests, http_bandwidth, http_status</li>
 *   <li>increase('PT1M') — on http_requests_increment, http_4xx/5xx_requests_increment</li>
 *   <li>sum with aggregation labels — status, type, state, le</li>
 * </ul>
 */
class NginxServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/nginx/nginx-service.yaml";
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
     * Build the full input map covering all sample names in nginx-service.yaml.
     *
     * <pre>
     * # Scope labels (required by expPrefix tag closure and expSuffix):
     * #   service="my-nginx" (transformed to "nginx::my-nginx" by script)
     * #   service_instance_id="nginx-inst-1" (aggregation label)
     *
     * # --- Counter: total requests (multiple status samples for tagMatch) ---
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="200"} 5000.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="301"} 200.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="400"} 50.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="401"} 10.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="403"} 5.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="404"} 80.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="405"} 3.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="500"} 20.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="502"} 8.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="503"} 12.0
     * nginx_http_requests_total{service="my-nginx",service_instance_id="nginx-inst-1",status="504"} 2.0
     *
     * # --- Histogram: latency (le buckets) ---
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.005"} 10.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.01"} 25.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.025"} 50.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.05"} 80.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.1"} 120.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.25"} 180.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="0.5"} 220.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="1"} 260.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="2.5"} 285.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="5"} 295.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="10"} 299.0
     * nginx_http_latency{service="my-nginx",service_instance_id="nginx-inst-1",le="+Inf"} 300.0
     *
     * # --- Gauge: bandwidth by type ---
     * nginx_http_size_bytes{service="my-nginx",service_instance_id="nginx-inst-1",type="request"} 1048576.0
     * nginx_http_size_bytes{service="my-nginx",service_instance_id="nginx-inst-1",type="response"} 5242880.0
     *
     * # --- Gauge: connections by state ---
     * nginx_http_connections{service="my-nginx",service_instance_id="nginx-inst-1",state="active"} 150.0
     * nginx_http_connections{service="my-nginx",service_instance_id="nginx-inst-1",state="reading"} 10.0
     * nginx_http_connections{service="my-nginx",service_instance_id="nginx-inst-1",state="writing"} 25.0
     * nginx_http_connections{service="my-nginx",service_instance_id="nginx-inst-1",state="waiting"} 115.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "service", "my-nginx",
            "service_instance_id", "nginx-inst-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // nginx_http_requests_total — multiple status samples for tagMatch
            .put("nginx_http_requests_total",
                SampleFamilyBuilder.newBuilder(
                    statusSample("200", 5000.0 * scale, scope, timestamp),
                    statusSample("301", 200.0 * scale, scope, timestamp),
                    statusSample("400", 50.0 * scale, scope, timestamp),
                    statusSample("401", 10.0 * scale, scope, timestamp),
                    statusSample("403", 5.0 * scale, scope, timestamp),
                    statusSample("404", 80.0 * scale, scope, timestamp),
                    statusSample("405", 3.0 * scale, scope, timestamp),
                    statusSample("500", 20.0 * scale, scope, timestamp),
                    statusSample("502", 8.0 * scale, scope, timestamp),
                    statusSample("503", 12.0 * scale, scope, timestamp),
                    statusSample("504", 2.0 * scale, scope, timestamp)
                ).build())

            // nginx_http_latency — histogram with le buckets
            .put("nginx_http_latency",
                histogramSamples("nginx_http_latency", scope, scale, timestamp))

            // nginx_http_size_bytes — bandwidth by type
            .put("nginx_http_size_bytes",
                SampleFamilyBuilder.newBuilder(
                    typeSample("request", 1048576.0 * scale, scope, timestamp),
                    typeSample("response", 5242880.0 * scale, scope, timestamp)
                ).build())

            // nginx_http_connections — connections by state
            .put("nginx_http_connections",
                SampleFamilyBuilder.newBuilder(
                    stateSample("active", 150.0 * scale, scope, timestamp),
                    stateSample("reading", 10.0 * scale, scope, timestamp),
                    stateSample("writing", 25.0 * scale, scope, timestamp),
                    stateSample("waiting", 115.0 * scale, scope, timestamp)
                ).build())

            .build();
    }

    private static Sample statusSample(final String status,
                                        final double value,
                                        final ImmutableMap<String, String> scope,
                                        final long timestamp) {
        return Sample.builder()
            .name("nginx_http_requests_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("status", status)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample typeSample(final String type,
                                      final double value,
                                      final ImmutableMap<String, String> scope,
                                      final long timestamp) {
        return Sample.builder()
            .name("nginx_http_size_bytes")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("type", type)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample stateSample(final String state,
                                       final double value,
                                       final ImmutableMap<String, String> scope,
                                       final long timestamp) {
        return Sample.builder()
            .name("nginx_http_connections")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
