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
 * Comparison test for otel-rules/apisix.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expPrefix — tag closure: tags.service_name = 'APISIX::'+(tags['skywalking_service']?.trim()?:'APISIX')
 *       (builds service_name from skywalking_service or defaults to 'APISIX')</li>
 *   <li>Multiple scope levels per metric — service(), instance(), endpoint()</li>
 *   <li>tagEqual/tagNotEqual — route=''/node='' for matched vs unmatched traffic</li>
 *   <li>tagNotMatch — state not matching 'accepted|handled'</li>
 *   <li>tagNotEqual('route','') — route-based endpoint metrics</li>
 *   <li>tagNotEqual('node','') — node-based endpoint metrics</li>
 *   <li>Tag closure in endpoint — tags.route = 'route/'+tags['route'],
 *       tags.node = 'upstream/'+tags['node']</li>
 *   <li>6 histogram metrics — apisix_http_latency with histogram_percentile</li>
 *   <li>rate/downsampling(LATEST)</li>
 * </ul>
 */
class ApisixTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/apisix.yaml";
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
     * Build the full input map covering all sample names in apisix.yaml.
     *
     * <pre>
     * # expPrefix builds service_name from skywalking_service label.
     * # The original metric does NOT have service_name — the script creates it.
     * # Input labels: skywalking_service="my-gateway" (source for service_name)
     * #   service_instance_id="apisix-inst-1" (for instance scope)
     * #   route="1" and route="" (for matched/unmatched)
     * #   node="10.0.0.1:8080" and node="" (for matched/unmatched)
     *
     * # --- Connections (tagNotMatch state != accepted|handled) ---
     * apisix_nginx_http_current_connections{skywalking_service="my-gateway",service_instance_id="apisix-inst-1",state="active",node="10.0.0.1:8080",route="1"} 150.0
     * apisix_nginx_http_current_connections{skywalking_service="my-gateway",service_instance_id="apisix-inst-1",state="reading",node="10.0.0.1:8080",route="1"} 10.0
     * apisix_nginx_http_current_connections{skywalking_service="my-gateway",service_instance_id="apisix-inst-1",state="writing",node="10.0.0.1:8080",route="1"} 25.0
     * apisix_nginx_http_current_connections{...state="accepted",...} 5000.0  (filtered out)
     * apisix_nginx_http_current_connections{...state="handled",...} 4999.0   (filtered out)
     *
     * # --- HTTP requests ---
     * apisix_http_requests_total{skywalking_service="my-gateway",service_instance_id="apisix-inst-1",node="10.0.0.1:8080",route="1"} 5000.0
     *
     * # --- Bandwidth (matched route!='', unmatched route='') ---
     * apisix_bandwidth{...,type="ingress",route="1",node="10.0.0.1:8080"} 1048576.0
     * apisix_bandwidth{...,type="egress",route="1",node="10.0.0.1:8080"} 5242880.0
     * apisix_bandwidth{...,type="ingress",route="",node=""} 10240.0
     * apisix_bandwidth{...,type="egress",route="",node=""} 51200.0
     *
     * # --- HTTP status ---
     * apisix_http_status{...,code="200",route="1",node="10.0.0.1:8080"} 4000.0
     * apisix_http_status{...,code="500",route="1",node="10.0.0.1:8080"} 50.0
     * apisix_http_status{...,code="200",route="",node=""} 100.0
     * apisix_http_status{...,code="500",route="",node=""} 5.0
     *
     * # --- HTTP latency (histogram, matched and unmatched) ---
     * apisix_http_latency{...,type="request",le=...,route="1",node="10.0.0.1:8080"} histogram
     * apisix_http_latency{...,type="request",le=...,route="",node=""} histogram
     *
     * # --- Shared dict ---
     * apisix_shared_dict_capacity_bytes{...,name="cache"} 1048576.0
     * apisix_shared_dict_free_space_bytes{...,name="cache"} 524288.0
     *
     * # --- etcd ---
     * apisix_etcd_modify_indexes{...,key="/apisix/routes"} 100.0
     * apisix_etcd_reachable{...} 1.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        // Base labels present on all samples — skywalking_service is source
        // for expPrefix tag closure. Do NOT include service_name in input.
        String svcLabel = "my-gateway";
        String instLabel = "apisix-inst-1";
        String routeMatched = "1";
        String nodeMatched = "10.0.0.1:8080";

        // Matched route+node labels
        ImmutableMap<String, String> matched = ImmutableMap.of(
            "skywalking_service", svcLabel,
            "service_instance_id", instLabel,
            "route", routeMatched,
            "node", nodeMatched);

        // Unmatched (empty route and node)
        ImmutableMap<String, String> unmatched = ImmutableMap.of(
            "skywalking_service", svcLabel,
            "service_instance_id", instLabel,
            "route", "",
            "node", "");

        // Instance-level scope (no route/node)
        ImmutableMap<String, String> instScope = ImmutableMap.of(
            "skywalking_service", svcLabel,
            "service_instance_id", instLabel);

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Connections (tagNotMatch filters out accepted/handled) ---
            .put("apisix_nginx_http_current_connections",
                SampleFamilyBuilder.newBuilder(
                    connSample("active", 150.0 * scale, matched, timestamp),
                    connSample("reading", 10.0 * scale, matched, timestamp),
                    connSample("writing", 25.0 * scale, matched, timestamp),
                    connSample("accepted", 5000.0 * scale, matched, timestamp),
                    connSample("handled", 4999.0 * scale, matched, timestamp)
                ).build())

            // --- HTTP requests ---
            .put("apisix_http_requests_total",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("apisix_http_requests_total")
                        .labels(matched)
                        .value(5000.0 * scale).timestamp(timestamp).build()
                ).build())

            // --- Bandwidth (matched + unmatched) ---
            .put("apisix_bandwidth",
                SampleFamilyBuilder.newBuilder(
                    typedSample("apisix_bandwidth", "ingress",
                        matched, 1048576.0 * scale, timestamp),
                    typedSample("apisix_bandwidth", "egress",
                        matched, 5242880.0 * scale, timestamp),
                    typedSample("apisix_bandwidth", "ingress",
                        unmatched, 10240.0 * scale, timestamp),
                    typedSample("apisix_bandwidth", "egress",
                        unmatched, 51200.0 * scale, timestamp)
                ).build())

            // --- HTTP status (matched + unmatched) ---
            .put("apisix_http_status",
                SampleFamilyBuilder.newBuilder(
                    codeSample("200", matched, 4000.0 * scale, timestamp),
                    codeSample("500", matched, 50.0 * scale, timestamp),
                    codeSample("200", unmatched, 100.0 * scale, timestamp),
                    codeSample("500", unmatched, 5.0 * scale, timestamp)
                ).build())

            // --- HTTP latency (histogram — matched + unmatched, with type label) ---
            .put("apisix_http_latency",
                buildLatencyHistogram(scale, timestamp, matched, unmatched))

            // --- Shared dict ---
            .put("apisix_shared_dict_capacity_bytes",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("apisix_shared_dict_capacity_bytes")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(instScope).put("name", "cache").build())
                        .value(1048576.0 * scale).timestamp(timestamp).build()
                ).build())
            .put("apisix_shared_dict_free_space_bytes",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("apisix_shared_dict_free_space_bytes")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(instScope).put("name", "cache").build())
                        .value(524288.0 * scale).timestamp(timestamp).build()
                ).build())

            // --- etcd ---
            .put("apisix_etcd_modify_indexes",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("apisix_etcd_modify_indexes")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(instScope).put("key", "/apisix/routes").build())
                        .value(100.0 * scale).timestamp(timestamp).build()
                ).build())
            .put("apisix_etcd_reachable",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("apisix_etcd_reachable")
                        .labels(instScope)
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            .build();
    }

    /**
     * Build histogram SampleFamily for apisix_http_latency with both
     * matched and unmatched route/node samples. Each set includes a type label.
     */
    private static SampleFamily buildLatencyHistogram(
            final double scale, final long timestamp,
            final ImmutableMap<String, String> matched,
            final ImmutableMap<String, String> unmatched) {
        String[] les = {
            "0.005", "0.01", "0.025", "0.05", "0.1", "0.25",
            "0.5", "1", "2.5", "5", "10", "+Inf"
        };
        double[] vals = {10, 25, 50, 80, 120, 180, 220, 260, 285, 295, 299, 300};

        // 2 sets (matched + unmatched) * 12 buckets each, with type="request"
        Sample[] samples = new Sample[les.length * 2];
        for (int i = 0; i < les.length; i++) {
            samples[i] = Sample.builder()
                .name("apisix_http_latency")
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(matched).put("type", "request").put("le", les[i]).build())
                .value(vals[i] * scale)
                .timestamp(timestamp)
                .build();
            samples[les.length + i] = Sample.builder()
                .name("apisix_http_latency")
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(unmatched).put("type", "request").put("le", les[i]).build())
                .value(vals[i] * scale * 0.1)
                .timestamp(timestamp)
                .build();
        }
        return SampleFamilyBuilder.newBuilder(samples).build();
    }

    private static Sample connSample(final String state,
                                      final double value,
                                      final ImmutableMap<String, String> base,
                                      final long timestamp) {
        return Sample.builder()
            .name("apisix_nginx_http_current_connections")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(base).put("state", state).build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample typedSample(final String name,
                                       final String type,
                                       final ImmutableMap<String, String> base,
                                       final double value,
                                       final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(base).put("type", type).build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample codeSample(final String code,
                                      final ImmutableMap<String, String> base,
                                      final double value,
                                      final long timestamp) {
        return Sample.builder()
            .name("apisix_http_status")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(base).put("code", code).build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
