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
 * Comparison test for otel-rules/activemq/activemq-destination.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>Tag closure in expSuffix — tags.cluster = 'activemq::' + tags.cluster</li>
 *   <li>Scope: ENDPOINT(['cluster'], ['destinationName'], Layer.ACTIVEMQ)</li>
 *   <li>tagEqual — filtering by destinationType=Topic (topic_consumer_count)</li>
 *   <li>sum with aggregation labels —
 *       sum(['cluster','destinationName','destinationType'])</li>
 *   <li>avg/max with aggregation labels — avg/max(['cluster','destinationName',
 *       'destinationType'])</li>
 *   <li>Multiple destinationType values in one SampleFamily — Queue and Topic</li>
 * </ul>
 */
class ActivemqDestinationTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/activemq/activemq-destination.yaml";
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
     * Build the full input map covering all sample names in activemq-destination.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ.
     *
     * <p>Most metrics use sum(['cluster','destinationName','destinationType']),
     * so each SampleFamily includes both Queue and Topic samples. The
     * topic_consumer_count metric uses tagEqual('destinationType','Topic') to
     * filter only Topic destinations before summing.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="my-activemq-cluster", destinationName="orders"
     * # Additional aggregation label:
     * #   destinationType="Queue" or "Topic"
     *
     * # --- Producer/Consumer counts ---
     * org_apache_activemq_Broker_ProducerCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 5.0
     * org_apache_activemq_Broker_ProducerCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 3.0
     * org_apache_activemq_Broker_ConsumerCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 10.0
     * org_apache_activemq_Broker_ConsumerCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 8.0
     *
     * # --- Queue size ---
     * org_apache_activemq_Broker_QueueSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 250.0
     * org_apache_activemq_Broker_QueueSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 100.0
     *
     * # --- Memory metrics ---
     * org_apache_activemq_Broker_MemoryUsageByteCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 1048576.0
     * org_apache_activemq_Broker_MemoryUsageByteCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 524288.0
     * org_apache_activemq_Broker_MemoryPercentUsage{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 25.0
     * org_apache_activemq_Broker_MemoryPercentUsage{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 12.0
     *
     * # --- Message count metrics ---
     * org_apache_activemq_Broker_EnqueueCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 50000.0
     * org_apache_activemq_Broker_EnqueueCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 30000.0
     * org_apache_activemq_Broker_DequeueCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 48000.0
     * org_apache_activemq_Broker_DequeueCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 29000.0
     *
     * # --- Enqueue time metrics ---
     * org_apache_activemq_Broker_AverageEnqueueTime{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 8.5
     * org_apache_activemq_Broker_AverageEnqueueTime{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 4.2
     * org_apache_activemq_Broker_MaxEnqueueTime{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 150.0
     * org_apache_activemq_Broker_MaxEnqueueTime{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 80.0
     *
     * # --- Dispatch/Expired/InFlight counts ---
     * org_apache_activemq_Broker_DispatchCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 47500.0
     * org_apache_activemq_Broker_DispatchCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 28500.0
     * org_apache_activemq_Broker_ExpiredCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 100.0
     * org_apache_activemq_Broker_ExpiredCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 50.0
     * org_apache_activemq_Broker_InFlightCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 20.0
     * org_apache_activemq_Broker_InFlightCount{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 10.0
     *
     * # --- Message size metrics ---
     * org_apache_activemq_Broker_AverageMessageSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 1024.0
     * org_apache_activemq_Broker_AverageMessageSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 512.0
     * org_apache_activemq_Broker_MaxMessageSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Queue"} 65536.0
     * org_apache_activemq_Broker_MaxMessageSize{cluster="my-activemq-cluster",destinationName="orders",destinationType="Topic"} 32768.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        return ImmutableMap.<String, SampleFamily>builder()
            // Producer/Consumer counts
            .put("org_apache_activemq_Broker_ProducerCount",
                destFamily("org_apache_activemq_Broker_ProducerCount",
                    5.0 * scale, 3.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_ConsumerCount",
                destFamily("org_apache_activemq_Broker_ConsumerCount",
                    10.0 * scale, 8.0 * scale, timestamp))

            // Queue size
            .put("org_apache_activemq_Broker_QueueSize",
                destFamily("org_apache_activemq_Broker_QueueSize",
                    250.0 * scale, 100.0 * scale, timestamp))

            // Memory metrics
            .put("org_apache_activemq_Broker_MemoryUsageByteCount",
                destFamily("org_apache_activemq_Broker_MemoryUsageByteCount",
                    1048576.0 * scale, 524288.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_MemoryPercentUsage",
                destFamily("org_apache_activemq_Broker_MemoryPercentUsage",
                    25.0 * scale, 12.0 * scale, timestamp))

            // Message count metrics
            .put("org_apache_activemq_Broker_EnqueueCount",
                destFamily("org_apache_activemq_Broker_EnqueueCount",
                    50000.0 * scale, 30000.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_DequeueCount",
                destFamily("org_apache_activemq_Broker_DequeueCount",
                    48000.0 * scale, 29000.0 * scale, timestamp))

            // Enqueue time metrics
            .put("org_apache_activemq_Broker_AverageEnqueueTime",
                destFamily("org_apache_activemq_Broker_AverageEnqueueTime",
                    8.5 * scale, 4.2 * scale, timestamp))
            .put("org_apache_activemq_Broker_MaxEnqueueTime",
                destFamily("org_apache_activemq_Broker_MaxEnqueueTime",
                    150.0 * scale, 80.0 * scale, timestamp))

            // Dispatch/Expired/InFlight counts
            .put("org_apache_activemq_Broker_DispatchCount",
                destFamily("org_apache_activemq_Broker_DispatchCount",
                    47500.0 * scale, 28500.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_ExpiredCount",
                destFamily("org_apache_activemq_Broker_ExpiredCount",
                    100.0 * scale, 50.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_InFlightCount",
                destFamily("org_apache_activemq_Broker_InFlightCount",
                    20.0 * scale, 10.0 * scale, timestamp))

            // Message size metrics
            .put("org_apache_activemq_Broker_AverageMessageSize",
                destFamily("org_apache_activemq_Broker_AverageMessageSize",
                    1024.0 * scale, 512.0 * scale, timestamp))
            .put("org_apache_activemq_Broker_MaxMessageSize",
                destFamily("org_apache_activemq_Broker_MaxMessageSize",
                    65536.0 * scale, 32768.0 * scale, timestamp))

            .build();
    }

    /**
     * Creates a SampleFamily with two samples: one for Queue and one for Topic
     * destination types. Both share the same cluster and destinationName scope
     * labels.
     */
    private static SampleFamily destFamily(final String name,
                                            final double queueValue,
                                            final double topicValue,
                                            final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            destSample(name, "Queue", queueValue, timestamp),
            destSample(name, "Topic", topicValue, timestamp)
        ).build();
    }

    private static Sample destSample(final String name,
                                      final String destinationType,
                                      final double value,
                                      final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.of(
                "cluster", "my-activemq-cluster",
                "destinationName", "orders",
                "destinationType", destinationType))
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
