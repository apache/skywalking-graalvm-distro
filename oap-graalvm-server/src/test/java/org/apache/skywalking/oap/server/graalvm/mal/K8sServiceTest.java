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
import org.apache.skywalking.library.kubernetes.ObjectID;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.v2.k8s.K8sInfoRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;

/**
 * Comparison test for otel-rules/k8s/k8s-service.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>Scope: service(['cluster', 'service'], '::', Layer.K8S_SERVICE)</li>
 *   <li>retagByK8sMeta — ALL metrics use Pod2Service mapping</li>
 *   <li>tagNotEqual — service != '' (filter samples where retag found a service)</li>
 *   <li>tagEqual — resource='cpu'/'memory'</li>
 *   <li>valueEqual(1) — kube_pod_status_phase, status_waiting/terminated</li>
 *   <li>tagNotEqual — container != '', pod != '' (for CPU/memory)</li>
 *   <li>irate — container_cpu_usage_seconds_total</li>
 * </ul>
 */
class K8sServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/k8s/k8s-service.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:00:10Z").toEpochMilli();

    private MockedStatic<K8sInfoRegistry> k8sRegistryMock;

    @BeforeEach
    void setupK8sMocks() {
        // Since upstream #14135 the registry reads pods and services in the background, so the
        // lookups are stubbed at the registry, as upstream's MAL script tests do. Every pod
        // resolves to the one service, as the former Kubernetes cache stubs did.
        String service = ObjectID.builder().name("web-svc").namespace("default").build().toString();
        K8sInfoRegistry registry = Mockito.mock(K8sInfoRegistry.class);
        Mockito.when(registry.findServiceName(anyString(), anyString())).thenReturn(service);
        k8sRegistryMock = Mockito.mockStatic(K8sInfoRegistry.class);
        k8sRegistryMock.when(K8sInfoRegistry::getInstance).thenReturn(registry);
    }

    @AfterEach
    void closeK8sMocks() {
        k8sRegistryMock.close();
    }

    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
        return generateComparisonTests(YAML_PATH,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    /**
     * Build the full input map covering all sample names in k8s-service.yaml.
     * All metrics use retagByK8sMeta('service', Pod2Service, 'pod', 'namespace').
     * The mock maps pod="web-pod-1",namespace="default" to service="web-svc".
     *
     * <pre>
     * # Scope labels: cluster="my-cluster"
     * # retagByK8sMeta adds service="web-svc" based on pod/namespace lookup
     *
     * # --- Pod total ---
     * kube_pod_info{cluster="my-cluster",pod="web-pod-1",namespace="default"} 1.0
     *
     * # --- CPU/Memory resources ---
     * kube_pod_container_resource_requests{cluster="my-cluster",pod="web-pod-1",namespace="default",resource="cpu"} 0.5
     * kube_pod_container_resource_requests{cluster="my-cluster",pod="web-pod-1",namespace="default",resource="memory"} 536870912.0
     * kube_pod_container_resource_limits{cluster="my-cluster",pod="web-pod-1",namespace="default",resource="cpu"} 1.0
     * kube_pod_container_resource_limits{cluster="my-cluster",pod="web-pod-1",namespace="default",resource="memory"} 1073741824.0
     *
     * # --- Pod status ---
     * kube_pod_status_phase{cluster="my-cluster",pod="web-pod-1",namespace="default",phase="Running"} 1.0
     * kube_pod_container_status_waiting_reason{cluster="my-cluster",pod="web-pod-1",namespace="default",container="app",reason="ContainerCreating"} 1.0
     * kube_pod_container_status_terminated_reason{cluster="my-cluster",pod="web-pod-1",namespace="default",container="app",reason="Completed"} 1.0
     * kube_pod_container_status_restarts_total{cluster="my-cluster",pod="web-pod-1",namespace="default"} 3.0
     *
     * # --- Pod CPU/Memory usage ---
     * container_cpu_usage_seconds_total{cluster="my-cluster",pod="web-pod-1",namespace="default",container="app"} 250.0
     * container_memory_working_set_bytes{cluster="my-cluster",pod="web-pod-1",namespace="default",container="app"} 268435456.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        // All samples need pod + namespace for retagByK8sMeta
        ImmutableMap<String, String> podScope = ImmutableMap.of(
            "cluster", "my-cluster",
            "pod", "web-pod-1",
            "namespace", "default");

        return ImmutableMap.<String, SampleFamily>builder()
            .put("kube_pod_info",
                single("kube_pod_info", podScope, 1.0, timestamp))

            .put("kube_pod_container_resource_requests",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_requests",
                        podScope, "cpu", 0.5 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_requests",
                        podScope, "memory", 536870912.0 * scale, timestamp)
                ).build())

            .put("kube_pod_container_resource_limits",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_limits",
                        podScope, "cpu", 1.0 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_limits",
                        podScope, "memory", 1073741824.0 * scale, timestamp)
                ).build())

            .put("kube_pod_status_phase",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_status_phase")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(podScope).put("phase", "Running").build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            .put("kube_pod_container_status_waiting_reason",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_container_status_waiting_reason")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(podScope)
                            .put("container", "app")
                            .put("reason", "ContainerCreating")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            .put("kube_pod_container_status_terminated_reason",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_container_status_terminated_reason")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(podScope)
                            .put("container", "app")
                            .put("reason", "Completed")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            .put("kube_pod_container_status_restarts_total",
                single("kube_pod_container_status_restarts_total",
                    podScope, 3.0 * scale, timestamp))

            // container metrics need container!="" and pod!="" for tagNotEqual
            .put("container_cpu_usage_seconds_total",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("container_cpu_usage_seconds_total")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(podScope)
                            .put("container", "app")
                            .build())
                        .value(250.0 * scale).timestamp(timestamp).build()
                ).build())

            .put("container_memory_working_set_bytes",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("container_memory_working_set_bytes")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(podScope)
                            .put("container", "app")
                            .build())
                        .value(268435456.0 * scale).timestamp(timestamp).build()
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
}
