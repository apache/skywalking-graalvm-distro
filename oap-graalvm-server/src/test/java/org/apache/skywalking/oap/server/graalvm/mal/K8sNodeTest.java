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
 * Comparison test for otel-rules/k8s/k8s-node.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix tag closure — tags.cluster = 'k8s-cluster::' + tags.cluster
 *       (cluster modified in-place)</li>
 *   <li>Scope: instance(['cluster'], ['node'], Layer.K8S)</li>
 *   <li>tagEqual — resource='cpu'/'memory'/'ephemeral_storage', id='/'</li>
 *   <li>valueEqual(1) — kube_node_status_condition</li>
 *   <li>tagMatch — status='true|unknown'</li>
 *   <li>irate — on container_cpu_usage_seconds_total, network bytes</li>
 *   <li>No retagByK8sMeta — pure tag filter tests</li>
 * </ul>
 */
class K8sNodeTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/k8s/k8s-node.yaml";
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
     * Build the full input map covering all sample names in k8s-node.yaml.
     *
     * <pre>
     * # Scope labels: cluster="my-cluster", node="node-1"
     * # expSuffix transforms cluster to "k8s-cluster::my-cluster"
     *
     * # --- CPU ---
     * kube_node_status_capacity{cluster="my-cluster",node="node-1",resource="cpu"} 4.0
     * kube_node_status_capacity{cluster="my-cluster",node="node-1",resource="memory"} 16777216000.0
     * kube_node_status_capacity{cluster="my-cluster",node="node-1",resource="ephemeral_storage"} 107374182400.0
     * kube_node_status_allocatable{cluster="my-cluster",node="node-1",resource="cpu"} 3.8
     * kube_node_status_allocatable{cluster="my-cluster",node="node-1",resource="memory"} 16500000000.0
     * kube_node_status_allocatable{cluster="my-cluster",node="node-1",resource="ephemeral_storage"} 100000000000.0
     * kube_pod_container_resource_requests{cluster="my-cluster",node="node-1",resource="cpu"} 2.5
     * kube_pod_container_resource_requests{cluster="my-cluster",node="node-1",resource="memory"} 8000000000.0
     * kube_pod_container_resource_limits{cluster="my-cluster",node="node-1",resource="cpu"} 3.0
     * kube_pod_container_resource_limits{cluster="my-cluster",node="node-1",resource="memory"} 12000000000.0
     * container_cpu_usage_seconds_total{cluster="my-cluster",node="node-1",id="/"} 500.0
     *
     * # --- Memory ---
     * container_memory_working_set_bytes{cluster="my-cluster",node="node-1",id="/"} 8589934592.0
     *
     * # --- Node status ---
     * kube_node_status_condition{cluster="my-cluster",node="node-1",condition="Ready",status="true"} 1.0
     *
     * # --- Pods ---
     * kube_pod_info{cluster="my-cluster",node="node-1"} 25.0
     *
     * # --- Network ---
     * container_network_receive_bytes_total{cluster="my-cluster",node="node-1",id="/"} 1073741824.0
     * container_network_transmit_bytes_total{cluster="my-cluster",node="node-1",id="/"} 536870912.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "my-cluster",
            "node", "node-1");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- kube_node_status_capacity (cpu, memory, ephemeral_storage) ---
            .put("kube_node_status_capacity",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_node_status_capacity", scope,
                        "cpu", 4.0 * scale, timestamp),
                    resourceSample("kube_node_status_capacity", scope,
                        "memory", 16777216000.0 * scale, timestamp),
                    resourceSample("kube_node_status_capacity", scope,
                        "ephemeral_storage", 107374182400.0 * scale, timestamp)
                ).build())

            // --- kube_node_status_allocatable ---
            .put("kube_node_status_allocatable",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_node_status_allocatable", scope,
                        "cpu", 3.8 * scale, timestamp),
                    resourceSample("kube_node_status_allocatable", scope,
                        "memory", 16500000000.0 * scale, timestamp),
                    resourceSample("kube_node_status_allocatable", scope,
                        "ephemeral_storage", 100000000000.0 * scale, timestamp)
                ).build())

            // --- kube_pod_container_resource_requests ---
            .put("kube_pod_container_resource_requests",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_requests", scope,
                        "cpu", 2.5 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_requests", scope,
                        "memory", 8000000000.0 * scale, timestamp)
                ).build())

            // --- kube_pod_container_resource_limits ---
            .put("kube_pod_container_resource_limits",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_limits", scope,
                        "cpu", 3.0 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_limits", scope,
                        "memory", 12000000000.0 * scale, timestamp)
                ).build())

            // --- container_cpu_usage_seconds_total (tagEqual id='/') ---
            .put("container_cpu_usage_seconds_total",
                SampleFamilyBuilder.newBuilder(
                    labeled("container_cpu_usage_seconds_total", scope,
                        "id", "/", 500.0 * scale, timestamp)
                ).build())

            // --- container_memory_working_set_bytes (tagEqual id='/') ---
            .put("container_memory_working_set_bytes",
                SampleFamilyBuilder.newBuilder(
                    labeled("container_memory_working_set_bytes", scope,
                        "id", "/", 8589934592.0 * scale, timestamp)
                ).build())

            // --- kube_node_status_condition (valueEqual=1, tagMatch status) ---
            .put("kube_node_status_condition",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_node_status_condition")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("condition", "Ready")
                            .put("status", "true")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            // --- kube_pod_info ---
            .put("kube_pod_info",
                single("kube_pod_info", scope, 25.0 * scale, timestamp))

            // --- Network ---
            .put("container_network_receive_bytes_total",
                SampleFamilyBuilder.newBuilder(
                    labeled("container_network_receive_bytes_total", scope,
                        "id", "/", 1073741824.0 * scale, timestamp)
                ).build())
            .put("container_network_transmit_bytes_total",
                SampleFamilyBuilder.newBuilder(
                    labeled("container_network_transmit_bytes_total", scope,
                        "id", "/", 536870912.0 * scale, timestamp)
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

    private static Sample resourceSample(final String name,
                                          final ImmutableMap<String, String> scope,
                                          final String resource,
                                          final double value,
                                          final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("resource", resource)
                .build())
            .value(value)
            .timestamp(timestamp)
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
