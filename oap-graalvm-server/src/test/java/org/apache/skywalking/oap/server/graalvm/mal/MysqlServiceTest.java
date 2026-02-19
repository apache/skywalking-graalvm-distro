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
 * Comparison test for otel-rules/mysql/mysql-service.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagEqual — filtering by command type (insert, select, delete, update)
 *       and error type (max_connection, internal)</li>
 *   <li>tagMatch — regex matching for rollback|commit</li>
 *   <li>sum with aggregation labels — sum(['service_instance_id','host_name'])</li>
 *   <li>rate('PT1M') — on commands, queries, slow_queries, tps</li>
 *   <li>Tag closure in expSuffix — tags.host_name = 'mysql::' + tags.host_name</li>
 *   <li>Scope: service(['host_name'], Layer.MYSQL) — SERVICE scope only,
 *       no instance scope (unlike mysql-instance.yaml)</li>
 * </ul>
 */
class MysqlServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/mysql/mysql-service.yaml";
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
     * Build the full input map covering all sample names in mysql-service.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix and sum aggregation for all samples):
     * #   host_name="mysql-host", service_instance_id="mysql:3306"
     *
     * # --- Commands (multiple samples for tagEqual/tagMatch) ---
     * mysql_global_status_commands_total{command="insert",host_name="mysql-host",service_instance_id="mysql:3306"} 500.0
     * mysql_global_status_commands_total{command="select",host_name="mysql-host",service_instance_id="mysql:3306"} 2000.0
     * mysql_global_status_commands_total{command="delete",host_name="mysql-host",service_instance_id="mysql:3306"} 100.0
     * mysql_global_status_commands_total{command="update",host_name="mysql-host",service_instance_id="mysql:3306"} 300.0
     * mysql_global_status_commands_total{command="rollback",host_name="mysql-host",service_instance_id="mysql:3306"} 10.0
     * mysql_global_status_commands_total{command="commit",host_name="mysql-host",service_instance_id="mysql:3306"} 800.0
     *
     * # --- Rate metrics ---
     * mysql_global_status_queries{host_name="mysql-host",service_instance_id="mysql:3306"} 10000.0
     *
     * # --- Threads metrics ---
     * mysql_global_status_threads_connected{host_name="mysql-host",service_instance_id="mysql:3306"} 50.0
     * mysql_global_status_threads_created{host_name="mysql-host",service_instance_id="mysql:3306"} 120.0
     * mysql_global_status_threads_running{host_name="mysql-host",service_instance_id="mysql:3306"} 5.0
     * mysql_global_status_threads_cached{host_name="mysql-host",service_instance_id="mysql:3306"} 8.0
     *
     * # --- Connection metrics ---
     * mysql_global_status_aborted_connects{host_name="mysql-host",service_instance_id="mysql:3306"} 3.0
     * mysql_global_variables_max_connections{host_name="mysql-host",service_instance_id="mysql:3306"} 1000.0
     *
     * # --- Connection errors (multiple samples for tagEqual) ---
     * mysql_global_status_connection_errors_total{error="max_connection",host_name="mysql-host",service_instance_id="mysql:3306"} 5.0
     * mysql_global_status_connection_errors_total{error="internal",host_name="mysql-host",service_instance_id="mysql:3306"} 2.0
     *
     * # --- Slow queries ---
     * mysql_global_status_slow_queries{host_name="mysql-host",service_instance_id="mysql:3306"} 15.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        // Scale factor: base=100 for TS1, base=200 for TS2
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "host_name", "mysql-host",
            "service_instance_id", "mysql:3306");

        return ImmutableMap.<String, SampleFamily>builder()
            // Commands — multiple samples for tagEqual/tagMatch
            .put("mysql_global_status_commands_total",
                SampleFamilyBuilder.newBuilder(
                    cmdSample("insert", 500.0 * scale, scope, timestamp),
                    cmdSample("select", 2000.0 * scale, scope, timestamp),
                    cmdSample("delete", 100.0 * scale, scope, timestamp),
                    cmdSample("update", 300.0 * scale, scope, timestamp),
                    cmdSample("rollback", 10.0 * scale, scope, timestamp),
                    cmdSample("commit", 800.0 * scale, scope, timestamp)
                ).build())

            // Rate metrics
            .put("mysql_global_status_queries",
                single("mysql_global_status_queries",
                    scope, 10000.0 * scale, timestamp))

            // Threads metrics
            .put("mysql_global_status_threads_connected",
                single("mysql_global_status_threads_connected",
                    scope, 50.0 * scale, timestamp))
            .put("mysql_global_status_threads_created",
                single("mysql_global_status_threads_created",
                    scope, 120.0 * scale, timestamp))
            .put("mysql_global_status_threads_running",
                single("mysql_global_status_threads_running",
                    scope, 5.0 * scale, timestamp))
            .put("mysql_global_status_threads_cached",
                single("mysql_global_status_threads_cached",
                    scope, 8.0 * scale, timestamp))

            // Connection metrics
            .put("mysql_global_status_aborted_connects",
                single("mysql_global_status_aborted_connects",
                    scope, 3.0 * scale, timestamp))
            .put("mysql_global_variables_max_connections",
                single("mysql_global_variables_max_connections",
                    scope, 1000.0, timestamp))

            // Connection errors — multiple samples for tagEqual
            .put("mysql_global_status_connection_errors_total",
                SampleFamilyBuilder.newBuilder(
                    errorSample("max_connection", 5.0 * scale, scope, timestamp),
                    errorSample("internal", 2.0 * scale, scope, timestamp)
                ).build())

            // Slow queries
            .put("mysql_global_status_slow_queries",
                single("mysql_global_status_slow_queries",
                    scope, 15.0 * scale, timestamp))

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

    private static Sample cmdSample(final String command,
                                     final double value,
                                     final ImmutableMap<String, String> scope,
                                     final long timestamp) {
        return Sample.builder()
            .name("mysql_global_status_commands_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("command", command)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    private static Sample errorSample(final String error,
                                       final double value,
                                       final ImmutableMap<String, String> scope,
                                       final long timestamp) {
        return Sample.builder()
            .name("mysql_global_status_connection_errors_total")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("error", error)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
