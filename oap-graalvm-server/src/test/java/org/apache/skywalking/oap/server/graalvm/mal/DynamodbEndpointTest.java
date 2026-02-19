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
 * Comparison test for otel-rules/aws-dynamodb/dynamodb-endpoint.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>expPrefix with tag closure — builds {@code host_name} from
 *       {@code cloud_account_id}: {@code tag({tags -> tags.host_name =
 *       'aws-dynamodb::' + tags.cloud_account_id})}. Input provides
 *       {@code cloud_account_id} only; the script creates {@code host_name}.</li>
 *   <li>Endpoint scope with TableName — {@code .endpoint(['host_name'],
 *       ['TableName'], Layer.AWS_DYNAMODB)}. Input must include
 *       {@code TableName} as an original metric label.</li>
 *   <li>tagMatch('Operation','GetItem|BatchGetItem') — regex matching for
 *       SuccessfulRequestLatency, ThrottledRequests, SystemErrors</li>
 *   <li>tagEqual('Operation','Query') — exact match for
 *       SuccessfulRequestLatency, ReturnedItemCount</li>
 *   <li>tagEqual('Operation','Scan') — exact match for
 *       SuccessfulRequestLatency, ReturnedItemCount</li>
 *   <li>tagMatch with multiple write operations — PutItem|UpdateItem|
 *       DeleteItem|BatchWriteItem for ThrottledRequests</li>
 *   <li>tagMatch with transact operations — TransactGetItems/TransactWriteItems
 *       for SystemErrors</li>
 *   <li>Passthrough table-level metrics — ConsumedWriteCapacityUnits,
 *       ProvisionedReadCapacityUnits, etc.</li>
 *   <li>Scope: service(['host_name'], Layer.AWS_DYNAMODB).endpoint(
 *       ['host_name'], ['TableName'], Layer.AWS_DYNAMODB)</li>
 * </ul>
 */
class DynamodbEndpointTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/aws-dynamodb/dynamodb-endpoint.yaml";
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
     * dynamodb-endpoint.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ.
     *
     * <p>Note: {@code cloud_account_id} is the source label for the expPrefix
     * tag closure. The script builds {@code host_name = 'aws-dynamodb::' +
     * cloud_account_id}. Do NOT include {@code host_name} in input.
     * {@code TableName} is an original metric label consumed by the endpoint
     * scope function.</p>
     *
     * <pre>
     * # Source labels:
     * #   cloud_account_id="123456789012"  (used by expPrefix to build host_name)
     * #   TableName="UserSessions"         (used by endpoint scope)
     *
     * # --- Table-level passthrough metrics ---
     * amazonaws_com_AWS_DynamoDB_ConsumedWriteCapacityUnits{cloud_account_id="123456789012",TableName="UserSessions"} 500.0
     * amazonaws_com_AWS_DynamoDB_ConsumedReadCapacityUnits{cloud_account_id="123456789012",TableName="UserSessions"} 1200.0
     * amazonaws_com_AWS_DynamoDB_ProvisionedReadCapacityUnits{cloud_account_id="123456789012",TableName="UserSessions"} 2000.0
     * amazonaws_com_AWS_DynamoDB_ProvisionedWriteCapacityUnits{cloud_account_id="123456789012",TableName="UserSessions"} 1000.0
     *
     * # --- SuccessfulRequestLatency (multiple Operation values) ---
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="GetItem"} 5.2
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchGetItem"} 12.5
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="PutItem"} 6.8
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchWriteItem"} 15.3
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="Query"} 8.1
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",TableName="UserSessions",Operation="Scan"} 25.0
     *
     * # --- ReturnedItemCount ---
     * amazonaws_com_AWS_DynamoDB_ReturnedItemCount{cloud_account_id="123456789012",TableName="UserSessions",Operation="Scan"} 150.0
     * amazonaws_com_AWS_DynamoDB_ReturnedItemCount{cloud_account_id="123456789012",TableName="UserSessions",Operation="Query"} 80.0
     *
     * # --- TimeToLiveDeletedItemCount ---
     * amazonaws_com_AWS_DynamoDB_TimeToLiveDeletedItemCount{cloud_account_id="123456789012",TableName="UserSessions"} 30.0
     *
     * # --- ThrottledRequests (read + write operations) ---
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="GetItem"} 3.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="Scan"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="Query"} 2.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchGetItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="PutItem"} 4.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="UpdateItem"} 2.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="DeleteItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchWriteItem"} 3.0
     *
     * # --- Throttle events ---
     * amazonaws_com_AWS_DynamoDB_ReadThrottleEvents{cloud_account_id="123456789012",TableName="UserSessions"} 7.0
     * amazonaws_com_AWS_DynamoDB_WriteThrottleEvents{cloud_account_id="123456789012",TableName="UserSessions"} 10.0
     *
     * # --- SystemErrors (read + write + transact operations) ---
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="GetItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="Scan"} 0.5
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="Query"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchGetItem"} 0.5
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="TransactGetItems"} 2.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="PutItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="UpdateItem"} 0.5
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="DeleteItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="BatchWriteItem"} 0.5
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",TableName="UserSessions",Operation="TransactWriteItems"} 1.0
     *
     * # --- ConditionalCheckFailedRequests ---
     * amazonaws_com_AWS_DynamoDB_ConditionalCheckFailedRequests{cloud_account_id="123456789012",TableName="UserSessions"} 8.0
     *
     * # --- TransactionConflict ---
     * amazonaws_com_AWS_DynamoDB_TransactionConflict{cloud_account_id="123456789012",TableName="UserSessions"} 3.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        // Source labels: cloud_account_id for expPrefix, TableName for endpoint
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cloud_account_id", "123456789012",
            "TableName", "UserSessions");

        return ImmutableMap.<String, SampleFamily>builder()
            // Table-level passthrough metrics
            .put("amazonaws_com_AWS_DynamoDB_ConsumedWriteCapacityUnits",
                single("amazonaws_com_AWS_DynamoDB_ConsumedWriteCapacityUnits",
                    scope, 500.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_ConsumedReadCapacityUnits",
                single("amazonaws_com_AWS_DynamoDB_ConsumedReadCapacityUnits",
                    scope, 1200.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_ProvisionedReadCapacityUnits",
                single("amazonaws_com_AWS_DynamoDB_ProvisionedReadCapacityUnits",
                    scope, 2000.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_ProvisionedWriteCapacityUnits",
                single("amazonaws_com_AWS_DynamoDB_ProvisionedWriteCapacityUnits",
                    scope, 1000.0 * scale, timestamp))

            // SuccessfulRequestLatency — multiple Operation values
            .put("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                SampleFamilyBuilder.newBuilder(
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "GetItem", 5.2 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "BatchGetItem", 12.5 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "PutItem", 6.8 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "BatchWriteItem", 15.3 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "Query", 8.1 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency",
                        "Scan", 25.0 * scale, scope, timestamp)
                ).build())

            // ReturnedItemCount — Scan and Query
            .put("amazonaws_com_AWS_DynamoDB_ReturnedItemCount",
                SampleFamilyBuilder.newBuilder(
                    opSample("amazonaws_com_AWS_DynamoDB_ReturnedItemCount",
                        "Scan", 150.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ReturnedItemCount",
                        "Query", 80.0 * scale, scope, timestamp)
                ).build())

            // TimeToLiveDeletedItemCount — passthrough
            .put("amazonaws_com_AWS_DynamoDB_TimeToLiveDeletedItemCount",
                single("amazonaws_com_AWS_DynamoDB_TimeToLiveDeletedItemCount",
                    scope, 30.0 * scale, timestamp))

            // ThrottledRequests — read + write operations
            .put("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                SampleFamilyBuilder.newBuilder(
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "GetItem", 3.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "Scan", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "Query", 2.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "BatchGetItem", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "PutItem", 4.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "UpdateItem", 2.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "DeleteItem", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_ThrottledRequests",
                        "BatchWriteItem", 3.0 * scale, scope, timestamp)
                ).build())

            // Throttle events — passthrough
            .put("amazonaws_com_AWS_DynamoDB_ReadThrottleEvents",
                single("amazonaws_com_AWS_DynamoDB_ReadThrottleEvents",
                    scope, 7.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_WriteThrottleEvents",
                single("amazonaws_com_AWS_DynamoDB_WriteThrottleEvents",
                    scope, 10.0 * scale, timestamp))

            // SystemErrors — read + write + transact operations
            .put("amazonaws_com_AWS_DynamoDB_SystemErrors",
                SampleFamilyBuilder.newBuilder(
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "GetItem", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "Scan", 0.5 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "Query", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "BatchGetItem", 0.5 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "TransactGetItems", 2.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "PutItem", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "UpdateItem", 0.5 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "DeleteItem", 1.0 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "BatchWriteItem", 0.5 * scale, scope, timestamp),
                    opSample("amazonaws_com_AWS_DynamoDB_SystemErrors",
                        "TransactWriteItems", 1.0 * scale, scope, timestamp)
                ).build())

            // ConditionalCheckFailedRequests — passthrough
            .put("amazonaws_com_AWS_DynamoDB_ConditionalCheckFailedRequests",
                single("amazonaws_com_AWS_DynamoDB_ConditionalCheckFailedRequests",
                    scope, 8.0 * scale, timestamp))

            // TransactionConflict — passthrough
            .put("amazonaws_com_AWS_DynamoDB_TransactionConflict",
                single("amazonaws_com_AWS_DynamoDB_TransactionConflict",
                    scope, 3.0 * scale, timestamp))

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

    private static Sample opSample(final String metricName,
                                    final String operation,
                                    final double value,
                                    final ImmutableMap<String, String> scope,
                                    final long timestamp) {
        return Sample.builder()
            .name(metricName)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(scope)
                .put("Operation", operation)
                .build())
            .value(value)
            .timestamp(timestamp)
            .build();
    }
}
