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
 * Comparison test for otel-rules/mongodb/mongodb-cluster.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagNotEqual — filtering out cl_role='mongos' and database='local'</li>
 *   <li>tagEqual — filtering by conn_type='current', rs_state='1'</li>
 *   <li>Tag closure in expSuffix — tags.cluster = 'mongodb::' + tags.cluster</li>
 *   <li>Tag closure in repl_lag — tags.rs_nm = tags.set</li>
 *   <li>tagNotEqual('state','ARBITER') on replication lag</li>
 *   <li>Scope: SERVICE with service(['cluster'], Layer.MONGODB)</li>
 *   <li>max, sum, rate('PT1M'), avg aggregations</li>
 * </ul>
 */
class MongodbClusterTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/mongodb/mongodb-cluster.yaml";
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
     * Build the full input map covering all sample names in mongodb-cluster.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="test-cluster", service_instance_id="mongo-node-1:27017"
     * # Additional labels vary per metric.
     *
     * # --- Uptime ---
     * mongodb_ss_uptime{cluster="test-cluster",service_instance_id="mongo-node-1:27017"} 86400.0
     *
     * # --- DB stats (tagNotEqual cl_role!=mongos, database!=local) ---
     * mongodb_dbstats_dataSize{cluster="test-cluster",service_instance_id="mongo-node-1:27017",cl_role="replset",database="testdb",rs_nm="rs0",rs_state="1"} 5242880.0
     * mongodb_dbstats_collections{cluster="test-cluster",service_instance_id="mongo-node-1:27017",cl_role="replset",database="testdb",rs_nm="rs0",rs_state="1"} 25.0
     * mongodb_dbstats_objects{cluster="test-cluster",service_instance_id="mongo-node-1:27017",cl_role="replset",database="testdb",rs_nm="rs0",rs_state="1"} 50000.0
     * mongodb_dbstats_indexSize{cluster="test-cluster",service_instance_id="mongo-node-1:27017",cl_role="replset",database="testdb",rs_nm="rs0",rs_state="1"} 1048576.0
     * mongodb_dbstats_indexes{cluster="test-cluster",service_instance_id="mongo-node-1:27017",cl_role="replset",database="testdb",rs_nm="rs0",rs_state="1"} 10.0
     *
     * # --- Document metrics (needs doc_op_type) ---
     * mongodb_ss_metrics_document{cluster="test-cluster",service_instance_id="mongo-node-1:27017",doc_op_type="inserted"} 3000.0
     *
     * # --- Operation counters (needs legacy_op_type) ---
     * mongodb_ss_opcounters{cluster="test-cluster",service_instance_id="mongo-node-1:27017",legacy_op_type="insert"} 5000.0
     *
     * # --- Connections (tagEqual conn_type='current') ---
     * mongodb_ss_connections{cluster="test-cluster",service_instance_id="mongo-node-1:27017",conn_type="current"} 150.0
     *
     * # --- Cursor (needs csr_type) ---
     * mongodb_ss_metrics_cursor_open{cluster="test-cluster",service_instance_id="mongo-node-1:27017",csr_type="total"} 42.0
     *
     * # --- Replication lag (tag closure: tags.rs_nm = tags.set, tagNotEqual state!=ARBITER) ---
     * mongodb_mongod_replset_member_replication_lag{cluster="test-cluster",service_instance_id="mongo-node-1:27017",set="rs0",state="SECONDARY"} 2.5
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "test-cluster",
            "service_instance_id", "mongo-node-1:27017");

        // Labels for dbstats: cl_role != mongos, database != local, with rs_nm and rs_state
        ImmutableMap<String, String> dbstatsLabels = ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put("cl_role", "replset")
            .put("database", "testdb")
            .put("rs_nm", "rs0")
            .put("rs_state", "1")
            .build();

        return ImmutableMap.<String, SampleFamily>builder()
            // Uptime — max(['cluster','service_instance_id'])
            .put("mongodb_ss_uptime",
                single("mongodb_ss_uptime", scope, 86400.0 * scale, timestamp))

            // DB stats — tagNotEqual('cl_role','mongos').tagNotEqual('database','local')
            .put("mongodb_dbstats_dataSize",
                single("mongodb_dbstats_dataSize",
                    dbstatsLabels, 5242880.0 * scale, timestamp))
            .put("mongodb_dbstats_collections",
                single("mongodb_dbstats_collections",
                    dbstatsLabels, 25.0 * scale, timestamp))
            .put("mongodb_dbstats_objects",
                single("mongodb_dbstats_objects",
                    dbstatsLabels, 50000.0 * scale, timestamp))
            .put("mongodb_dbstats_indexSize",
                single("mongodb_dbstats_indexSize",
                    dbstatsLabels, 1048576.0 * scale, timestamp))
            .put("mongodb_dbstats_indexes",
                single("mongodb_dbstats_indexes",
                    dbstatsLabels, 10.0 * scale, timestamp))

            // Document metrics — max(['cluster','doc_op_type','service_instance_id']).rate
            .put("mongodb_ss_metrics_document",
                single("mongodb_ss_metrics_document",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("doc_op_type", "inserted")
                        .build(),
                    3000.0 * scale, timestamp))

            // Operation counters — max(['cluster','legacy_op_type','service_instance_id']).rate
            .put("mongodb_ss_opcounters",
                single("mongodb_ss_opcounters",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("legacy_op_type", "insert")
                        .build(),
                    5000.0 * scale, timestamp))

            // Connections — tagEqual('conn_type','current')
            .put("mongodb_ss_connections",
                single("mongodb_ss_connections",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("conn_type", "current")
                        .build(),
                    150.0 * scale, timestamp))

            // Cursor — max(['cluster','csr_type','service_instance_id'])
            .put("mongodb_ss_metrics_cursor_open",
                single("mongodb_ss_metrics_cursor_open",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("csr_type", "total")
                        .build(),
                    42.0 * scale, timestamp))

            // Replication lag — tag({tags -> tags.rs_nm = tags.set}).tagNotEqual('state','ARBITER')
            .put("mongodb_mongod_replset_member_replication_lag",
                single("mongodb_mongod_replset_member_replication_lag",
                    ImmutableMap.<String, String>builder()
                        .putAll(scope)
                        .put("set", "rs0")
                        .put("state", "SECONDARY")
                        .build(),
                    2.5 * scale, timestamp))

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
}
