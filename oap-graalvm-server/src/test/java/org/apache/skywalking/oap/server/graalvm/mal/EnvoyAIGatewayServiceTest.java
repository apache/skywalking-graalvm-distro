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

class EnvoyAIGatewayServiceTest extends MALScriptComparisonBase {

    private static final String YAML_PATH =
        "otel-rules/envoy-ai-gateway/gateway-service.yaml";
    private static final long TS1 =
        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final long TS2 =
        Instant.parse("2024-01-01T00:02:00Z").toEpochMilli();

    @TestFactory
    Stream<DynamicTest> allMetrics() {
        return generateComparisonTests(YAML_PATH,
            buildInput(100.0, TS1), buildInput(200.0, TS2));
    }

    private static ImmutableMap<String, SampleFamily> buildInput(
            final double base, final long timestamp) {
        double scale = base / 100.0;
        ImmutableMap<String, String> scope = ImmutableMap.of(
            "service_name", "e2e-ai-gateway",
            "job_name", "envoy-ai-gateway");

        ImmutableMap<String, String> providerScope = ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put("gen_ai_provider_name", "openai")
            .build();

        ImmutableMap<String, String> modelScope = ImmutableMap.<String, String>builder()
            .putAll(scope)
            .put("gen_ai_response_model", "gpt-4o-mini")
            .build();

        return ImmutableMap.<String, SampleFamily>builder()
            .put("gen_ai_server_request_duration_count",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_request_duration_count", scope, 50.0 * scale, timestamp),
                    sample("gen_ai_server_request_duration_count", providerScope, 50.0 * scale, timestamp),
                    sample("gen_ai_server_request_duration_count", modelScope, 50.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_request_duration_sum",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_request_duration_sum", scope, 25.0 * scale, timestamp),
                    sample("gen_ai_server_request_duration_sum", providerScope, 25.0 * scale, timestamp),
                    sample("gen_ai_server_request_duration_sum", modelScope, 25.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_request_duration",
                SampleFamilyBuilder.newBuilder(
                    histSample("gen_ai_server_request_duration", scope, "0.1", 10.0 * scale, timestamp),
                    histSample("gen_ai_server_request_duration", scope, "0.5", 30.0 * scale, timestamp),
                    histSample("gen_ai_server_request_duration", scope, "1.0", 45.0 * scale, timestamp),
                    histSample("gen_ai_server_request_duration", scope, "10.0", 50.0 * scale, timestamp)
                ).build())
            .put("gen_ai_client_token_usage_sum",
                SampleFamilyBuilder.newBuilder(
                    tokenSample(scope, "input", 5000.0 * scale, timestamp),
                    tokenSample(scope, "output", 3000.0 * scale, timestamp),
                    tokenSample(providerScope, "input", 5000.0 * scale, timestamp),
                    tokenSample(providerScope, "output", 3000.0 * scale, timestamp),
                    tokenSample(modelScope, "input", 5000.0 * scale, timestamp),
                    tokenSample(modelScope, "output", 3000.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_to_first_token_count",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_time_to_first_token_count", scope, 40.0 * scale, timestamp),
                    sample("gen_ai_server_time_to_first_token_count", modelScope, 40.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_to_first_token_sum",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_time_to_first_token_sum", scope, 8.0 * scale, timestamp),
                    sample("gen_ai_server_time_to_first_token_sum", modelScope, 8.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_to_first_token",
                SampleFamilyBuilder.newBuilder(
                    histSample("gen_ai_server_time_to_first_token", scope, "0.1", 10.0 * scale, timestamp),
                    histSample("gen_ai_server_time_to_first_token", scope, "0.5", 30.0 * scale, timestamp),
                    histSample("gen_ai_server_time_to_first_token", scope, "10.0", 40.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_per_output_token_count",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_time_per_output_token_count", scope, 40.0 * scale, timestamp),
                    sample("gen_ai_server_time_per_output_token_count", modelScope, 40.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_per_output_token_sum",
                SampleFamilyBuilder.newBuilder(
                    sample("gen_ai_server_time_per_output_token_sum", scope, 2.0 * scale, timestamp),
                    sample("gen_ai_server_time_per_output_token_sum", modelScope, 2.0 * scale, timestamp)
                ).build())
            .put("gen_ai_server_time_per_output_token",
                SampleFamilyBuilder.newBuilder(
                    histSample("gen_ai_server_time_per_output_token", scope, "0.01", 10.0 * scale, timestamp),
                    histSample("gen_ai_server_time_per_output_token", scope, "0.05", 30.0 * scale, timestamp),
                    histSample("gen_ai_server_time_per_output_token", scope, "1.0", 40.0 * scale, timestamp)
                ).build())
            .build();
    }

    private static Sample sample(final String name,
                                  final ImmutableMap<String, String> labels,
                                  final double value, final long timestamp) {
        return Sample.builder()
            .name(name).labels(labels).value(value).timestamp(timestamp).build();
    }

    private static Sample histSample(final String name,
                                      final ImmutableMap<String, String> labels,
                                      final String le, final double value,
                                      final long timestamp) {
        return Sample.builder()
            .name(name)
            .labels(ImmutableMap.<String, String>builder()
                .putAll(labels).put("le", le).build())
            .value(value).timestamp(timestamp).build();
    }

    private static Sample tokenSample(final ImmutableMap<String, String> labels,
                                       final String tokenType, final double value,
                                       final long timestamp) {
        return Sample.builder()
            .name("gen_ai_client_token_usage_sum")
            .labels(ImmutableMap.<String, String>builder()
                .putAll(labels).put("gen_ai_token_type", tokenType).build())
            .value(value).timestamp(timestamp).build();
    }
}
