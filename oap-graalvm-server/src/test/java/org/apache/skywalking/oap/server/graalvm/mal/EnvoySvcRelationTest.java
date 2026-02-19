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
 * Comparison test for envoy-metrics-rules/envoy-svc-relation.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix — serviceRelation(DetectPoint.CLIENT, ['app'], ['cluster_name'], Layer.MESH_DP)
 *       with scope labels app (source service) and cluster_name (dest service)</li>
 *   <li>tagMatch — double tagMatch on metrics_name for each metric
 *       (suffix pattern AND cluster.outbound prefix)</li>
 *   <li>increase — 4 of the 7 metrics use .increase('PT1M')</li>
 *   <li>All 7 metrics share a single sample name: envoy_cluster_metrics</li>
 * </ul>
 */
class EnvoySvcRelationTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "envoy-metrics-rules/envoy-svc-relation.yaml";
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
     * Build the full input map for envoy-svc-relation.yaml.
     *
     * <p>All 7 metrics read from a single sample: envoy_cluster_metrics.
     * Each metric uses double tagMatch on metrics_name:
     * first for the specific metric suffix, second for cluster.outbound prefix.
     * The metrics_name values must satisfy both patterns simultaneously.
     *
     * <p>Scope labels: app (source service), cluster_name (dest service).
     * cluster_name is used as a serviceRelation scope label so it needs a
     * realistic service-mesh value.
     *
     * <pre>
     * # Scope: app="my-app", cluster_name="outbound|9080||my-svc.default.svc.cluster.local"
     *
     * # cluster_up_cx_active — tagMatch('.+upstream_cx_active', 'cluster.outbound.+')
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_active"} 50.0
     *
     * # cluster_up_cx_incr — tagMatch('.+upstream_cx_total', 'cluster.outbound.+') + increase
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_total"} 300.0
     *
     * # cluster_up_rq_active — tagMatch('.+upstream_rq_active', 'cluster.outbound.+')
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_active"} 25.0
     *
     * # cluster_up_rq_incr — tagMatch('.+upstream_rq_total', 'cluster.outbound.+') + increase
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_total"} 1000.0
     *
     * # cluster_up_rq_pending_active — tagMatch('.+upstream_rq_pending_active', 'cluster.outbound.+')
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_rq_pending_active"} 5.0
     *
     * # cluster_lb_healthy_panic_incr — tagMatch('.+lb_healthy_panic', 'cluster.outbound.+') + increase
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.lb_healthy_panic"} 10.0
     *
     * # cluster_up_cx_none_healthy_incr — tagMatch('.+upstream_cx_none_healthy', 'cluster.outbound.+') + increase
     * envoy_cluster_metrics{app="my-app",cluster_name="outbound|9080||my-svc.default.svc.cluster.local",metrics_name="cluster.outbound.9080.upstream_cx_none_healthy"} 3.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        String app = "my-app";
        String clusterName =
            "outbound|9080||my-svc.default.svc.cluster.local";

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "app", app,
            "cluster_name", clusterName);

        return ImmutableMap.<String, SampleFamily>builder()
            .put("envoy_cluster_metrics",
                SampleFamilyBuilder.newBuilder(
                    // cluster_up_cx_active
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_cx_active",
                        50.0 * scale, timestamp),
                    // cluster_up_cx_incr (increase)
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_cx_total",
                        300.0 * scale, timestamp),
                    // cluster_up_rq_active
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_rq_active",
                        25.0 * scale, timestamp),
                    // cluster_up_rq_incr (increase)
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_rq_total",
                        1000.0 * scale, timestamp),
                    // cluster_up_rq_pending_active
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_rq_pending_active",
                        5.0 * scale, timestamp),
                    // cluster_lb_healthy_panic_incr (increase)
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.lb_healthy_panic",
                        10.0 * scale, timestamp),
                    // cluster_up_cx_none_healthy_incr (increase)
                    labeled("envoy_cluster_metrics", scope,
                        "metrics_name",
                        "cluster.outbound.9080.upstream_cx_none_healthy",
                        3.0 * scale, timestamp)
                ).build())
            .build();
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
