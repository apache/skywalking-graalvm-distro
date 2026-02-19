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
 * Comparison test for otel-rules/k8s/k8s-instance.yaml.
 *
 * <p>This file has 1 metric:
 * <ul>
 *   <li>pod_instance_status — uses retagByK8sMeta(Pod2Service)</li>
 *   <li>Scope: service(['cluster','service'], '::', Layer.K8S_SERVICE)
 *       .instance(['cluster','service'], '::', ['pod'], '', Layer.K8S_SERVICE,
 *       { tags -> ['pod': tags.pod, 'namespace': tags.namespace] })</li>
 *   <li>tagNotEqual — service != ''</li>
 * </ul>
 */
class K8sInstanceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH = "otel-rules/k8s/k8s-instance.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @BeforeEach
    void setupK8sMocks() {
        Service svc = mockService("api-svc", "default",
            ImmutableMap.of("app", "api"), "10.96.0.20");
        Pod pod = mockPod("api-pod-1", "default",
            ImmutableMap.of("app", "api"), "10.0.0.20");

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
     * Build the input map for k8s-instance.yaml's single metric.
     * retagByK8sMeta maps pod="api-pod-1",namespace="default" to service="api-svc".
     *
     * <pre>
     * kube_pod_status_phase{cluster="my-cluster",pod="api-pod-1",namespace="default"} 1.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        ImmutableMap<String, String> podScope = ImmutableMap.of(
            "cluster", "my-cluster",
            "pod", "api-pod-1",
            "namespace", "default");

        return ImmutableMap.<String, SampleFamily>builder()
            .put("kube_pod_status_phase",
                SampleFamilyBuilder.newBuilder(
                    Sample.builder().name("kube_pod_status_phase")
                        .labels(podScope)
                        .value(1.0).timestamp(timestamp).build()
                ).build())
            .build();
    }

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
