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
 * Comparison test for otel-rules/activemq/activemq-cluster.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>Tag closure in expSuffix — tags.cluster = 'activemq::' + tags.cluster</li>
 *   <li>Scope: SERVICE(['cluster'], Layer.ACTIVEMQ)</li>
 *   <li>tagEqual — filtering by type=GarbageCollector (G1 GC metrics)</li>
 *   <li>tagEqual — filtering by name=PS MarkSweep / PS Scavenge (parallel GC)</li>
 *   <li>sum with aggregation labels — sum(['cluster','service_instance_id'])</li>
 *   <li>increase("PT1M") — on GC collection count/time metrics</li>
 *   <li>rate("PT1M") — on enqueue/dequeue/dispatch/expired rates</li>
 *   <li>avg/max with aggregation labels — avg/max(['cluster'])</li>
 *   <li>Multi-sample expression with *10000 scaling (system_load_average)</li>
 * </ul>
 */
class ActivemqClusterTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/activemq/activemq-cluster.yaml";
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
     * Build the full input map covering all sample names in activemq-cluster.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="my-activemq-cluster", service_instance_id="broker-1"
     *
     * # --- JVM system metrics ---
     * java_lang_OperatingSystem_SystemLoadAverage{cluster="my-activemq-cluster",service_instance_id="broker-1"} 2.5
     * java_lang_Threading_ThreadCount{cluster="my-activemq-cluster",service_instance_id="broker-1"} 150.0
     *
     * # --- Heap memory metrics ---
     * java_lang_Memory_HeapMemoryUsage_init{cluster="my-activemq-cluster",service_instance_id="broker-1"} 268435456.0
     * java_lang_Memory_HeapMemoryUsage_committed{cluster="my-activemq-cluster",service_instance_id="broker-1"} 536870912.0
     * java_lang_Memory_HeapMemoryUsage_used{cluster="my-activemq-cluster",service_instance_id="broker-1"} 134217728.0
     * java_lang_Memory_HeapMemoryUsage_max{cluster="my-activemq-cluster",service_instance_id="broker-1"} 1073741824.0
     *
     * # --- G1 GC metrics (tagEqual type=GarbageCollector) ---
     * java_lang_G1_Old_Generation_CollectionCount{cluster="my-activemq-cluster",service_instance_id="broker-1",type="GarbageCollector"} 50.0
     * java_lang_G1_Young_Generation_CollectionCount{cluster="my-activemq-cluster",service_instance_id="broker-1",type="GarbageCollector"} 500.0
     * java_lang_G1_Old_Generation_CollectionTime{cluster="my-activemq-cluster",service_instance_id="broker-1",type="GarbageCollector"} 3000.0
     * java_lang_G1_Young_Generation_CollectionTime{cluster="my-activemq-cluster",service_instance_id="broker-1",type="GarbageCollector"} 12000.0
     *
     * # --- Parallel GC metrics (tagEqual name=PS MarkSweep / PS Scavenge) ---
     * java_lang_GarbageCollector_CollectionCount{cluster="my-activemq-cluster",service_instance_id="broker-1",name="PS MarkSweep"} 30.0
     * java_lang_GarbageCollector_CollectionCount{cluster="my-activemq-cluster",service_instance_id="broker-1",name="PS Scavenge"} 400.0
     * java_lang_GarbageCollector_CollectionTime{cluster="my-activemq-cluster",service_instance_id="broker-1",name="PS MarkSweep"} 2000.0
     * java_lang_GarbageCollector_CollectionTime{cluster="my-activemq-cluster",service_instance_id="broker-1",name="PS Scavenge"} 8000.0
     *
     * # --- Broker rate metrics ---
     * org_apache_activemq_Broker_TotalEnqueueCount{cluster="my-activemq-cluster"} 100000.0
     * org_apache_activemq_Broker_TotalDequeueCount{cluster="my-activemq-cluster"} 95000.0
     * org_apache_activemq_Broker_DispatchCount{cluster="my-activemq-cluster"} 98000.0
     * org_apache_activemq_Broker_ExpiredCount{cluster="my-activemq-cluster"} 200.0
     *
     * # --- Broker passthrough metrics ---
     * org_apache_activemq_Broker_AverageEnqueueTime{cluster="my-activemq-cluster"} 12.5
     * org_apache_activemq_Broker_MaxEnqueueTime{cluster="my-activemq-cluster"} 250.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> jvmScope = ImmutableMap.of(
            "cluster", "my-activemq-cluster",
            "service_instance_id", "broker-1");

        ImmutableMap<String, String> brokerScope = ImmutableMap.of(
            "cluster", "my-activemq-cluster");

        return ImmutableMap.<String, SampleFamily>builder()
            // JVM system metrics
            .put("java_lang_OperatingSystem_SystemLoadAverage",
                single("java_lang_OperatingSystem_SystemLoadAverage",
                    jvmScope, 2.5 * scale, timestamp))
            .put("java_lang_Threading_ThreadCount",
                single("java_lang_Threading_ThreadCount",
                    jvmScope, 150.0 * scale, timestamp))

            // Heap memory metrics
            .put("java_lang_Memory_HeapMemoryUsage_init",
                single("java_lang_Memory_HeapMemoryUsage_init",
                    jvmScope, 268435456.0, timestamp))
            .put("java_lang_Memory_HeapMemoryUsage_committed",
                single("java_lang_Memory_HeapMemoryUsage_committed",
                    jvmScope, 536870912.0, timestamp))
            .put("java_lang_Memory_HeapMemoryUsage_used",
                single("java_lang_Memory_HeapMemoryUsage_used",
                    jvmScope, 134217728.0 * scale, timestamp))
            .put("java_lang_Memory_HeapMemoryUsage_max",
                single("java_lang_Memory_HeapMemoryUsage_max",
                    jvmScope, 1073741824.0, timestamp))

            // G1 GC metrics — tagEqual('type','GarbageCollector')
            .put("java_lang_G1_Old_Generation_CollectionCount",
                single("java_lang_G1_Old_Generation_CollectionCount",
                    gcTypeSample(jvmScope, "type", "GarbageCollector"),
                    50.0 * scale, timestamp))
            .put("java_lang_G1_Young_Generation_CollectionCount",
                single("java_lang_G1_Young_Generation_CollectionCount",
                    gcTypeSample(jvmScope, "type", "GarbageCollector"),
                    500.0 * scale, timestamp))
            .put("java_lang_G1_Old_Generation_CollectionTime",
                single("java_lang_G1_Old_Generation_CollectionTime",
                    gcTypeSample(jvmScope, "type", "GarbageCollector"),
                    3000.0 * scale, timestamp))
            .put("java_lang_G1_Young_Generation_CollectionTime",
                single("java_lang_G1_Young_Generation_CollectionTime",
                    gcTypeSample(jvmScope, "type", "GarbageCollector"),
                    12000.0 * scale, timestamp))

            // Parallel GC metrics — tagEqual('name','PS MarkSweep'/'PS Scavenge')
            .put("java_lang_GarbageCollector_CollectionCount",
                SampleFamilyBuilder.newBuilder(
                    gcNameSample("java_lang_GarbageCollector_CollectionCount",
                        jvmScope, "PS MarkSweep", 30.0 * scale, timestamp),
                    gcNameSample("java_lang_GarbageCollector_CollectionCount",
                        jvmScope, "PS Scavenge", 400.0 * scale, timestamp)
                ).build())
            .put("java_lang_GarbageCollector_CollectionTime",
                SampleFamilyBuilder.newBuilder(
                    gcNameSample("java_lang_GarbageCollector_CollectionTime",
                        jvmScope, "PS MarkSweep", 2000.0 * scale, timestamp),
                    gcNameSample("java_lang_GarbageCollector_CollectionTime",
                        jvmScope, "PS Scavenge", 8000.0 * scale, timestamp)
                ).build())

            // Broker rate metrics
            .put("org_apache_activemq_Broker_TotalEnqueueCount",
                single("org_apache_activemq_Broker_TotalEnqueueCount",
                    brokerScope, 100000.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_TotalDequeueCount",
                single("org_apache_activemq_Broker_TotalDequeueCount",
                    brokerScope, 95000.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_DispatchCount",
                single("org_apache_activemq_Broker_DispatchCount",
                    brokerScope, 98000.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_ExpiredCount",
                single("org_apache_activemq_Broker_ExpiredCount",
                    brokerScope, 200.0 * scale, timestamp))

            // Broker passthrough metrics
            .put("org_apache_activemq_Broker_AverageEnqueueTime",
                single("org_apache_activemq_Broker_AverageEnqueueTime",
                    brokerScope, 12.5 * scale, timestamp))
            .put("org_apache_activemq_Broker_MaxEnqueueTime",
                single("org_apache_activemq_Broker_MaxEnqueueTime",
                    brokerScope, 250.0 * scale, timestamp))

            .build();
    }

    private static SampleFamily single(final String name,
                                        final ImmutableMap<String, String> labels,
                                        final double value,
                                        final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .name(name)
                .labels(labels)
                .value(value)
                .timestamp(timestamp)
                .build()
        ).build();
    }

    private static ImmutableMap<String, String> gcTypeSample(
            final ImmutableMap<String, String> scope,
            final String tagKey,
            final String tagValue) {
        return ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put(tagKey, tagValue)
            .build();
    }

    private static Sample gcNameSample(final String name,
                                        final ImmutableMap<String, String> scope,
                                        final String gcName,
                                        final double value,
                                        final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("name", gcName)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
