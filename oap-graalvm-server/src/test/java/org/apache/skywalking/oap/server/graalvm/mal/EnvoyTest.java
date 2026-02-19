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
 * Comparison test for envoy-metrics-rules/envoy.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix — instance(['app'], ['instance'], Layer.MESH_DP)</li>
 *   <li>Simple passthrough metrics — server_memory_heap_size, server_memory_allocated,
 *       server_memory_physical_size, server_total_connections, server_parent_connections,
 *       server_concurrency, server_envoy_bug_failures</li>
 *   <li>.max(['app', 'instance']) aggregation on several server metrics</li>
 *   <li>envoy_cluster_metrics with dual tagMatch on metrics_name —
 *       first matches suffix (e.g. .+membership_healthy), second matches
 *       prefix (cluster.outbound.+|cluster.inbound.+)</li>
 *   <li>tagNotMatch('cluster_name', '.+kube-system') on cluster_membership_healthy</li>
 *   <li>.sum(['app', 'instance', 'cluster_name']) aggregation on cluster metrics</li>
 *   <li>.increase('PT1M') on cluster counters (cx_total, rq_total, lb_healthy_panic,
 *       upstream_cx_none_healthy)</li>
 * </ul>
 */
class EnvoyTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "envoy-metrics-rules/envoy.yaml";
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
     * Build the full input map covering all sample names in envoy.yaml.
     *
     * <pre>
     * # Scope labels: app="envoy-app", instance="envoy-inst-1"
     * # expSuffix: instance(['app'], ['instance'], Layer.MESH_DP)
     *
     * # --- Simple server metrics ---
     * server_memory_heap_size{app="envoy-app",instance="envoy-inst-1"} 104857600.0
     * server_memory_allocated{app="envoy-app",instance="envoy-inst-1"} 52428800.0
     * server_memory_physical_size{app="envoy-app",instance="envoy-inst-1"} 209715200.0
     * server_total_connections{app="envoy-app",instance="envoy-inst-1"} 500.0
     * server_parent_connections{app="envoy-app",instance="envoy-inst-1"} 10.0
     * server_concurrency{app="envoy-app",instance="envoy-inst-1"} 4.0
     * server_envoy_bug_failures{app="envoy-app",instance="envoy-inst-1"} 2.0
     *
     * # --- envoy_cluster_metrics (one SampleFamily, multiple Samples) ---
     * # Each Sample has a metrics_name matching BOTH tagMatch patterns:
     * #   1) suffix pattern (e.g. .+membership_healthy)
     * #   2) prefix pattern (cluster.outbound.+|cluster.inbound.+)
     * # cluster_name does NOT match .+kube-system
     *
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.membership_healthy"} 3.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_active"} 15.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_total"} 1200.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_active"} 8.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_total"} 5000.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_pending_active"} 2.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.lb_healthy_panic"} 0.0
     * envoy_cluster_metrics{app="envoy-app",instance="envoy-inst-1",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_none_healthy"} 1.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "app", "envoy-app",
            "instance", "envoy-inst-1");

        // Cluster metric labels: scope + cluster_name
        String clusterName =
            "outbound|9080||my-svc.default.svc.cluster.local";
        ImmutableMap<String, String> clusterScope = ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put("cluster_name", clusterName)
            .build();

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Simple server metrics ---
            .put("server_memory_heap_size",
                single("server_memory_heap_size", scope,
                    104857600.0 * scale, timestamp))
            .put("server_memory_allocated",
                single("server_memory_allocated", scope,
                    52428800.0 * scale, timestamp))
            .put("server_memory_physical_size",
                single("server_memory_physical_size", scope,
                    209715200.0 * scale, timestamp))
            .put("server_total_connections",
                single("server_total_connections", scope,
                    500.0 * scale, timestamp))
            .put("server_parent_connections",
                single("server_parent_connections", scope,
                    10.0 * scale, timestamp))
            .put("server_concurrency",
                single("server_concurrency", scope,
                    4.0 * scale, timestamp))
            .put("server_envoy_bug_failures",
                single("server_envoy_bug_failures", scope,
                    2.0 * scale, timestamp))

            // --- envoy_cluster_metrics ---
            // One SampleFamily with multiple Samples, each with a different
            // metrics_name value matching both tagMatch patterns.
            // metrics_name values use "cluster.outbound.9080." prefix to match
            // "cluster.outbound.+|cluster.inbound.+" and each specific suffix.
            .put("envoy_cluster_metrics",
                SampleFamilyBuilder.newBuilder(
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.membership_healthy",
                        3.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_cx_active",
                        15.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_cx_total",
                        1200.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_rq_active",
                        8.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_rq_total",
                        5000.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_rq_pending_active",
                        2.0 * scale, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.lb_healthy_panic",
                        0.0, timestamp),
                    clusterSample(clusterScope,
                        "cluster.outbound.9080.upstream_cx_none_healthy",
                        1.0 * scale, timestamp)
                ).build())

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

    private static Sample clusterSample(
            final ImmutableMap<String, String> clusterScope,
            final String metricsName,
            final double value,
            final long timestamp) {
        return Sample.builder()
            .name("envoy_cluster_metrics")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(clusterScope)
                .put("metrics_name", metricsName)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
