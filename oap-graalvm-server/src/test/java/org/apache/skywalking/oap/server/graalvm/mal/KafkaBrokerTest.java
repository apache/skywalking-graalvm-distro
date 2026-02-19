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
 * Comparison test for otel-rules/kafka/kafka-broker.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>tagMatch — filtering by area (heap), delayedOperation (Produce|Fetch),
 *       gc (G1 Young Generation|G1 Old Generation), and request
 *       (FetchConsumer|Produce|Fetch|FetchFollower)</li>
 *   <li>Multi-sample division — jvm_memory_bytes_used * 100 / jvm_memory_bytes_max
 *       (both with tagMatch('area','heap'))</li>
 *   <li>sum with aggregation labels — sum(['cluster','broker']),
 *       sum(['cluster','broker','delayedOperation']),
 *       sum(['cluster','broker','gc']),
 *       sum(['cluster','broker','request']),
 *       sum(['cluster','broker','topic'])</li>
 *   <li>rate('PT1M') — on counters and totals</li>
 *   <li>Tag closure in expSuffix — tags.cluster = 'kafka::' + tags.cluster</li>
 *   <li>Scope: instance(['cluster'], ['broker'], Layer.KAFKA)</li>
 * </ul>
 */
class KafkaBrokerTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/kafka/kafka-broker.yaml";
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
     * Build the full input map covering all sample names in kafka-broker.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate/increase computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="kafka-cluster", broker="broker-0"
     * #   Note: cluster is modified in-place by tag closure to "kafka::kafka-cluster"
     *
     * # --- CPU ---
     * process_cpu_seconds_total{cluster="kafka-cluster",broker="broker-0"} 500.0
     *
     * # --- JVM Memory (multi-sample with area tag for tagMatch) ---
     * jvm_memory_bytes_used{cluster="kafka-cluster",broker="broker-0",area="heap"} 536870912.0
     * jvm_memory_bytes_max{cluster="kafka-cluster",broker="broker-0",area="heap"} 1073741824.0
     *
     * # --- Broker Topic Metrics (counters) ---
     * kafka_server_brokertopicmetrics_messagesin_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 50000.0
     * kafka_server_brokertopicmetrics_bytesin_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 25000000.0
     * kafka_server_brokertopicmetrics_bytesout_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 30000000.0
     * kafka_server_brokertopicmetrics_replicationbytesin_total{cluster="kafka-cluster",broker="broker-0"} 12000000.0
     * kafka_server_brokertopicmetrics_replicationbytesout_total{cluster="kafka-cluster",broker="broker-0"} 15000000.0
     * kafka_server_brokertopicmetrics_totalfetchrequests_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 8000.0
     * kafka_server_brokertopicmetrics_totalproducerequests_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 6000.0
     *
     * # --- Replica Manager ---
     * kafka_server_replicamanager_underreplicatedpartitions{cluster="kafka-cluster",broker="broker-0"} 2.0
     * kafka_server_replicamanager_underminisrpartitioncount{cluster="kafka-cluster",broker="broker-0"} 1.0
     * kafka_server_replicamanager_partitioncount{cluster="kafka-cluster",broker="broker-0"} 120.0
     * kafka_server_replicamanager_leadercount{cluster="kafka-cluster",broker="broker-0"} 60.0
     * kafka_server_replicamanager_isrshrinks_total{cluster="kafka-cluster",broker="broker-0"} 3.0
     * kafka_server_replicamanager_isrexpands_total{cluster="kafka-cluster",broker="broker-0"} 5.0
     *
     * # --- Replica Fetcher Manager ---
     * kafka_server_replicafetchermanager_maxlag{cluster="kafka-cluster",broker="broker-0"} 10.0
     *
     * # --- Delayed Operation Purgatory (multi-sample with delayedOperation tag) ---
     * kafka_server_delayedoperationpurgatory_purgatorysize{cluster="kafka-cluster",broker="broker-0",delayedOperation="Produce"} 15.0
     * kafka_server_delayedoperationpurgatory_purgatorysize{cluster="kafka-cluster",broker="broker-0",delayedOperation="Fetch"} 25.0
     *
     * # --- JVM GC (multi-sample with gc tag) ---
     * jvm_gc_collection_seconds_count{cluster="kafka-cluster",broker="broker-0",gc="G1 Young Generation"} 400.0
     * jvm_gc_collection_seconds_count{cluster="kafka-cluster",broker="broker-0",gc="G1 Old Generation"} 10.0
     *
     * # --- Network Request Metrics (multi-sample with request tag) ---
     * kafka_network_requestmetrics_requests_total{cluster="kafka-cluster",broker="broker-0",request="FetchConsumer"} 3000.0
     * kafka_network_requestmetrics_requests_total{cluster="kafka-cluster",broker="broker-0",request="Produce"} 4000.0
     * kafka_network_requestmetrics_requests_total{cluster="kafka-cluster",broker="broker-0",request="Fetch"} 2000.0
     * kafka_network_requestmetrics_requestqueuetimems_count{cluster="kafka-cluster",broker="broker-0",request="Produce"} 1500.0
     * kafka_network_requestmetrics_requestqueuetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchConsumer"} 1200.0
     * kafka_network_requestmetrics_requestqueuetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchFollower"} 800.0
     * kafka_network_requestmetrics_remotetimems_count{cluster="kafka-cluster",broker="broker-0",request="Produce"} 2500.0
     * kafka_network_requestmetrics_remotetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchConsumer"} 1800.0
     * kafka_network_requestmetrics_remotetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchFollower"} 1000.0
     * kafka_network_requestmetrics_responsequeuetimems_count{cluster="kafka-cluster",broker="broker-0",request="Produce"} 600.0
     * kafka_network_requestmetrics_responsequeuetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchConsumer"} 500.0
     * kafka_network_requestmetrics_responsequeuetimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchFollower"} 300.0
     * kafka_network_requestmetrics_responsesendtimems_count{cluster="kafka-cluster",broker="broker-0",request="Produce"} 200.0
     * kafka_network_requestmetrics_responsesendtimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchConsumer"} 150.0
     * kafka_network_requestmetrics_responsesendtimems_count{cluster="kafka-cluster",broker="broker-0",request="FetchFollower"} 100.0
     *
     * # --- Network Socket Server ---
     * kafka_network_socketserver_networkprocessoravgidlepercent{cluster="kafka-cluster",broker="broker-0"} 0.85
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        // Scale factor: base=100 for TS1, base=200 for TS2
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "kafka-cluster",
            "broker", "broker-0");

        // Scope + topic label for topic-level metrics
        ImmutableMap<String, String> scopeWithTopic = ImmutableMap.of(
            "cluster", "kafka-cluster",
            "broker", "broker-0",
            "topic", "orders");

        return ImmutableMap.<String, SampleFamily>builder()

            // --- CPU ---
            .put("process_cpu_seconds_total",
                single("process_cpu_seconds_total",
                    scope, 500.0 * scale, timestamp))

            // --- JVM Memory (tagMatch 'area','heap') ---
            .put("jvm_memory_bytes_used",
                SampleFamilyBuilder.newBuilder(
                    memSample("jvm_memory_bytes_used", "heap",
                        536870912.0 * scale, scope, timestamp)
                ).build())
            .put("jvm_memory_bytes_max",
                SampleFamilyBuilder.newBuilder(
                    memSample("jvm_memory_bytes_max", "heap",
                        1073741824.0, scope, timestamp)
                ).build())

            // --- Broker Topic Metrics (counters with rate) ---
            .put("kafka_server_brokertopicmetrics_messagesin_total",
                single("kafka_server_brokertopicmetrics_messagesin_total",
                    scopeWithTopic, 50000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_bytesin_total",
                single("kafka_server_brokertopicmetrics_bytesin_total",
                    scopeWithTopic, 25000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_bytesout_total",
                single("kafka_server_brokertopicmetrics_bytesout_total",
                    scopeWithTopic, 30000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_replicationbytesin_total",
                single("kafka_server_brokertopicmetrics_replicationbytesin_total",
                    scope, 12000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_replicationbytesout_total",
                single("kafka_server_brokertopicmetrics_replicationbytesout_total",
                    scope, 15000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_totalfetchrequests_total",
                single("kafka_server_brokertopicmetrics_totalfetchrequests_total",
                    scopeWithTopic, 8000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_totalproducerequests_total",
                single("kafka_server_brokertopicmetrics_totalproducerequests_total",
                    scopeWithTopic, 6000.0 * scale, timestamp))

            // --- Replica Manager ---
            .put("kafka_server_replicamanager_underreplicatedpartitions",
                single("kafka_server_replicamanager_underreplicatedpartitions",
                    scope, 2.0 * scale, timestamp))
            .put("kafka_server_replicamanager_underminisrpartitioncount",
                single("kafka_server_replicamanager_underminisrpartitioncount",
                    scope, 1.0 * scale, timestamp))
            .put("kafka_server_replicamanager_partitioncount",
                single("kafka_server_replicamanager_partitioncount",
                    scope, 120.0 * scale, timestamp))
            .put("kafka_server_replicamanager_leadercount",
                single("kafka_server_replicamanager_leadercount",
                    scope, 60.0 * scale, timestamp))
            .put("kafka_server_replicamanager_isrshrinks_total",
                single("kafka_server_replicamanager_isrshrinks_total",
                    scope, 3.0 * scale, timestamp))
            .put("kafka_server_replicamanager_isrexpands_total",
                single("kafka_server_replicamanager_isrexpands_total",
                    scope, 5.0 * scale, timestamp))

            // --- Replica Fetcher Manager ---
            .put("kafka_server_replicafetchermanager_maxlag",
                single("kafka_server_replicafetchermanager_maxlag",
                    scope, 10.0 * scale, timestamp))

            // --- Delayed Operation Purgatory (tagMatch 'delayedOperation','Produce|Fetch') ---
            .put("kafka_server_delayedoperationpurgatory_purgatorysize",
                SampleFamilyBuilder.newBuilder(
                    purgatorySample("Produce", 15.0 * scale, scope, timestamp),
                    purgatorySample("Fetch", 25.0 * scale, scope, timestamp)
                ).build())

            // --- JVM GC (tagMatch 'gc','G1 Young Generation|G1 Old Generation') ---
            .put("jvm_gc_collection_seconds_count",
                SampleFamilyBuilder.newBuilder(
                    gcSample("G1 Young Generation", 400.0 * scale, scope, timestamp),
                    gcSample("G1 Old Generation", 10.0 * scale, scope, timestamp)
                ).build())

            // --- Network Request Metrics (tagMatch 'request',...) ---
            // requests_total: FetchConsumer|Produce|Fetch
            .put("kafka_network_requestmetrics_requests_total",
                SampleFamilyBuilder.newBuilder(
                    requestSample("kafka_network_requestmetrics_requests_total",
                        "FetchConsumer", 3000.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_requests_total",
                        "Produce", 4000.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_requests_total",
                        "Fetch", 2000.0 * scale, scope, timestamp)
                ).build())
            // requestqueuetimems_count: Produce|FetchConsumer|FetchFollower
            .put("kafka_network_requestmetrics_requestqueuetimems_count",
                SampleFamilyBuilder.newBuilder(
                    requestSample("kafka_network_requestmetrics_requestqueuetimems_count",
                        "Produce", 1500.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_requestqueuetimems_count",
                        "FetchConsumer", 1200.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_requestqueuetimems_count",
                        "FetchFollower", 800.0 * scale, scope, timestamp)
                ).build())
            // remotetimems_count: Produce|FetchConsumer|FetchFollower
            .put("kafka_network_requestmetrics_remotetimems_count",
                SampleFamilyBuilder.newBuilder(
                    requestSample("kafka_network_requestmetrics_remotetimems_count",
                        "Produce", 2500.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_remotetimems_count",
                        "FetchConsumer", 1800.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_remotetimems_count",
                        "FetchFollower", 1000.0 * scale, scope, timestamp)
                ).build())
            // responsequeuetimems_count: Produce|FetchConsumer|FetchFollower
            .put("kafka_network_requestmetrics_responsequeuetimems_count",
                SampleFamilyBuilder.newBuilder(
                    requestSample("kafka_network_requestmetrics_responsequeuetimems_count",
                        "Produce", 600.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_responsequeuetimems_count",
                        "FetchConsumer", 500.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_responsequeuetimems_count",
                        "FetchFollower", 300.0 * scale, scope, timestamp)
                ).build())
            // responsesendtimems_count: Produce|FetchConsumer|FetchFollower
            .put("kafka_network_requestmetrics_responsesendtimems_count",
                SampleFamilyBuilder.newBuilder(
                    requestSample("kafka_network_requestmetrics_responsesendtimems_count",
                        "Produce", 200.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_responsesendtimems_count",
                        "FetchConsumer", 150.0 * scale, scope, timestamp),
                    requestSample("kafka_network_requestmetrics_responsesendtimems_count",
                        "FetchFollower", 100.0 * scale, scope, timestamp)
                ).build())

            // --- Network Socket Server ---
            .put("kafka_network_socketserver_networkprocessoravgidlepercent",
                single("kafka_network_socketserver_networkprocessoravgidlepercent",
                    scope, 0.85 * scale, timestamp))

            .build();
    }

    // ---------------------------------------------------------------
    // Helper: single-sample SampleFamily
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // Helper: JVM memory sample with area tag
    // ---------------------------------------------------------------

    private static Sample memSample(final String name,
                                     final String area,
                                     final double value,
                                     final ImmutableMap<String, String> scope,
                                     final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("area", area)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    // ---------------------------------------------------------------
    // Helper: purgatory sample with delayedOperation tag
    // ---------------------------------------------------------------

    private static Sample purgatorySample(final String delayedOperation,
                                           final double value,
                                           final ImmutableMap<String, String> scope,
                                           final long timestamp) {
        return Sample.builder()
            .name("kafka_server_delayedoperationpurgatory_purgatorysize")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("delayedOperation", delayedOperation)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    // ---------------------------------------------------------------
    // Helper: GC sample with gc tag
    // ---------------------------------------------------------------

    private static Sample gcSample(final String gc,
                                    final double value,
                                    final ImmutableMap<String, String> scope,
                                    final long timestamp) {
        return Sample.builder()
            .name("jvm_gc_collection_seconds_count")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("gc", gc)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    // ---------------------------------------------------------------
    // Helper: request metrics sample with request tag
    // ---------------------------------------------------------------

    private static Sample requestSample(final String name,
                                         final String request,
                                         final double value,
                                         final ImmutableMap<String, String> scope,
                                         final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("request", request)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
