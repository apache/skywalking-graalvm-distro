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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparison test for the "network-profiling-slow-trace" rule shared across
 * envoy-als.yaml, mesh-dp.yaml, and k8s-service.yaml.
 *
 * <p>All three files contain identical DSL, so they compile to the same
 * pre-compiled class (same SHA-256 hash).
 *
 * <h3>DSL (simplified):</h3>
 * <pre>
 * filter {
 *   json{}
 *   extractor{
 *     if (tag("LOG_KIND") == "NET_PROFILING_SAMPLED_TRACE") {
 *       sampledTrace {
 *         latency parsed.latency as Long
 *         uri parsed.uri as String
 *         reason parsed.reason as String
 *
 *         // processId: 3-way if/else-if/else
 *         if (parsed.client_process.process_id as String != "") {
 *           processId parsed.client_process.process_id as String
 *         } else if (parsed.client_process.local as Boolean) {
 *           processId ProcessRegistry.generateVirtualLocalProcess(...)
 *         } else {
 *           processId ProcessRegistry.generateVirtualRemoteProcess(...)
 *         }
 *
 *         // destProcessId: same 3-way pattern
 *         // detectPoint: direct assignment
 *
 *         // componentId: 4-way if/else-if chain
 *         if (parsed.component as String == "http" &amp;&amp; parsed.ssl as Boolean) {
 *           componentId 129     // HTTPS
 *         } else if (parsed.component as String == "http") {
 *           componentId 49      // HTTP
 *         } else if (parsed.ssl as Boolean) {
 *           componentId 130     // TLS
 *         } else {
 *           componentId 110     // TCP
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Branch coverage:</h3>
 * <ul>
 *   <li>LOG_KIND guard: matching vs non-matching</li>
 *   <li>processId: non-empty process_id (first if-branch)</li>
 *   <li>componentId: all 4 combinations of component + ssl</li>
 * </ul>
 *
 * <p>All tests use non-empty process_id values to avoid ProcessRegistry
 * static method calls. The else-if/else branches for ProcessRegistry
 * require static mocking — deferred as lower priority.
 */
class LALNetworkProfilingTest extends LALScriptComparisonBase {

    private static String NETWORK_PROFILING_DSL;

    @BeforeAll
    static void loadRules() throws Exception {
        // Load from envoy-als.yaml (second rule)
        final List<LALConfig> rules = loadLALRules("envoy-als.yaml");
        for (final LALConfig rule : rules) {
            if ("network-profiling-slow-trace".equals(rule.getName())) {
                NETWORK_PROFILING_DSL = rule.getDsl();
                break;
            }
        }
    }

    // ── componentId branch coverage ──

    /**
     * component="http", ssl=false → componentId=49 (HTTP).
     */
    @Test
    void httpNoSsl_componentId49() {
        final LogData logData = buildProfilingLogData();
        final String json = buildProfilingJson("http", false);

        final LogData withJson = buildJsonLogData(
            logData.getService(), logData.getServiceInstance(), json,
            profilingTags());

        runAndCompare(NETWORK_PROFILING_DSL, withJson);
    }

    /**
     * component="http", ssl=true → componentId=129 (HTTPS).
     */
    @Test
    void httpWithSsl_componentId129() {
        final LogData logData = buildProfilingLogData();
        final String json = buildProfilingJson("http", true);

        final LogData withJson = buildJsonLogData(
            logData.getService(), logData.getServiceInstance(), json,
            profilingTags());

        runAndCompare(NETWORK_PROFILING_DSL, withJson);
    }

    /**
     * component="tcp", ssl=true → componentId=130 (TLS).
     */
    @Test
    void tcpWithSsl_componentId130() {
        final LogData logData = buildProfilingLogData();
        final String json = buildProfilingJson("tcp", true);

        final LogData withJson = buildJsonLogData(
            logData.getService(), logData.getServiceInstance(), json,
            profilingTags());

        runAndCompare(NETWORK_PROFILING_DSL, withJson);
    }

    /**
     * component="tcp", ssl=false → componentId=110 (TCP).
     */
    @Test
    void tcpNoSsl_componentId110() {
        final LogData logData = buildProfilingLogData();
        final String json = buildProfilingJson("tcp", false);

        final LogData withJson = buildJsonLogData(
            logData.getService(), logData.getServiceInstance(), json,
            profilingTags());

        runAndCompare(NETWORK_PROFILING_DSL, withJson);
    }

    // ── LOG_KIND guard ──

    /**
     * LOG_KIND != "NET_PROFILING_SAMPLED_TRACE" → sampledTrace block skipped.
     */
    @Test
    void nonMatchingTag_skipsSampledTrace() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "OTHER");

        final LogData logData = buildJsonLogData(
            "mesh-svc", "mesh-inst", "{}", tags);

        runAndCompare(NETWORK_PROFILING_DSL, logData);
    }

    // ── Manifest verification ──

    /**
     * Verify all 3 files resolve to the same pre-compiled class.
     */
    /**
     * Verify all 3 files have structurally identical DSL (may differ in trailing
     * whitespace due to YAML parsing), and each resolves to a pre-compiled class.
     */
    @Test
    void manifestContainsAllThreeRules() throws Exception {
        final List<LALConfig> envoyRules = loadLALRules("envoy-als.yaml");
        final List<LALConfig> meshDpRules = loadLALRules("mesh-dp.yaml");
        final List<LALConfig> k8sRules = loadLALRules("k8s-service.yaml");

        String envoyDsl = null;
        for (final LALConfig r : envoyRules) {
            if ("network-profiling-slow-trace".equals(r.getName())) {
                envoyDsl = r.getDsl();
            }
        }
        final String meshDpDsl = meshDpRules.get(0).getDsl();
        final String k8sDsl = k8sRules.get(0).getDsl();

        // DSL content is identical (may differ in trailing newline from YAML)
        assertEquals(envoyDsl.trim(), meshDpDsl.trim(),
            "envoy-als and mesh-dp should have identical DSL content");
        assertEquals(envoyDsl.trim(), k8sDsl.trim(),
            "envoy-als and k8s-service should have identical DSL content");

        // Each DSL hash should resolve in the manifest
        final Map<String, String> hashManifest = loadManifest();
        assertTrue(hashManifest.containsKey(sha256(envoyDsl)),
            "Hash manifest should contain the envoy-als network-profiling DSL hash");
        assertTrue(hashManifest.containsKey(sha256(meshDpDsl)),
            "Hash manifest should contain the mesh-dp network-profiling DSL hash");
        assertTrue(hashManifest.containsKey(sha256(k8sDsl)),
            "Hash manifest should contain the k8s-service network-profiling DSL hash");

        // Name manifest should contain the rule name
        final Map<String, String> nameManifest = loadNameManifest();
        assertTrue(nameManifest.containsKey("network-profiling-slow-trace"),
            "lal-scripts.txt should contain network-profiling-slow-trace");
    }

    // ── Helpers ──

    private static LogData buildProfilingLogData() {
        return LogData.newBuilder()
            .setService("mesh-svc")
            .setServiceInstance("mesh-inst")
            .setTimestamp(System.currentTimeMillis())
            .build();
    }

    private static Map<String, String> profilingTags() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "NET_PROFILING_SAMPLED_TRACE");
        return tags;
    }

    /**
     * Build JSON body for network profiling with non-empty process_id values
     * (avoids ProcessRegistry static calls).
     *
     * <pre>
     * {
     *   "latency": 250,
     *   "uri": "/api/checkout",
     *   "reason": "slow",
     *   "client_process": {"process_id": "proc-client-1", "local": false, "address": "1.2.3.4"},
     *   "server_process": {"process_id": "proc-server-1", "local": false, "address": "5.6.7.8"},
     *   "detect_point": "server",
     *   "component": "{component}",
     *   "ssl": {ssl},
     *   "service": "mesh-svc",
     *   "serviceInstance": "mesh-inst"
     * }
     * </pre>
     */
    private static String buildProfilingJson(final String component,
                                             final boolean ssl) {
        return "{"
            + "\"latency\":250,"
            + "\"uri\":\"/api/checkout\","
            + "\"reason\":\"slow\","
            + "\"client_process\":{\"process_id\":\"proc-client-1\","
            + "\"local\":false,\"address\":\"1.2.3.4\"},"
            + "\"server_process\":{\"process_id\":\"proc-server-1\","
            + "\"local\":false,\"address\":\"5.6.7.8\"},"
            + "\"detect_point\":\"server\","
            + "\"component\":\"" + component + "\","
            + "\"ssl\":" + ssl + ","
            + "\"service\":\"mesh-svc\","
            + "\"serviceInstance\":\"mesh-inst\""
            + "}";
    }
}
