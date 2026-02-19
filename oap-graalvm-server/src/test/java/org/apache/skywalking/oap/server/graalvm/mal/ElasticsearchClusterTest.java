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
 * Comparison test for otel-rules/elasticsearch/elasticsearch-cluster.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagNotEqual — filtering {@code .tagNotEqual('cluster','unknown_cluster')}
 *       on every metric; samples use {@code cluster="es-cluster"}</li>
 *   <li>valueEqual — {@code .valueEqual(1)} on health_status to keep only
 *       active color entries</li>
 *   <li>Multi-sample division — jvm_memory_used_avg =
 *       {@code used_bytes.sum / max_bytes.sum * 100}</li>
 *   <li>increase('PT1M') — on breakers_tripped (counter)</li>
 *   <li>avg(['cluster']) — on cpu_usage_avg</li>
 *   <li>Tag closure in expSuffix —
 *       {@code tags.cluster = 'elasticsearch::' + tags.cluster}</li>
 *   <li>Scope: service(['cluster'], Layer.ELASTICSEARCH)</li>
 * </ul>
 */
class ElasticsearchClusterTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/elasticsearch/elasticsearch-cluster.yaml";
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
     * Build the full input map covering all sample names in
     * elasticsearch-cluster.yaml. Values are scaled relative to {@code base}
     * so input1 and input2 differ for rate/increase computations.
     *
     * <pre>
     * # Scope label (consumed by expSuffix after tag closure):
     * #   cluster="es-cluster"  (transformed to "elasticsearch::es-cluster")
     *
     * # --- Health status (valueEqual(1), aggregated by cluster+color) ---
     * elasticsearch_cluster_health_status{cluster="es-cluster",color="green"} 1.0
     * elasticsearch_cluster_health_status{cluster="es-cluster",color="yellow"} 0.0
     * elasticsearch_cluster_health_status{cluster="es-cluster",color="red"} 0.0
     *
     * # --- Breakers tripped (increase) ---
     * elasticsearch_breakers_tripped{cluster="es-cluster"} 50.0
     *
     * # --- Cluster node counts ---
     * elasticsearch_cluster_health_number_of_nodes{cluster="es-cluster"} 5.0
     * elasticsearch_cluster_health_number_of_data_nodes{cluster="es-cluster"} 3.0
     *
     * # --- Pending tasks ---
     * elasticsearch_cluster_health_number_of_pending_tasks{cluster="es-cluster"} 2.0
     *
     * # --- CPU usage (avg) ---
     * elasticsearch_process_cpu_percent{cluster="es-cluster"} 45.0
     *
     * # --- JVM memory (division: used / max * 100) ---
     * elasticsearch_jvm_memory_used_bytes{cluster="es-cluster"} 536870912.0
     * elasticsearch_jvm_memory_max_bytes{cluster="es-cluster"} 1073741824.0
     *
     * # --- Open file count ---
     * elasticsearch_process_open_files_count{cluster="es-cluster"} 256.0
     *
     * # --- Shard metrics ---
     * elasticsearch_cluster_health_active_primary_shards{cluster="es-cluster"} 10.0
     * elasticsearch_cluster_health_active_shards{cluster="es-cluster"} 20.0
     * elasticsearch_cluster_health_initializing_shards{cluster="es-cluster"} 1.0
     * elasticsearch_cluster_health_delayed_unassigned_shards{cluster="es-cluster"} 0.0
     * elasticsearch_cluster_health_relocating_shards{cluster="es-cluster"} 2.0
     * elasticsearch_cluster_health_unassigned_shards{cluster="es-cluster"} 3.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "es-cluster");

        return ImmutableMap.<String, SampleFamily>builder()
            // Health status — valueEqual(1) keeps only color=green row
            .put("elasticsearch_cluster_health_status",
                SampleFamilyBuilder.newBuilder(
                    healthStatusSample("green", 1.0, scope, timestamp),
                    healthStatusSample("yellow", 0.0, scope, timestamp),
                    healthStatusSample("red", 0.0, scope, timestamp)
                ).build())

            // Breakers tripped — increase('PT1M')
            .put("elasticsearch_breakers_tripped",
                single("elasticsearch_breakers_tripped",
                    scope, 50.0 * scale, timestamp))

            // Cluster node counts
            .put("elasticsearch_cluster_health_number_of_nodes",
                single("elasticsearch_cluster_health_number_of_nodes",
                    scope, 5.0, timestamp))
            .put("elasticsearch_cluster_health_number_of_data_nodes",
                single("elasticsearch_cluster_health_number_of_data_nodes",
                    scope, 3.0, timestamp))

            // Pending tasks
            .put("elasticsearch_cluster_health_number_of_pending_tasks",
                single("elasticsearch_cluster_health_number_of_pending_tasks",
                    scope, 2.0, timestamp))

            // CPU usage avg
            .put("elasticsearch_process_cpu_percent",
                single("elasticsearch_process_cpu_percent",
                    scope, 45.0 * scale, timestamp))

            // JVM memory used / max * 100
            .put("elasticsearch_jvm_memory_used_bytes",
                single("elasticsearch_jvm_memory_used_bytes",
                    scope, 536870912.0 * scale, timestamp))
            .put("elasticsearch_jvm_memory_max_bytes",
                single("elasticsearch_jvm_memory_max_bytes",
                    scope, 1073741824.0, timestamp))

            // Open file count
            .put("elasticsearch_process_open_files_count",
                single("elasticsearch_process_open_files_count",
                    scope, 256.0 * scale, timestamp))

            // Shard metrics
            .put("elasticsearch_cluster_health_active_primary_shards",
                single("elasticsearch_cluster_health_active_primary_shards",
                    scope, 10.0, timestamp))
            .put("elasticsearch_cluster_health_active_shards",
                single("elasticsearch_cluster_health_active_shards",
                    scope, 20.0, timestamp))
            .put("elasticsearch_cluster_health_initializing_shards",
                single("elasticsearch_cluster_health_initializing_shards",
                    scope, 1.0, timestamp))
            .put("elasticsearch_cluster_health_delayed_unassigned_shards",
                single("elasticsearch_cluster_health_delayed_unassigned_shards",
                    scope, 0.0, timestamp))
            .put("elasticsearch_cluster_health_relocating_shards",
                single("elasticsearch_cluster_health_relocating_shards",
                    scope, 2.0, timestamp))
            .put("elasticsearch_cluster_health_unassigned_shards",
                single("elasticsearch_cluster_health_unassigned_shards",
                    scope, 3.0, timestamp))

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

    private static Sample healthStatusSample(final String color,
                                              final double value,
                                              final ImmutableMap<String, String> scope,
                                              final long timestamp) {
        return Sample.builder()
            .name("elasticsearch_cluster_health_status")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("color", color)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
