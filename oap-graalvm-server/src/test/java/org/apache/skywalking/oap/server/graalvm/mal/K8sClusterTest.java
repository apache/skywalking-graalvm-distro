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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.LoadBalancerIngress;
import io.fabric8.kubernetes.api.model.LoadBalancerStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.ServiceStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.skywalking.library.kubernetes.KubernetesPods;
import org.apache.skywalking.library.kubernetes.KubernetesServices;
import org.apache.skywalking.library.kubernetes.ObjectID;
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.powermock.reflect.Whitebox;

/**
 * Comparison test for otel-rules/k8s/k8s-cluster.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>expSuffix tag closure — tags.cluster = 'k8s-cluster::' + tags.cluster</li>
 *   <li>Scope: service(['cluster'], Layer.K8S)</li>
 *   <li>tagEqual — resource='cpu'/'memory'/'ephemeral_storage'</li>
 *   <li>valueEqual(1) — kube_node_status_condition, kube_pod_status_phase,
 *       kube_deployment_status_condition, kube_pod_container_status_*</li>
 *   <li>tagMatch — status='true|unknown', condition='Available', phase not Running</li>
 *   <li>tagNotMatch — phase != 'Running'</li>
 *   <li>tag({tags -> tags.remove('condition')}) — removes condition label</li>
 *   <li>retagByK8sMeta — service_pod_status uses Pod2Service mapping</li>
 *   <li>tagNotEqual — service != ''</li>
 * </ul>
 */
class K8sClusterTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/k8s/k8s-cluster.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @BeforeEach
    void setupK8sMocks() {
        Service svc = mockService("nginx-svc", "default",
            ImmutableMap.of("app", "nginx"), "10.96.0.1");
        Pod pod = mockPod("nginx-pod-abc", "default",
            ImmutableMap.of("app", "nginx"), "10.0.0.5");

        // Replace internal caches in enum singletons (avoids mocking enum class)
        Whitebox.setInternalState(KubernetesServices.INSTANCE, "services",
            CacheBuilder.newBuilder().build(
                CacheLoader.from(() -> ImmutableList.of(svc))));
        Whitebox.setInternalState(KubernetesServices.INSTANCE, "serviceByID",
            CacheBuilder.newBuilder().build(
                CacheLoader.from((ObjectID id) -> Optional.of(svc))));
        Whitebox.setInternalState(KubernetesPods.INSTANCE, "podByIP",
            CacheBuilder.newBuilder().build(
                CacheLoader.from((String ip) -> Optional.of(pod))));
        Whitebox.setInternalState(KubernetesPods.INSTANCE, "podByObjectID",
            CacheBuilder.newBuilder().build(
                CacheLoader.from((ObjectID id) -> Optional.of(pod))));
    }

    @TestFactory
    Stream<DynamicTest> allMetricsGroovyVsPrecompiled() {
        return generateComparisonTests(YAML_PATH,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    /**
     * Build the full input map covering all sample names in k8s-cluster.yaml.
     *
     * <pre>
     * # Scope labels: cluster="my-cluster"
     * # expSuffix transforms cluster to "k8s-cluster::my-cluster"
     *
     * # --- CPU/Memory/Storage (tagEqual resource) ---
     * kube_node_status_capacity{cluster="my-cluster",resource="cpu"} 8.0
     * kube_node_status_capacity{cluster="my-cluster",resource="memory"} 33554432000.0
     * kube_node_status_capacity{cluster="my-cluster",resource="ephemeral_storage"} 214748364800.0
     * kube_node_status_allocatable{cluster="my-cluster",resource="cpu"} 7.6
     * kube_node_status_allocatable{cluster="my-cluster",resource="memory"} 33000000000.0
     * kube_node_status_allocatable{cluster="my-cluster",resource="ephemeral_storage"} 200000000000.0
     * kube_pod_container_resource_requests{cluster="my-cluster",resource="cpu"} 5.0
     * kube_pod_container_resource_requests{cluster="my-cluster",resource="memory"} 16000000000.0
     * kube_pod_container_resource_limits{cluster="my-cluster",resource="cpu"} 6.0
     * kube_pod_container_resource_limits{cluster="my-cluster",resource="memory"} 24000000000.0
     *
     * # --- Node status (valueEqual=1, tagMatch status) ---
     * kube_node_info{cluster="my-cluster"} 3.0
     * kube_node_status_condition{cluster="my-cluster",node="node-1",condition="Ready",status="true"} 1.0
     *
     * # --- Namespaces ---
     * kube_namespace_labels{cluster="my-cluster"} 5.0
     *
     * # --- Deployments ---
     * kube_deployment_labels{cluster="my-cluster"} 10.0
     * kube_deployment_status_condition{cluster="my-cluster",deployment="nginx",namespace="default",condition="Available",status="True"} 1.0
     * kube_deployment_spec_replicas{cluster="my-cluster",deployment="nginx",namespace="default"} 3.0
     *
     * # --- StatefulSets/DaemonSets ---
     * kube_statefulset_labels{cluster="my-cluster"} 2.0
     * kube_daemonset_labels{cluster="my-cluster"} 4.0
     *
     * # --- Services ---
     * kube_service_info{cluster="my-cluster"} 8.0
     * kube_pod_status_phase{cluster="my-cluster",pod="nginx-pod-abc",namespace="default",phase="Running",service=""} 1.0
     *
     * # --- Pods ---
     * kube_pod_info{cluster="my-cluster"} 20.0
     * kube_pod_status_phase{cluster="my-cluster",pod="failing-pod",phase="Failed"} 1.0
     *
     * # --- Containers ---
     * kube_pod_container_info{cluster="my-cluster"} 30.0
     * kube_pod_container_status_waiting_reason{cluster="my-cluster",pod="crashing-pod",container="app",reason="CrashLoopBackOff"} 1.0
     * kube_pod_container_status_terminated_reason{cluster="my-cluster",pod="done-pod",container="app",reason="Completed"} 1.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "my-cluster");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- kube_node_status_capacity (cpu, memory, ephemeral_storage) ---
            .put("kube_node_status_capacity",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_node_status_capacity", scope,
                        "cpu", 8.0 * scale, timestamp),
                    resourceSample("kube_node_status_capacity", scope,
                        "memory", 33554432000.0 * scale, timestamp),
                    resourceSample("kube_node_status_capacity", scope,
                        "ephemeral_storage", 214748364800.0 * scale, timestamp)
                ).build())

            .put("kube_node_status_allocatable",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_node_status_allocatable", scope,
                        "cpu", 7.6 * scale, timestamp),
                    resourceSample("kube_node_status_allocatable", scope,
                        "memory", 33000000000.0 * scale, timestamp),
                    resourceSample("kube_node_status_allocatable", scope,
                        "ephemeral_storage", 200000000000.0 * scale, timestamp)
                ).build())

            .put("kube_pod_container_resource_requests",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_requests", scope,
                        "cpu", 5.0 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_requests", scope,
                        "memory", 16000000000.0 * scale, timestamp)
                ).build())

            .put("kube_pod_container_resource_limits",
                SampleFamilyBuilder.newBuilder(
                    resourceSample("kube_pod_container_resource_limits", scope,
                        "cpu", 6.0 * scale, timestamp),
                    resourceSample("kube_pod_container_resource_limits", scope,
                        "memory", 24000000000.0 * scale, timestamp)
                ).build())

            // --- Node info/status ---
            .put("kube_node_info",
                single("kube_node_info", scope, 3.0 * scale, timestamp))
            .put("kube_node_status_condition",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_node_status_condition")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("node", "node-1")
                            .put("condition", "Ready")
                            .put("status", "true")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            // --- Namespaces ---
            .put("kube_namespace_labels",
                single("kube_namespace_labels", scope, 5.0 * scale, timestamp))

            // --- Deployments ---
            .put("kube_deployment_labels",
                single("kube_deployment_labels", scope, 10.0 * scale, timestamp))
            .put("kube_deployment_status_condition",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_deployment_status_condition")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("deployment", "nginx")
                            .put("namespace", "default")
                            .put("condition", "Available")
                            .put("status", "True")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())
            .put("kube_deployment_spec_replicas",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_deployment_spec_replicas")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("deployment", "nginx")
                            .put("namespace", "default")
                            .build())
                        .value(3.0 * scale).timestamp(timestamp).build()
                ).build())

            // --- StatefulSets/DaemonSets ---
            .put("kube_statefulset_labels",
                single("kube_statefulset_labels", scope, 2.0 * scale, timestamp))
            .put("kube_daemonset_labels",
                single("kube_daemonset_labels", scope, 4.0 * scale, timestamp))

            // --- Services ---
            .put("kube_service_info",
                single("kube_service_info", scope, 8.0 * scale, timestamp))

            // --- Pod status phase (used by service_pod_status with retagByK8sMeta,
            //     pod_total, and pod_status_not_running) ---
            .put("kube_pod_status_phase",
                SampleFamilyBuilder.newBuilder(
                    // For service_pod_status: retagByK8sMeta matches pod→service
                    Sample.builder().name("kube_pod_status_phase")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("pod", "nginx-pod-abc")
                            .put("namespace", "default")
                            .put("phase", "Running")
                            .build())
                        .value(1.0).timestamp(timestamp).build(),
                    // For pod_status_not_running: tagNotMatch phase!=Running
                    Sample.builder().name("kube_pod_status_phase")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("pod", "failing-pod")
                            .put("namespace", "default")
                            .put("phase", "Failed")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())

            // --- Pods ---
            .put("kube_pod_info",
                single("kube_pod_info", scope, 20.0 * scale, timestamp))

            // --- Containers ---
            .put("kube_pod_container_info",
                single("kube_pod_container_info", scope, 30.0 * scale, timestamp))
            .put("kube_pod_container_status_waiting_reason",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_container_status_waiting_reason")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("pod", "crashing-pod")
                            .put("container", "app")
                            .put("reason", "CrashLoopBackOff")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
                ).build())
            .put("kube_pod_container_status_terminated_reason",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_container_status_terminated_reason")
                        .labels(ImmutableMap.<String, String>builder()
                            .putAll(scope)
                            .put("pod", "done-pod")
                            .put("container", "app")
                            .put("reason", "Completed")
                            .build())
                        .value(1.0).timestamp(timestamp).build()
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

    // --- K8s mock helpers ---

    private static Service mockService(final String name,
                                        final String namespace,
                                        final Map<String, String> selector,
                                        final String ipAddress) {
        Service service = new Service();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        service.setMetadata(meta);

        ServiceSpec spec = new ServiceSpec();
        spec.setSelector(selector);
        service.setSpec(spec);

        ServiceStatus status = new ServiceStatus();
        LoadBalancerStatus lbStatus = new LoadBalancerStatus();
        LoadBalancerIngress ingress = new LoadBalancerIngress();
        ingress.setIp(ipAddress);
        lbStatus.setIngress(Arrays.asList(ingress));
        status.setLoadBalancer(lbStatus);
        service.setStatus(status);

        return service;
    }

    private static Pod mockPod(final String name,
                                final String namespace,
                                final Map<String, String> labels,
                                final String ipAddress) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        meta.setLabels(labels);
        pod.setMetadata(meta);

        PodStatus status = new PodStatus();
        status.setPodIP(ipAddress);
        pod.setStatus(status);

        return pod;
    }
}
