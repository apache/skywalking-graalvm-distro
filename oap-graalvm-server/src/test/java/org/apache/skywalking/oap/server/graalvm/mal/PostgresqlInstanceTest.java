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
 * Comparison test for otel-rules/postgresql/postgresql-instance.yaml.
 *
 * <p>This file exercises:
 * <ul>
 *   <li>tagEqual — filtering by state (active)</li>
 *   <li>tagMatch — regex matching for idle|idle in transaction|idle in transaction (aborted)</li>
 *   <li>Custom tag transform — tags.mode = tags.datname + ":" + tags.mode (locks_count)</li>
 *   <li>Multi-sample division — pg_stat_database_blks_hit*100 /
 *       (pg_stat_database_blks_read + pg_stat_database_blks_hit)</li>
 *   <li>sum with aggregation labels — sum(['datname','host_name','service_instance_id'])</li>
 *   <li>rate('PT1M') — on rows, transactions, conflicts, deadlocks, buffers, etc.</li>
 *   <li>Passthrough metrics — pg_settings_* (no aggregation, no rate)</li>
 *   <li>Tag closure in expSuffix — tags.host_name = 'postgresql::' + tags.host_name</li>
 *   <li>Scope: service(['host_name'], Layer.POSTGRESQL).instance(['host_name'],
 *       ['service_instance_id'], Layer.POSTGRESQL)</li>
 * </ul>
 */
class PostgresqlInstanceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/postgresql/postgresql-instance.yaml";
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
     * Build the full input map covering all sample names in postgresql-instance.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   host_name="pg-host-1", service_instance_id="pg:5432"
     *
     * # --- PostgreSQL configuration settings (passthrough) ---
     * pg_settings_shared_buffers_bytes{host_name="pg-host-1",service_instance_id="pg:5432"} 134217728.0
     * pg_settings_effective_cache_size_bytes{host_name="pg-host-1",service_instance_id="pg:5432"} 4294967296.0
     * pg_settings_maintenance_work_mem_bytes{host_name="pg-host-1",service_instance_id="pg:5432"} 67108864.0
     * pg_settings_work_mem_bytes{host_name="pg-host-1",service_instance_id="pg:5432"} 4194304.0
     * pg_settings_seq_page_cost{host_name="pg-host-1",service_instance_id="pg:5432"} 1.0
     * pg_settings_random_page_cost{host_name="pg-host-1",service_instance_id="pg:5432"} 4.0
     * pg_settings_max_wal_size_bytes{host_name="pg-host-1",service_instance_id="pg:5432"} 1073741824.0
     * pg_settings_max_parallel_workers{host_name="pg-host-1",service_instance_id="pg:5432"} 8.0
     * pg_settings_max_worker_processes{host_name="pg-host-1",service_instance_id="pg:5432"} 8.0
     *
     * # --- Row operation counters (rate with sum on datname,host_name,service_instance_id) ---
     * pg_stat_database_tup_fetched{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 50000.0
     * pg_stat_database_tup_deleted{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 1200.0
     * pg_stat_database_tup_inserted{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 8000.0
     * pg_stat_database_tup_updated{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 3500.0
     * pg_stat_database_tup_returned{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 120000.0
     *
     * # --- Locks (custom tag transform: tags.mode = tags.datname + ":" + tags.mode) ---
     * pg_locks_count{datname="mydb",mode="AccessShareLock",host_name="pg-host-1",service_instance_id="pg:5432"} 25.0
     * pg_locks_count{datname="mydb",mode="RowExclusiveLock",host_name="pg-host-1",service_instance_id="pg:5432"} 10.0
     *
     * # --- Sessions (tagEqual for active, tagMatch for idle variants) ---
     * pg_stat_activity_count{datname="mydb",state="active",host_name="pg-host-1",service_instance_id="pg:5432"} 15.0
     * pg_stat_activity_count{datname="mydb",state="idle",host_name="pg-host-1",service_instance_id="pg:5432"} 30.0
     * pg_stat_activity_count{datname="mydb",state="idle in transaction",host_name="pg-host-1",service_instance_id="pg:5432"} 5.0
     * pg_stat_activity_count{datname="mydb",state="idle in transaction (aborted)",host_name="pg-host-1",service_instance_id="pg:5432"} 2.0
     *
     * # --- Transaction counters (rate with sum) ---
     * pg_stat_database_xact_commit{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 25000.0
     * pg_stat_database_xact_rollback{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 150.0
     *
     * # --- Cache hit rate (multi-sample: blks_hit*100 / (blks_read + blks_hit)) ---
     * pg_stat_database_blks_hit{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 95000.0
     * pg_stat_database_blks_read{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 5000.0
     *
     * # --- Temporary files (rate with sum) ---
     * pg_stat_database_temp_bytes{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 1048576.0
     *
     * # --- Checkpoint metrics (rate, no datname aggregation) ---
     * pg_stat_bgwriter_checkpoint_write_time_total{host_name="pg-host-1",service_instance_id="pg:5432"} 45000.0
     * pg_stat_bgwriter_checkpoint_sync_time_total{host_name="pg-host-1",service_instance_id="pg:5432"} 1200.0
     * pg_stat_bgwriter_checkpoints_req_total{host_name="pg-host-1",service_instance_id="pg:5432"} 50.0
     * pg_stat_bgwriter_checkpoints_timed_total{host_name="pg-host-1",service_instance_id="pg:5432"} 300.0
     *
     * # --- Conflicts and deadlocks (rate with sum on datname) ---
     * pg_stat_database_conflicts{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 8.0
     * pg_stat_database_deadlocks{datname="mydb",host_name="pg-host-1",service_instance_id="pg:5432"} 2.0
     *
     * # --- Buffer metrics (rate, no datname aggregation) ---
     * pg_stat_bgwriter_buffers_checkpoint_total{host_name="pg-host-1",service_instance_id="pg:5432"} 10000.0
     * pg_stat_bgwriter_buffers_clean_total{host_name="pg-host-1",service_instance_id="pg:5432"} 500.0
     * pg_stat_bgwriter_buffers_backend_fsync_total{host_name="pg-host-1",service_instance_id="pg:5432"} 3.0
     * pg_stat_bgwriter_buffers_alloc_total{host_name="pg-host-1",service_instance_id="pg:5432"} 25000.0
     * pg_stat_bgwriter_buffers_backend_total{host_name="pg-host-1",service_instance_id="pg:5432"} 8000.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        // Scale factor: base=100 for TS1, base=200 for TS2
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "pg-host-1",
            "service_instance_id", "pg:5432");

        // Scope labels + datname for pg_stat_database_* and pg_stat_activity_count
        ImmutableMap<String, String> scopeWithDb = ImmutableMap.of(
            "host_name", "pg-host-1",
            "service_instance_id", "pg:5432",
            "datname", "mydb");

        return ImmutableMap.<String, SampleFamily>builder()
            // --- PostgreSQL configuration settings (passthrough) ---
            .put("pg_settings_shared_buffers_bytes",
                single("pg_settings_shared_buffers_bytes",
                    scope, 134217728.0, timestamp))
            .put("pg_settings_effective_cache_size_bytes",
                single("pg_settings_effective_cache_size_bytes",
                    scope, 4294967296.0, timestamp))
            .put("pg_settings_maintenance_work_mem_bytes",
                single("pg_settings_maintenance_work_mem_bytes",
                    scope, 67108864.0, timestamp))
            .put("pg_settings_work_mem_bytes",
                single("pg_settings_work_mem_bytes",
                    scope, 4194304.0, timestamp))
            .put("pg_settings_seq_page_cost",
                single("pg_settings_seq_page_cost",
                    scope, 1.0, timestamp))
            .put("pg_settings_random_page_cost",
                single("pg_settings_random_page_cost",
                    scope, 4.0, timestamp))
            .put("pg_settings_max_wal_size_bytes",
                single("pg_settings_max_wal_size_bytes",
                    scope, 1073741824.0, timestamp))
            .put("pg_settings_max_parallel_workers",
                single("pg_settings_max_parallel_workers",
                    scope, 8.0, timestamp))
            .put("pg_settings_max_worker_processes",
                single("pg_settings_max_worker_processes",
                    scope, 8.0, timestamp))

            // --- Row operation counters ---
            .put("pg_stat_database_tup_fetched",
                single("pg_stat_database_tup_fetched",
                    scopeWithDb, 50000.0 * scale, timestamp))
            .put("pg_stat_database_tup_deleted",
                single("pg_stat_database_tup_deleted",
                    scopeWithDb, 1200.0 * scale, timestamp))
            .put("pg_stat_database_tup_inserted",
                single("pg_stat_database_tup_inserted",
                    scopeWithDb, 8000.0 * scale, timestamp))
            .put("pg_stat_database_tup_updated",
                single("pg_stat_database_tup_updated",
                    scopeWithDb, 3500.0 * scale, timestamp))
            .put("pg_stat_database_tup_returned",
                single("pg_stat_database_tup_returned",
                    scopeWithDb, 120000.0 * scale, timestamp))

            // --- Locks (needs datname and mode for tag transform) ---
            .put("pg_locks_count",
                SampleFamilyBuilder.newBuilder(
                    lockSample("AccessShareLock", 25.0 * scale,
                        scopeWithDb, timestamp),
                    lockSample("RowExclusiveLock", 10.0 * scale,
                        scopeWithDb, timestamp)
                ).build())

            // --- Sessions (tagEqual for active, tagMatch for idle variants) ---
            .put("pg_stat_activity_count",
                SampleFamilyBuilder.newBuilder(
                    activitySample("active", 15.0 * scale,
                        scopeWithDb, timestamp),
                    activitySample("idle", 30.0 * scale,
                        scopeWithDb, timestamp),
                    activitySample("idle in transaction", 5.0 * scale,
                        scopeWithDb, timestamp),
                    activitySample("idle in transaction (aborted)", 2.0 * scale,
                        scopeWithDb, timestamp)
                ).build())

            // --- Transaction counters ---
            .put("pg_stat_database_xact_commit",
                single("pg_stat_database_xact_commit",
                    scopeWithDb, 25000.0 * scale, timestamp))
            .put("pg_stat_database_xact_rollback",
                single("pg_stat_database_xact_rollback",
                    scopeWithDb, 150.0 * scale, timestamp))

            // --- Cache hit rate (multi-sample division) ---
            .put("pg_stat_database_blks_hit",
                single("pg_stat_database_blks_hit",
                    scopeWithDb, 95000.0 * scale, timestamp))
            .put("pg_stat_database_blks_read",
                single("pg_stat_database_blks_read",
                    scopeWithDb, 5000.0 * scale, timestamp))

            // --- Temporary files ---
            .put("pg_stat_database_temp_bytes",
                single("pg_stat_database_temp_bytes",
                    scopeWithDb, 1048576.0 * scale, timestamp))

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
                    scopeWithDb, 8.0 * scale, timestamp))
            .put("pg_stat_database_deadlocks",
                single("pg_stat_database_deadlocks",
                    scopeWithDb, 2.0 * scale, timestamp))

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

    private static Sample lockSample(final String mode,
                                      final double value,
                                      final ImmutableMap<String, String> scopeWithDb,
                                      final long timestamp) {
        return Sample.builder()
            .name("pg_locks_count")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scopeWithDb)
                .put("mode", mode)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample activitySample(final String state,
                                          final double value,
                                          final ImmutableMap<String, String> scopeWithDb,
                                          final long timestamp) {
        return Sample.builder()
            .name("pg_stat_activity_count")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scopeWithDb)
                .put("state", state)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
