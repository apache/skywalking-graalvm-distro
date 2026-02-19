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
 * Comparison test for otel-rules/rabbitmq/rabbitmq-node.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>Tag closure in expSuffix — tags.cluster = 'rabbitmq::' + tags.cluster</li>
 *   <li>Scope: INSTANCE(['cluster'], ['node'], Layer.RABBITMQ)</li>
 *   <li>Multi-sample subtraction — rabbitmq_channels - rabbitmq_channel_consumers
 *       (publisher_total)</li>
 *   <li>Multi-sample addition — sum of 6 rate terms (outgoing_messages_total)</li>
 *   <li>rate('PT1M') — on incoming_messages, outgoing_messages_total</li>
 *   <li>tagEqual — filtering by usage=blocks_size / carriers_size (erlang_vm_allocators)</li>
 *   <li>tagEqual with multiple key-value pairs — usage + kind filtering</li>
 *   <li>Division and multiplication — allocated_used_percent, allocated_unused_percent</li>
 *   <li>Multi-sample subtraction — carriers_size - blocks_size
 *       (allocated_unused_bytes/percent)</li>
 *   <li>sum with aggregation labels — sum(['cluster','node']) and
 *       sum(['cluster','node','alloc'])</li>
 * </ul>
 */
class RabbitmqNodeTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/rabbitmq/rabbitmq-node.yaml";
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
     * Build the full input map covering all sample names in rabbitmq-node.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ
     * for rate computations.
     *
     * <pre>
     * # Scope labels (required by expSuffix for all samples):
     * #   cluster="my-rabbitmq-cluster", node="rabbit@node1"
     *
     * # --- Queue/Message metrics ---
     * rabbitmq_queue_messages_ready{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 500.0
     * rabbitmq_queue_messages_unacked{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 25.0
     *
     * # --- Rate metrics (incoming) ---
     * rabbitmq_global_messages_received_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 100000.0
     *
     * # --- Multi-sample subtraction (publisher_total = channels - channel_consumers) ---
     * rabbitmq_channels{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 50.0
     * rabbitmq_channel_consumers{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 30.0
     *
     * # --- Passthrough metrics ---
     * rabbitmq_connections{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 120.0
     * rabbitmq_queues{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 45.0
     * rabbitmq_consumers{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 30.0
     *
     * # --- Rate metrics (outgoing — 5 distinct sample names, all rate('PT1M')) ---
     * rabbitmq_global_messages_redelivered_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 500.0
     * rabbitmq_global_messages_delivered_consume_auto_ack_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 40000.0
     * rabbitmq_global_messages_delivered_consume_manual_ack_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 35000.0
     * rabbitmq_global_messages_delivered_get_auto_ack_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 5000.0
     * rabbitmq_global_messages_delivered_get_manual_ack_total{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 3000.0
     *
     * # --- Erlang VM allocators (tagEqual on 'usage': blocks_size and carriers_size) ---
     * # Also tagEqual on 'kind': mbcs, mbcs_pool for per-type metrics
     * # Additional aggregation label: alloc (allocator type)
     * erlang_vm_allocators{cluster="my-rabbitmq-cluster",node="rabbit@node1",usage="blocks_size",kind="mbcs",alloc="binary_alloc"} 2000000.0
     * erlang_vm_allocators{cluster="my-rabbitmq-cluster",node="rabbit@node1",usage="carriers_size",kind="mbcs",alloc="binary_alloc"} 4000000.0
     * erlang_vm_allocators{cluster="my-rabbitmq-cluster",node="rabbit@node1",usage="blocks_size",kind="mbcs_pool",alloc="binary_alloc"} 500000.0
     * erlang_vm_allocators{cluster="my-rabbitmq-cluster",node="rabbit@node1",usage="carriers_size",kind="mbcs_pool",alloc="binary_alloc"} 1000000.0
     *
     * # --- Process resident memory ---
     * rabbitmq_process_resident_memory_bytes{cluster="my-rabbitmq-cluster",node="rabbit@node1"} 536870912.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cluster", "my-rabbitmq-cluster",
            "node", "rabbit@node1");

        return ImmutableMap.<String, SampleFamily>builder()
            // Queue/Message metrics
            .put("rabbitmq_queue_messages_ready",
                single("rabbitmq_queue_messages_ready",
                    scope, 500.0 * scale, timestamp))
            .put("rabbitmq_queue_messages_unacked",
                single("rabbitmq_queue_messages_unacked",
                    scope, 25.0 * scale, timestamp))

            // Incoming messages (rate)
            .put("rabbitmq_global_messages_received_total",
                single("rabbitmq_global_messages_received_total",
                    scope, 100000.0 * scale, timestamp))

            // Channels and channel consumers (subtraction for publisher_total)
            .put("rabbitmq_channels",
                single("rabbitmq_channels",
                    scope, 50.0 * scale, timestamp))
            .put("rabbitmq_channel_consumers",
                single("rabbitmq_channel_consumers",
                    scope, 30.0 * scale, timestamp))

            // Passthrough metrics
            .put("rabbitmq_connections",
                single("rabbitmq_connections",
                    scope, 120.0 * scale, timestamp))
            .put("rabbitmq_queues",
                single("rabbitmq_queues",
                    scope, 45.0 * scale, timestamp))
            .put("rabbitmq_consumers",
                single("rabbitmq_consumers",
                    scope, 30.0 * scale, timestamp))

            // Outgoing messages — 5 distinct sample names, all rate('PT1M')
            .put("rabbitmq_global_messages_redelivered_total",
                single("rabbitmq_global_messages_redelivered_total",
                    scope, 500.0 * scale, timestamp))
            .put("rabbitmq_global_messages_delivered_consume_auto_ack_total",
                single("rabbitmq_global_messages_delivered_consume_auto_ack_total",
                    scope, 40000.0 * scale, timestamp))
            .put("rabbitmq_global_messages_delivered_consume_manual_ack_total",
                single("rabbitmq_global_messages_delivered_consume_manual_ack_total",
                    scope, 35000.0 * scale, timestamp))
            .put("rabbitmq_global_messages_delivered_get_auto_ack_total",
                single("rabbitmq_global_messages_delivered_get_auto_ack_total",
                    scope, 5000.0 * scale, timestamp))
            .put("rabbitmq_global_messages_delivered_get_manual_ack_total",
                single("rabbitmq_global_messages_delivered_get_manual_ack_total",
                    scope, 3000.0 * scale, timestamp))

            // Erlang VM allocators — multiple samples with usage/kind/alloc tags
            .put("erlang_vm_allocators",
                buildAllocatorSamples(scope, scale, timestamp))

            // Process resident memory
            .put("rabbitmq_process_resident_memory_bytes",
                single("rabbitmq_process_resident_memory_bytes",
                    scope, 536870912.0 * scale, timestamp))

            .build();
    }

    /**
     * Builds the erlang_vm_allocators SampleFamily with all required tag
     * combinations. The YAML uses:
     * <ul>
     *   <li>tagEqual('usage','blocks_size') / tagEqual('usage','carriers_size')
     *       for allocated_used/unused percent/bytes/total</li>
     *   <li>tagEqual('usage','carriers_size') with
     *       sum(['cluster','node','alloc']) for allocated_by_type</li>
     *   <li>tagEqual('usage','blocks_size','kind','mbcs') and
     *       tagEqual('usage','carriers_size','kind','mbcs') for multiblock
     *       used/unused</li>
     *   <li>tagEqual('usage','blocks_size','kind','mbcs_pool') and
     *       tagEqual('usage','carriers_size','kind','mbcs_pool') for
     *       multiblock pool used/unused</li>
     * </ul>
     */
    private static SampleFamily buildAllocatorSamples(
            final ImmutableMap<String, String> scope,
            final double scale,
            final long timestamp) {
        return SampleFamilyBuilder.newBuilder(
            // blocks_size + mbcs
            allocSample(scope, "blocks_size", "mbcs", "binary_alloc",
                2000000.0 * scale, timestamp),
            // carriers_size + mbcs
            allocSample(scope, "carriers_size", "mbcs", "binary_alloc",
                4000000.0 * scale, timestamp),
            // blocks_size + mbcs_pool
            allocSample(scope, "blocks_size", "mbcs_pool", "binary_alloc",
                500000.0 * scale, timestamp),
            // carriers_size + mbcs_pool
            allocSample(scope, "carriers_size", "mbcs_pool", "binary_alloc",
                1000000.0 * scale, timestamp)
        ).build();
    }

    private static Sample allocSample(final ImmutableMap<String, String> scope,
                                       final String usage,
                                       final String kind,
                                       final String alloc,
                                       final double value,
                                       final long timestamp) {
        return Sample.builder()
            .name("erlang_vm_allocators")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("usage", usage)
                .put("kind", kind)
                .put("alloc", alloc)
                .build())
            .value(value)
            .timestamp(timestamp)
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
