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
 * Comparison test for otel-rules/aws-dynamodb/dynamodb-service.yaml.
 *
 * This file exercises:
 * <ul>
 *   <li>expPrefix with tag closure — builds {@code host_name} from
 *       {@code cloud_account_id}: {@code tag({tags -> tags.host_name =
 *       'aws-dynamodb::' + tags.cloud_account_id})}. Input provides
 *       {@code cloud_account_id} only; the script creates {@code host_name}.</li>
 *   <li>tagMatch('Operation','GetItem|BatchGetItem') — regex matching for
 *       SuccessfulRequestLatency, ThrottledRequests, SystemErrors</li>
 *   <li>tagEqual('Operation','Query') — exact match for
 *       SuccessfulRequestLatency, ReturnedItemCount</li>
 *   <li>tagEqual('Operation','Scan') — exact match for
 *       SuccessfulRequestLatency, ReturnedItemCount</li>
 *   <li>tagMatch with multiple write operations — PutItem|UpdateItem|DeleteItem|
 *       BatchWriteItem for ThrottledRequests</li>
 *   <li>tagMatch with transact operations — TransactGetItems/TransactWriteItems
 *       for SystemErrors</li>
 *   <li>Passthrough account-level metrics — AccountMaxWrites, AccountMaxReads,
 *       provisioned capacity utilization</li>
 *   <li>Scope: service(['host_name'], Layer.AWS_DYNAMODB)</li>
 * </ul>
 */
class DynamodbServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/aws-dynamodb/dynamodb-service.yaml";
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
     * Build the full input map covering all sample names in dynamodb-service.yaml.
     * Values are scaled relative to {@code base} so input1 and input2 differ.
     *
     * <p>Note: {@code cloud_account_id} is the source label for the expPrefix
     * tag closure. The script builds {@code host_name = 'aws-dynamodb::' +
     * cloud_account_id}. Do NOT include {@code host_name} in input.</p>
     *
     * <pre>
     * # Source label (used by expPrefix to build host_name):
     * #   cloud_account_id="123456789012"
     *
     * # --- Account-level passthrough metrics ---
     * amazonaws_com_AWS_DynamoDB_AccountMaxWrites{cloud_account_id="123456789012"} 40000.0
     * amazonaws_com_AWS_DynamoDB_AccountMaxReads{cloud_account_id="123456789012"} 40000.0
     * amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelWrites{cloud_account_id="123456789012"} 40000.0
     * amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelReads{cloud_account_id="123456789012"} 40000.0
     * amazonaws_com_AWS_DynamoDB_MaxProvisionedTableWriteCapacityUtilization{cloud_account_id="123456789012"} 75.0
     * amazonaws_com_AWS_DynamoDB_MaxProvisionedTableReadCapacityUtilization{cloud_account_id="123456789012"} 60.0
     * amazonaws_com_AWS_DynamoDB_AccountProvisionedReadCapacityUtilization{cloud_account_id="123456789012"} 55.0
     * amazonaws_com_AWS_DynamoDB_AccountProvisionedWriteCapacityUtilization{cloud_account_id="123456789012"} 45.0
     *
     * # --- Table-level passthrough metrics ---
     * amazonaws_com_AWS_DynamoDB_ConsumedWriteCapacityUnits{cloud_account_id="123456789012"} 500.0
     * amazonaws_com_AWS_DynamoDB_ConsumedReadCapacityUnits{cloud_account_id="123456789012"} 1200.0
     * amazonaws_com_AWS_DynamoDB_ProvisionedReadCapacityUnits{cloud_account_id="123456789012"} 2000.0
     * amazonaws_com_AWS_DynamoDB_ProvisionedWriteCapacityUnits{cloud_account_id="123456789012"} 1000.0
     *
     * # --- SuccessfulRequestLatency (multiple Operation values) ---
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="GetItem"} 5.2
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="BatchGetItem"} 12.5
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="PutItem"} 6.8
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="BatchWriteItem"} 15.3
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="Query"} 8.1
     * amazonaws_com_AWS_DynamoDB_SuccessfulRequestLatency{cloud_account_id="123456789012",Operation="Scan"} 25.0
     *
     * # --- ReturnedItemCount ---
     * amazonaws_com_AWS_DynamoDB_ReturnedItemCount{cloud_account_id="123456789012",Operation="Scan"} 150.0
     * amazonaws_com_AWS_DynamoDB_ReturnedItemCount{cloud_account_id="123456789012",Operation="Query"} 80.0
     *
     * # --- TimeToLiveDeletedItemCount ---
     * amazonaws_com_AWS_DynamoDB_TimeToLiveDeletedItemCount{cloud_account_id="123456789012"} 30.0
     *
     * # --- ThrottledRequests (read + write operations) ---
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="GetItem"} 3.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="Scan"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="Query"} 2.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="BatchGetItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="PutItem"} 4.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="UpdateItem"} 2.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="DeleteItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_ThrottledRequests{cloud_account_id="123456789012",Operation="BatchWriteItem"} 3.0
     *
     * # --- Throttle events ---
     * amazonaws_com_AWS_DynamoDB_ReadThrottleEvents{cloud_account_id="123456789012"} 7.0
     * amazonaws_com_AWS_DynamoDB_WriteThrottleEvents{cloud_account_id="123456789012"} 10.0
     *
     * # --- SystemErrors (read + write + transact operations) ---
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="GetItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="Scan"} 0.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="Query"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="BatchGetItem"} 0.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="TransactGetItems"} 2.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="PutItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="UpdateItem"} 0.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="DeleteItem"} 1.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="BatchWriteItem"} 0.0
     * amazonaws_com_AWS_DynamoDB_SystemErrors{cloud_account_id="123456789012",Operation="TransactWriteItems"} 1.0
     *
     * # --- UserErrors ---
     * amazonaws_com_AWS_DynamoDB_UserErrors{cloud_account_id="123456789012"} 5.0
     *
     * # --- ConditionalCheckFailedRequests ---
     * amazonaws_com_AWS_DynamoDB_ConditionalCheckFailedRequests{cloud_account_id="123456789012"} 8.0
     *
     * # --- TransactionConflict ---
     * amazonaws_com_AWS_DynamoDB_TransactionConflict{cloud_account_id="123456789012"} 3.0
     * </pre>
     */
    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;

        // Source label for expPrefix tag closure — NOT host_name
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "cloud_account_id", "123456789012");

        return ImmutableMap.<String, SampleFamily>builder()
            // Account-level passthrough metrics
            .put("amazonaws_com_AWS_DynamoDB_AccountMaxWrites",
                single("amazonaws_com_AWS_DynamoDB_AccountMaxWrites",
                    scope, 40000.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_AccountMaxReads",
                single("amazonaws_com_AWS_DynamoDB_AccountMaxReads",
                    scope, 40000.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelWrites",
                single("amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelWrites",
                    scope, 40000.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelReads",
                single("amazonaws_com_AWS_DynamoDB_AccountMaxTableLevelReads",
                    scope, 40000.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_MaxProvisionedTableWriteCapacityUtilization",
                single("amazonaws_com_AWS_DynamoDB_MaxProvisionedTableWriteCapacityUtilization",
                    scope, 75.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_MaxProvisionedTableReadCapacityUtilization",
                single("amazonaws_com_AWS_DynamoDB_MaxProvisionedTableReadCapacityUtilization",
                    scope, 60.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_AccountProvisionedReadCapacityUtilization",
                single("amazonaws_com_AWS_DynamoDB_AccountProvisionedReadCapacityUtilization",
                    scope, 55.0 * scale, timestamp))
            .put("amazonaws_com_AWS_DynamoDB_AccountProvisionedWriteCapacityUtilization",
                single("amazonaws_com_AWS_DynamoDB_AccountProvisionedWriteCapacityUtilization",
                    scope, 45.0 * scale, timestamp))

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

            // UserErrors — passthrough
            .put("amazonaws_com_AWS_DynamoDB_UserErrors",
                single("amazonaws_com_AWS_DynamoDB_UserErrors",
                    scope, 5.0 * scale, timestamp))

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
