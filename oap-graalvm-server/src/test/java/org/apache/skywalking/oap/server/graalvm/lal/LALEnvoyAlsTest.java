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

package org.apache.skywalking.oap.server.graalvm.lal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Comparison test for the "envoy-als" rule in lal/envoy-als.yaml.
 *
 * <p>This rule uses conditional abort, parsed?.navigation via nested Maps,
 * tag extraction, and rateLimit sampler with if/else branching.
 *
 * <h3>DSL:</h3>
 * <pre>
 * filter {
 *   // abort if responseCode &lt; 400 and no responseFlags
 *   if (parsed?.response?.responseCode?.value as Integer &lt; 400
 *       &amp;&amp; !parsed?.commonProperties?.responseFlags?.toString()?.trim()) {
 *     abort {}
 *   }
 *   extractor {
 *     if (parsed?.response?.responseCode) {
 *       tag 'status.code': parsed?.response?.responseCode?.value
 *     }
 *     tag 'response.flag': parsed?.commonProperties?.responseFlags
 *   }
 *   sink {
 *     sampler {
 *       if (parsed?.commonProperties?.responseFlags?.toString()) {
 *         rateLimit("${log.service}:${parsed?.commonProperties?.responseFlags?.toString()}") { rpm 6000 }
 *       } else {
 *         rateLimit("${log.service}:${parsed?.response?.responseCode}") { rpm 6000 }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Branch coverage:</h3>
 * <ul>
 *   <li>responseCode &lt; 400 + no flags → abort</li>
 *   <li>responseCode &gt;= 400 + no flags → tags extracted, sampler with responseCode</li>
 *   <li>responseCode &lt; 400 + flags present → tags extracted, sampler with flags</li>
 * </ul>
 *
 * <p>Input: Instead of Envoy protobuf extraLog, we set parsed Map directly via
 * {@code binding.parsed(nestedMap)}. Groovy's {@code map.property} syntax enables
 * {@code parsed?.response?.responseCode?.value} to traverse nested Maps.
 */
class LALEnvoyAlsTest extends LALScriptComparisonBase {

    private static String ENVOY_ALS_DSL;

    @BeforeAll
    static void loadRules() throws Exception {
        final List<LALConfig> rules = loadLALRules("envoy-als.yaml");
        for (final LALConfig rule : rules) {
            if ("envoy-als".equals(rule.getName())) {
                ENVOY_ALS_DSL = rule.getDsl();
            }
        }
    }

    /**
     * responseCode=200 (< 400), no responseFlags → abort fires.
     */
    @Test
    void lowResponseCodeNoFlags_aborts() {
        final Map<String, Object> parsedMap = new HashMap<>();
        parsedMap.put("response", buildMap("responseCode", buildMap("value", 200)));
        parsedMap.put("commonProperties", buildMap("responseFlags", ""));

        final LogData logData = buildTextLogData(
            "checkout-svc", "checkout-inst", "", null);

        runAndCompareWithParsedMap(ENVOY_ALS_DSL, logData, parsedMap);
    }

    /**
     * responseCode=500 (>= 400), no responseFlags → no abort,
     * tags extracted ('status.code': 500), sampler uses responseCode.
     */
    @Test
    void highResponseCodeNoFlags_extractsTagsWithResponseCode() {
        final Map<String, Object> parsedMap = new HashMap<>();
        parsedMap.put("response", buildMap("responseCode", buildMap("value", 500)));
        parsedMap.put("commonProperties", buildMap("responseFlags", ""));

        final LogData logData = buildTextLogData(
            "checkout-svc", "checkout-inst", "", null);

        runAndCompareWithParsedMap(ENVOY_ALS_DSL, logData, parsedMap);
    }

    /**
     * responseCode=200 (< 400), responseFlags present → no abort (flags make
     * the !trim() check fail), tags extracted, sampler uses flags.
     */
    @Test
    void lowResponseCodeWithFlags_extractsTagsWithFlags() {
        final Map<String, Object> parsedMap = new HashMap<>();
        parsedMap.put("response", buildMap("responseCode", buildMap("value", 200)));
        parsedMap.put("commonProperties",
            buildMap("responseFlags", "{upstreamConnectionFailure}"));

        final LogData logData = buildTextLogData(
            "checkout-svc", "checkout-inst", "", null);

        runAndCompareWithParsedMap(ENVOY_ALS_DSL, logData, parsedMap);
    }

    private static Map<String, Object> buildMap(final String key, final Object value) {
        final Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
