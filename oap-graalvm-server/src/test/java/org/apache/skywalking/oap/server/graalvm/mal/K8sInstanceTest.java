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
        Instant.parse("2024-01-01T00:00:10Z").toEpochMilli();

    private MockedStatic<K8sInfoRegistry> k8sRegistryMock;

    @BeforeEach
    void setupK8sMocks() {
        // Since upstream #14135 the registry reads pods and services in the background, so the
        // lookups are stubbed at the registry, as upstream's MAL script tests do. Every pod
        // resolves to the one service, as the former Kubernetes cache stubs did.
        String service = ObjectID.builder().name("api-svc").namespace("default").build().toString();
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
}
