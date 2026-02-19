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
 * Comparison test for otel-rules/postgresql/postgresql-service.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagEqual — filtering by state (active)</li>
 *   <li>tagMatch — regex matching for idle|idle in transaction|idle in transaction (aborted)</li>
 *   <li>Multi-sample division with avg — (blks_hit*100 / (blks_read + blks_hit)).avg([...])</li>
 *   <li>sum with service-level aggregation — sum(['service_instance_id','host_name'])</li>
 *   <li>rate('PT1M') — on rows, transactions, conflicts, deadlocks, buffers, temp files</li>
 *   <li>rate('PT1M').sum([...]) ordering — checkpoint and buffer metrics apply rate before sum</li>
 *   <li>Tag closure in expSuffix — tags.host_name = 'postgresql::' + tags.host_name</li>
 *   <li>Service-level scope only — service(['host_name'], Layer.POSTGRESQL)
 *       (no instance scope)</li>
 * </ul>
 */
class PostgresqlServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/postgresql/postgresql-service.yaml";
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
     * Build the full input map covering all sample names in postgresql-service.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   host_name="pg-host-1", service_instance_id="pg:5432"
     *
     * # --- Row operation counters (rate with sum on service_instance_id,host_name) ---
     * pg_stat_database_tup_fetched{service_instance_id="pg:5432",host_name="pg-host-1"} 50000.0
     * pg_stat_database_tup_deleted{service_instance_id="pg:5432",host_name="pg-host-1"} 1200.0
     * pg_stat_database_tup_inserted{service_instance_id="pg:5432",host_name="pg-host-1"} 8000.0
     * pg_stat_database_tup_updated{service_instance_id="pg:5432",host_name="pg-host-1"} 3500.0
     * pg_stat_database_tup_returned{service_instance_id="pg:5432",host_name="pg-host-1"} 120000.0
     *
     * # --- Locks (sum on service_instance_id,host_name) ---
     * pg_locks_count{service_instance_id="pg:5432",host_name="pg-host-1"} 35.0
     *
     * # --- Sessions (tagEqual for active, tagMatch for idle variants) ---
     * pg_stat_activity_count{state="active",service_instance_id="pg:5432",host_name="pg-host-1"} 15.0
     * pg_stat_activity_count{state="idle",service_instance_id="pg:5432",host_name="pg-host-1"} 30.0
     * pg_stat_activity_count{state="idle in transaction",service_instance_id="pg:5432",host_name="pg-host-1"} 5.0
     * pg_stat_activity_count{state="idle in transaction (aborted)",service_instance_id="pg:5432",host_name="pg-host-1"} 2.0
     *
     * # --- Transaction counters (rate with sum) ---
     * pg_stat_database_xact_commit{service_instance_id="pg:5432",host_name="pg-host-1"} 25000.0
     * pg_stat_database_xact_rollback{service_instance_id="pg:5432",host_name="pg-host-1"} 150.0
     *
     * # --- Cache hit rate (multi-sample: blks_hit*100 / (blks_read + blks_hit), avg) ---
     * pg_stat_database_blks_hit{service_instance_id="pg:5432",host_name="pg-host-1"} 95000.0
     * pg_stat_database_blks_read{service_instance_id="pg:5432",host_name="pg-host-1"} 5000.0
     *
     * # --- Temporary files (rate with sum) ---
     * pg_stat_database_temp_bytes{service_instance_id="pg:5432",host_name="pg-host-1"} 1048576.0
     *
     * # --- Checkpoint metrics (rate then sum on service_instance_id,host_name) ---
     * pg_stat_bgwriter_checkpoint_write_time_total{service_instance_id="pg:5432",host_name="pg-host-1"} 45000.0
     * pg_stat_bgwriter_checkpoint_sync_time_total{service_instance_id="pg:5432",host_name="pg-host-1"} 1200.0
     * pg_stat_bgwriter_checkpoints_req_total{service_instance_id="pg:5432",host_name="pg-host-1"} 50.0
     * pg_stat_bgwriter_checkpoints_timed_total{service_instance_id="pg:5432",host_name="pg-host-1"} 300.0
     *
     * # --- Conflicts and deadlocks (rate with sum) ---
     * pg_stat_database_conflicts{service_instance_id="pg:5432",host_name="pg-host-1"} 8.0
     * pg_stat_database_deadlocks{service_instance_id="pg:5432",host_name="pg-host-1"} 2.0
     *
     * # --- Buffer metrics (rate then sum on service_instance_id,host_name) ---
     * pg_stat_bgwriter_buffers_checkpoint_total{service_instance_id="pg:5432",host_name="pg-host-1"} 10000.0
     * pg_stat_bgwriter_buffers_clean_total{service_instance_id="pg:5432",host_name="pg-host-1"} 500.0
     * pg_stat_bgwriter_buffers_backend_fsync_total{service_instance_id="pg:5432",host_name="pg-host-1"} 3.0
     * pg_stat_bgwriter_buffers_alloc_total{service_instance_id="pg:5432",host_name="pg-host-1"} 25000.0
     * pg_stat_bgwriter_buffers_backend_total{service_instance_id="pg:5432",host_name="pg-host-1"} 8000.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        // Scale factor: base=100 for TS1, base=200 for TS2
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "pg-host-1",
            "service_instance_id", "pg:5432");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- Row operation counters ---
            .put("pg_stat_database_tup_fetched",
                single("pg_stat_database_tup_fetched",
                    scope, 50000.0 * scale, timestamp))
            .put("pg_stat_database_tup_deleted",
                single("pg_stat_database_tup_deleted",
                    scope, 1200.0 * scale, timestamp))
            .put("pg_stat_database_tup_inserted",
                single("pg_stat_database_tup_inserted",
                    scope, 8000.0 * scale, timestamp))
            .put("pg_stat_database_tup_updated",
                single("pg_stat_database_tup_updated",
                    scope, 3500.0 * scale, timestamp))
            .put("pg_stat_database_tup_returned",
                single("pg_stat_database_tup_returned",
                    scope, 120000.0 * scale, timestamp))

            // --- Locks (no tag filters at service level, just sum) ---
            .put("pg_locks_count",
                single("pg_locks_count",
                    scope, 35.0 * scale, timestamp))

            // --- Sessions (tagEqual for active, tagMatch for idle variants) ---
            .put("pg_stat_activity_count",
                SampleFamilyBuilder.newBuilder(
                    activitySample("active", 15.0 * scale,
                        scope, timestamp),
                    activitySample("idle", 30.0 * scale,
                        scope, timestamp),
                    activitySample("idle in transaction", 5.0 * scale,
                        scope, timestamp),
                    activitySample("idle in transaction (aborted)", 2.0 * scale,
                        scope, timestamp)
                ).build())

            // --- Transaction counters ---
            .put("pg_stat_database_xact_commit",
                single("pg_stat_database_xact_commit",
                    scope, 25000.0 * scale, timestamp))
            .put("pg_stat_database_xact_rollback",
                single("pg_stat_database_xact_rollback",
                    scope, 150.0 * scale, timestamp))

            // --- Cache hit rate (multi-sample division with avg) ---
            .put("pg_stat_database_blks_hit",
                single("pg_stat_database_blks_hit",
                    scope, 95000.0 * scale, timestamp))
            .put("pg_stat_database_blks_read",
                single("pg_stat_database_blks_read",
                    scope, 5000.0 * scale, timestamp))

            // --- Temporary files ---
            .put("pg_stat_database_temp_bytes",
                single("pg_stat_database_temp_bytes",
                    scope, 1048576.0 * scale, timestamp))

            // --- Checkpoint metrics ---
            .put("pg_stat_bgwriter_checkpoint_write_time_total",
                single("pg_stat_bgwriter_checkpoint_write_time_total",
                    scope, 45000.0 * scale, timestamp))
            .put("pg_stat_bgwriter_checkpoint_sync_time_total",
                single("pg_stat_bgwriter_checkpoint_sync_time_total",
                    scope, 1200.0 * scale, timestamp))
            .put("pg_stat_bgwriter_checkpoints_req_total",
                single("pg_stat_bgwriter_checkpoints_req_total",
                    scope, 50.0 * scale, timestamp))
            .put("pg_stat_bgwriter_checkpoints_timed_total",
                single("pg_stat_bgwriter_checkpoints_timed_total",
                    scope, 300.0 * scale, timestamp))

            // --- Conflicts and deadlocks ---
            .put("pg_stat_database_conflicts",
                single("pg_stat_database_conflicts",
                    scope, 8.0 * scale, timestamp))
            .put("pg_stat_database_deadlocks",
                single("pg_stat_database_deadlocks",
                    scope, 2.0 * scale, timestamp))

            // --- Buffer metrics ---
            .put("pg_stat_bgwriter_buffers_checkpoint_total",
                single("pg_stat_bgwriter_buffers_checkpoint_total",
                    scope, 10000.0 * scale, timestamp))
            .put("pg_stat_bgwriter_buffers_clean_total",
                single("pg_stat_bgwriter_buffers_clean_total",
                    scope, 500.0 * scale, timestamp))
            .put("pg_stat_bgwriter_buffers_backend_fsync_total",
                single("pg_stat_bgwriter_buffers_backend_fsync_total",
                    scope, 3.0 * scale, timestamp))
            .put("pg_stat_bgwriter_buffers_alloc_total",
                single("pg_stat_bgwriter_buffers_alloc_total",
                    scope, 25000.0 * scale, timestamp))
            .put("pg_stat_bgwriter_buffers_backend_total",
                single("pg_stat_bgwriter_buffers_backend_total",
                    scope, 8000.0 * scale, timestamp))

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

    private static Sample activitySample(final String state,
                                          final double value,
                                          final ImmutableMap<String, String> scope,
                                          final long timestamp) {
        return Sample.builder()
            .name("pg_stat_activity_count")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
