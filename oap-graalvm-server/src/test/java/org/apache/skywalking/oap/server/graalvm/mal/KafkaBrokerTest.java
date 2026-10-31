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
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
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
 *   <li>tagEqual/tagNotEqual('topic','') — all-topics total vs per-topic series</li>
 *   <li>tagEqual('quantile','0.50') — p50 of the request latency summaries</li>
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
        Instant.parse("2024-01-01T00:00:10Z").toEpochMilli();

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
 * # Message/byte/request meters come as the all-topics total (no topic label) beside the per-topic
 * # series; broker rules read tagEqual('topic','') and topic rules tagNotEqual('topic','').
 * kafka_server_brokertopicmetrics_messagesin_total{cluster="kafka-cluster",broker="broker-0"} 50000.0
 * kafka_server_brokertopicmetrics_messagesin_total{cluster="kafka-cluster",broker="broker-0",topic="orders"} 35000.0
 * kafka_server_brokertopicmetrics_messagesin_total{cluster="kafka-cluster",broker="broker-0",topic="payments"} 15000.0
 * (bytesin_total, bytesout_total, totalfetchrequests_total, totalproducerequests_total: same shape)
 * kafka_server_brokertopicmetrics_replicationbytesin_total{cluster="kafka-cluster",broker="broker-0"} 12000000.0
 * kafka_server_brokertopicmetrics_replicationbytesout_total{cluster="kafka-cluster",broker="broker-0"} 15000000.0
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
     * # Latency summaries (tagEqual 'quantile','0.50'); the 0.99 series must not leak into the p50 rules.
 * kafka_network_requestmetrics_requestqueuetimems{cluster="kafka-cluster",broker="broker-0",request="Produce",quantile="0.50"} 15.0
 * kafka_network_requestmetrics_requestqueuetimems{cluster="kafka-cluster",broker="broker-0",request="Produce",quantile="0.99"} 150.0
 * (FetchConsumer, FetchFollower likewise; remotetimems, responsequeuetimems, responsesendtimems: same shape)
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
                topicTotals("kafka_server_brokertopicmetrics_messagesin_total",
                    50000.0 * scale, scope, timestamp))
            .put("kafka_server_brokertopicmetrics_bytesin_total",
                topicTotals("kafka_server_brokertopicmetrics_bytesin_total",
                    25000000.0 * scale, scope, timestamp))
            .put("kafka_server_brokertopicmetrics_bytesout_total",
                topicTotals("kafka_server_brokertopicmetrics_bytesout_total",
                    30000000.0 * scale, scope, timestamp))
            .put("kafka_server_brokertopicmetrics_replicationbytesin_total",
                single("kafka_server_brokertopicmetrics_replicationbytesin_total",
                    scope, 12000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_replicationbytesout_total",
                single("kafka_server_brokertopicmetrics_replicationbytesout_total",
                    scope, 15000000.0 * scale, timestamp))
            .put("kafka_server_brokertopicmetrics_totalfetchrequests_total",
                topicTotals("kafka_server_brokertopicmetrics_totalfetchrequests_total",
                    8000.0 * scale, scope, timestamp))
            .put("kafka_server_brokertopicmetrics_totalproducerequests_total",
                topicTotals("kafka_server_brokertopicmetrics_totalproducerequests_total",
                    6000.0 * scale, scope, timestamp))

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
            // Latency p50: Produce|FetchConsumer|FetchFollower, tagEqual('quantile','0.50')
            .put("kafka_network_requestmetrics_requestqueuetimems",
                latencySummary("kafka_network_requestmetrics_requestqueuetimems",
                    15.0 * scale, scope, timestamp))
            .put("kafka_network_requestmetrics_remotetimems",
                latencySummary("kafka_network_requestmetrics_remotetimems",
                    25.0 * scale, scope, timestamp))
            .put("kafka_network_requestmetrics_responsequeuetimems",
                latencySummary("kafka_network_requestmetrics_responsequeuetimems",
                    6.0 * scale, scope, timestamp))
            .put("kafka_network_requestmetrics_responsesendtimems",
                latencySummary("kafka_network_requestmetrics_responsesendtimems",
                    2.0 * scale, scope, timestamp))

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
    // Helper: all-topics total (no topic label) plus two per-topic series
    // ---------------------------------------------------------------

    private static SampleFamily topicTotals(final String name,
                                            final double total,
                                            final ImmutableMap<String, String> scope,
                                            final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            Sample.builder()
                .name(name)
                .labels(scope)
                .value(total)
                .timestamp(timestamp)
                .build(),
            topicSample(name, "orders", total * 0.7, scope, timestamp),
            topicSample(name, "payments", total * 0.3, scope, timestamp)
        ).build();
    }

    private static Sample topicSample(final String name,
                                      final String topic,
                                      final double value,
                                      final ImmutableMap<String, String> scope,
                                      final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("topic", topic)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }

    // ---------------------------------------------------------------
    // Helper: request latency summary, p50 and p99 per request type
    // ---------------------------------------------------------------

    private static SampleFamily latencySummary(final String name,
                                               final double p50,
                                               final ImmutableMap<String, String> scope,
                                               final long timestamp) {
        String[] requests = {"Produce", "FetchConsumer", "FetchFollower"};
        Sample[] samples = new Sample[requests.length * 2];
        for (int i = 0; i < requests.length; i++) {
            samples[i * 2] = quantileSample(name, requests[i], "0.50",
                p50 * (i + 1), scope, timestamp);
            samples[i * 2 + 1] = quantileSample(name, requests[i], "0.99",
                p50 * (i + 1) * 10, scope, timestamp);
        }
        return SampleFamilyBuilder.newBuilder(samples).build();
    }

    private static Sample quantileSample(final String name,
                                         final String request,
                                         final String quantile,
                                         final double value,
                                         final ImmutableMap<String, String> scope,
                                         final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("request", request)
                .put("quantile", quantile)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
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
