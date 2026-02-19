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
 * Comparison test for otel-rules/elasticsearch/elasticsearch-index.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagNotEqual — filtering {@code .tagNotEqual('cluster','unknown_cluster')}
 *       on every metric; samples use {@code cluster="es-cluster"}</li>
 *   <li>rate('PT1M') — on all stats counters and doc totals</li>
 *   <li>Multi-sample division — proc_rate =
 *       {@code 1 / (time.rate / total.rate)}</li>
 *   <li>Complex multi-sample division — search query proc_rate =
 *       {@code 1 / ((query_time + fetch_time + scroll_time + suggest_time).rate / query_total.rate)}</li>
 *   <li>Average time expressions —
 *       {@code time.sum.rate / total.sum.rate}</li>
 *   <li>Tag closure for indices_shards_docs —
 *       {@code if (tags['primary'] == 'true') tags.primary = 'primary' else tags.primary = 'replica'}</li>
 *   <li>Tag closure in expSuffix —
 *       {@code tags.cluster = 'elasticsearch::' + tags.cluster}</li>
 *   <li>Scope: endpoint(['cluster'], ['index'], Layer.ELASTICSEARCH)</li>
 * </ul>
 */
class ElasticsearchIndexTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/elasticsearch/elasticsearch-index.yaml";
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
     * elasticsearch-index.yaml. Values are scaled relative to {@code base}
     * so input1 and input2 differ for rate computations.
     *
     * <pre>
     * # Scope labels (consumed by expSuffix after tag closure):
     * #   cluster="es-cluster"  (transformed to "elasticsearch::es-cluster")
     * #   index="idx-main"
     *
     * # --- Indexing stats (rate counters) ---
     * elasticsearch_index_stats_indexing_index_total{cluster="es-cluster",index="idx-main"} 5000.0
     * elasticsearch_index_stats_indexing_index_time_seconds_total{cluster="es-cluster",index="idx-main"} 25.0
     * elasticsearch_index_stats_indexing_delete_total{cluster="es-cluster",index="idx-main"} 200.0
     * elasticsearch_index_stats_indexing_delete_time_seconds_total{cluster="es-cluster",index="idx-main"} 1.0
     * elasticsearch_index_stats_indexing_throttle_time_seconds_total{cluster="es-cluster",index="idx-main"} 0.5
     *
     * # --- Search stats (rate counters) ---
     * elasticsearch_index_stats_search_query_total{cluster="es-cluster",index="idx-main"} 10000.0
     * elasticsearch_index_stats_search_query_time_seconds_total{cluster="es-cluster",index="idx-main"} 50.0
     * elasticsearch_index_stats_search_fetch_total{cluster="es-cluster",index="idx-main"} 8000.0
     * elasticsearch_index_stats_search_fetch_time_seconds_total{cluster="es-cluster",index="idx-main"} 16.0
     * elasticsearch_index_stats_search_scroll_total{cluster="es-cluster",index="idx-main"} 500.0
     * elasticsearch_index_stats_search_scroll_time_seconds_total{cluster="es-cluster",index="idx-main"} 2.5
     * elasticsearch_index_stats_search_suggest_total{cluster="es-cluster",index="idx-main"} 100.0
     * elasticsearch_index_stats_search_suggest_time_seconds_total{cluster="es-cluster",index="idx-main"} 0.3
     *
     * # --- Merge/flush/refresh/warmer stats (rate counters) ---
     * elasticsearch_index_stats_merge_total{cluster="es-cluster",index="idx-main"} 300.0
     * elasticsearch_index_stats_merge_time_seconds_total{cluster="es-cluster",index="idx-main"} 15.0
     * elasticsearch_index_stats_merge_stopped_time_seconds_total{cluster="es-cluster",index="idx-main"} 0.2
     * elasticsearch_index_stats_merge_throttle_time_seconds_total{cluster="es-cluster",index="idx-main"} 0.1
     * elasticsearch_index_stats_flush_total{cluster="es-cluster",index="idx-main"} 50.0
     * elasticsearch_index_stats_flush_time_seconds_total{cluster="es-cluster",index="idx-main"} 5.0
     * elasticsearch_index_stats_refresh_total{cluster="es-cluster",index="idx-main"} 600.0
     * elasticsearch_index_stats_refresh_time_seconds_total{cluster="es-cluster",index="idx-main"} 3.0
     * elasticsearch_index_stats_warmer_total{cluster="es-cluster",index="idx-main"} 400.0
     * elasticsearch_index_stats_warmer_time_seconds_total{cluster="es-cluster",index="idx-main"} 2.0
     *
     * # --- Get stats (rate counters) ---
     * elasticsearch_index_stats_get_total{cluster="es-cluster",index="idx-main"} 1500.0
     * elasticsearch_index_stats_get_time_seconds_total{cluster="es-cluster",index="idx-main"} 7.5
     *
     * # --- Document counts (gauges and rate) ---
     * elasticsearch_indices_docs_primary{cluster="es-cluster",index="idx-main"} 1000000.0
     * elasticsearch_indices_store_size_bytes_primary{cluster="es-cluster",index="idx-main"} 5368709120.0
     * elasticsearch_indices_docs_total{cluster="es-cluster",index="idx-main"} 2000000.0
     * elasticsearch_indices_store_size_bytes_total{cluster="es-cluster",index="idx-main"} 10737418240.0
     * elasticsearch_indices_deleted_docs_primary{cluster="es-cluster",index="idx-main"} 5000.0
     *
     * # --- Segment metrics ---
     * elasticsearch_indices_segment_count_total{cluster="es-cluster",index="idx-main"} 120.0
     * elasticsearch_indices_segment_memory_bytes_total{cluster="es-cluster",index="idx-main"} 67108864.0
     * elasticsearch_indices_segment_count_primary{cluster="es-cluster",index="idx-main"} 60.0
     * elasticsearch_indices_segment_memory_bytes_primary{cluster="es-cluster",index="idx-main"} 33554432.0
     *
     * # --- Shard docs (with primary/shard labels, tag closure remaps primary) ---
     * elasticsearch_indices_shards_docs{cluster="es-cluster",index="idx-main",primary="true",shard="0"} 500000.0
     * elasticsearch_indices_shards_docs{cluster="es-cluster",index="idx-main",primary="false",shard="0"} 500000.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "es-cluster",
            "index", "idx-main");

        return ImmutableMap.<String, SampleFamily>builder()
            // Indexing stats
            .put("elasticsearch_index_stats_indexing_index_total",
                single("elasticsearch_index_stats_indexing_index_total",
                    scope, 5000.0 * scale, timestamp))
            .put("elasticsearch_index_stats_indexing_index_time_seconds_total",
                single("elasticsearch_index_stats_indexing_index_time_seconds_total",
                    scope, 25.0 * scale, timestamp))
            .put("elasticsearch_index_stats_indexing_delete_total",
                single("elasticsearch_index_stats_indexing_delete_total",
                    scope, 200.0 * scale, timestamp))
            .put("elasticsearch_index_stats_indexing_delete_time_seconds_total",
                single("elasticsearch_index_stats_indexing_delete_time_seconds_total",
                    scope, 1.0 * scale, timestamp))
            .put("elasticsearch_index_stats_indexing_throttle_time_seconds_total",
                single("elasticsearch_index_stats_indexing_throttle_time_seconds_total",
                    scope, 0.5 * scale, timestamp))

            // Search stats
            .put("elasticsearch_index_stats_search_query_total",
                single("elasticsearch_index_stats_search_query_total",
                    scope, 10000.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_query_time_seconds_total",
                single("elasticsearch_index_stats_search_query_time_seconds_total",
                    scope, 50.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_fetch_total",
                single("elasticsearch_index_stats_search_fetch_total",
                    scope, 8000.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_fetch_time_seconds_total",
                single("elasticsearch_index_stats_search_fetch_time_seconds_total",
                    scope, 16.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_scroll_total",
                single("elasticsearch_index_stats_search_scroll_total",
                    scope, 500.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_scroll_time_seconds_total",
                single("elasticsearch_index_stats_search_scroll_time_seconds_total",
                    scope, 2.5 * scale, timestamp))
            .put("elasticsearch_index_stats_search_suggest_total",
                single("elasticsearch_index_stats_search_suggest_total",
                    scope, 100.0 * scale, timestamp))
            .put("elasticsearch_index_stats_search_suggest_time_seconds_total",
                single("elasticsearch_index_stats_search_suggest_time_seconds_total",
                    scope, 0.3 * scale, timestamp))

            // Merge/flush/refresh/warmer stats
            .put("elasticsearch_index_stats_merge_total",
                single("elasticsearch_index_stats_merge_total",
                    scope, 300.0 * scale, timestamp))
            .put("elasticsearch_index_stats_merge_time_seconds_total",
                single("elasticsearch_index_stats_merge_time_seconds_total",
                    scope, 15.0 * scale, timestamp))
            .put("elasticsearch_index_stats_merge_stopped_time_seconds_total",
                single("elasticsearch_index_stats_merge_stopped_time_seconds_total",
                    scope, 0.2 * scale, timestamp))
            .put("elasticsearch_index_stats_merge_throttle_time_seconds_total",
                single("elasticsearch_index_stats_merge_throttle_time_seconds_total",
                    scope, 0.1 * scale, timestamp))
            .put("elasticsearch_index_stats_flush_total",
                single("elasticsearch_index_stats_flush_total",
                    scope, 50.0 * scale, timestamp))
            .put("elasticsearch_index_stats_flush_time_seconds_total",
                single("elasticsearch_index_stats_flush_time_seconds_total",
                    scope, 5.0 * scale, timestamp))
            .put("elasticsearch_index_stats_refresh_total",
                single("elasticsearch_index_stats_refresh_total",
                    scope, 600.0 * scale, timestamp))
            .put("elasticsearch_index_stats_refresh_time_seconds_total",
                single("elasticsearch_index_stats_refresh_time_seconds_total",
                    scope, 3.0 * scale, timestamp))
            .put("elasticsearch_index_stats_warmer_total",
                single("elasticsearch_index_stats_warmer_total",
                    scope, 400.0 * scale, timestamp))
            .put("elasticsearch_index_stats_warmer_time_seconds_total",
                single("elasticsearch_index_stats_warmer_time_seconds_total",
                    scope, 2.0 * scale, timestamp))

            // Get stats
            .put("elasticsearch_index_stats_get_total",
                single("elasticsearch_index_stats_get_total",
                    scope, 1500.0 * scale, timestamp))
            .put("elasticsearch_index_stats_get_time_seconds_total",
                single("elasticsearch_index_stats_get_time_seconds_total",
                    scope, 7.5 * scale, timestamp))

            // Document counts
            .put("elasticsearch_indices_docs_primary",
                single("elasticsearch_indices_docs_primary",
                    scope, 1000000.0 * scale, timestamp))
            .put("elasticsearch_indices_store_size_bytes_primary",
                single("elasticsearch_indices_store_size_bytes_primary",
                    scope, 5368709120.0 * scale, timestamp))
            .put("elasticsearch_indices_docs_total",
                single("elasticsearch_indices_docs_total",
                    scope, 2000000.0 * scale, timestamp))
            .put("elasticsearch_indices_store_size_bytes_total",
                single("elasticsearch_indices_store_size_bytes_total",
                    scope, 10737418240.0 * scale, timestamp))
            .put("elasticsearch_indices_deleted_docs_primary",
                single("elasticsearch_indices_deleted_docs_primary",
                    scope, 5000.0 * scale, timestamp))

            // Segment metrics
            .put("elasticsearch_indices_segment_count_total",
                single("elasticsearch_indices_segment_count_total",
                    scope, 120.0, timestamp))
            .put("elasticsearch_indices_segment_memory_bytes_total",
                single("elasticsearch_indices_segment_memory_bytes_total",
                    scope, 67108864.0, timestamp))
            .put("elasticsearch_indices_segment_count_primary",
                single("elasticsearch_indices_segment_count_primary",
                    scope, 60.0, timestamp))
            .put("elasticsearch_indices_segment_memory_bytes_primary",
                single("elasticsearch_indices_segment_memory_bytes_primary",
                    scope, 33554432.0, timestamp))

            // Shard docs — primary/shard labels; tag closure remaps primary
            .put("elasticsearch_indices_shards_docs",
                SampleFamilyBuilder.newBuilder(
                    shardDocsSample("true", "0", 500000.0, scope, timestamp),
                    shardDocsSample("false", "0", 500000.0, scope, timestamp)
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

    private static Sample shardDocsSample(final String primary,
                                           final String shard,
                                           final double value,
                                           final ImmutableMap<String, String> scope,
                                           final long timestamp) {
        return Sample.builder()
            .name("elasticsearch_indices_shards_docs")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("primary", primary)
                .put("shard", shard)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
