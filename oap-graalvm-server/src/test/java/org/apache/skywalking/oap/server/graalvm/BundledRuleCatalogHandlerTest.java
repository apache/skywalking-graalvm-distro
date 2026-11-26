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

package org.apache.skywalking.oap.server.graalvm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.skywalking.oap.server.graalvm.admin.BootRuleLoads;
import org.apache.skywalking.oap.server.graalvm.admin.BundledRuleCatalogHandler;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only rule catalog must expose every bundled rule a boot that loads every module loaded, in the payload
 * shapes upstream's runtime-rule read routes use (Horizon and swctl parse them).
 */
class BundledRuleCatalogHandlerTest {
    private final BundledRuleCatalogHandler handler = new BundledRuleCatalogHandler();

    @BeforeAll
    static void bootWithEveryModule() throws Exception {
        BootRuleLoads.bootWith(Set.of(RuleCatalog.values()));
    }

    @Test
    void listHoldsEveryBundledRuleAsBundled() {
        AggregatedHttpResponse res = handler.list("").aggregate().join();
        assertEquals(200, res.status().code());
        JsonObject env = JsonParser.parseString(res.contentUtf8()).getAsJsonObject();
        assertTrue(env.get("generatedAt").getAsLong() > 0);
        assertEquals(0, env.getAsJsonObject("loaderStats").get("active").getAsInt());
        List<String> listed = new ArrayList<>();
        for (JsonElement e : env.getAsJsonArray("rules")) {
            JsonObject row = e.getAsJsonObject();
            assertEquals("BUNDLED", row.get("status").getAsString());
            assertEquals("RUNNING", row.get("localState").getAsString());
            assertEquals(row.get("contentHash").getAsString(), row.get("bundledContentHash").getAsString());
            listed.add(row.get("catalog").getAsString() + ":" + row.get("name").getAsString());
        }
        assertTrue(listed.contains("otel-rules:vm") && listed.contains("lal:default"), listed.toString());
        // Bundled, as its loader reads and records it, but upstream's runtime-rule module claims no file without a
        // named metric rule
        assertTrue(names("log-mal-rules").contains("placeholder"));
        assertFalse(listed.contains("log-mal-rules:placeholder"));
        JsonObject lal = JsonParser.parseString(handler.list("lal").aggregate().join().contentUtf8())
                                   .getAsJsonObject();
        assertEquals(listed.stream().filter(rule -> rule.startsWith("lal:")).count(),
            lal.getAsJsonArray("rules").size());

        assertEquals(400, handler.list("no-such-catalog").aggregate().join().status().code());
    }

    @Test
    void statusFindsNoApply() {
        AggregatedHttpResponse res = handler.applyStatus().aggregate().join();
        assertEquals(200, res.status().code());
        JsonObject status = JsonParser.parseString(res.contentUtf8()).getAsJsonObject();
        assertFalse(status.get("found").getAsBoolean());
        assertEquals("UNKNOWN", status.get("phase").getAsString());
    }

    @Test
    void bundledListsEveryShippedRuleFile() {
        assertTrue(names("otel-rules").contains("vm"));
        assertTrue(names("otel-rules").contains("banyandb/banyandb-instance"));
        assertTrue(names("otel-rules").contains("airflow/airflow-service"));
        assertTrue(names("meter-analyzer-config").contains("java-agent"));
        assertTrue(names("meter-analyzer-config").contains("nodejs-runtime"));
        assertTrue(names("log-mal-rules").contains("nginx"));
        assertTrue(names("telegraf-rules").contains("vm"));
        assertTrue(names("lal").contains("default"));
        assertTrue(names("lal").contains("envoy-als"));

        JsonArray rows = JsonParser.parseString(
            handler.listBundled("lal", "true").aggregate().join().contentUtf8()).getAsJsonArray();
        for (JsonElement e : rows) {
            JsonObject row = e.getAsJsonObject();
            assertEquals("bundled", row.get("kind").getAsString());
            assertFalse(row.get("overridden").getAsBoolean());
            assertEquals(64, row.get("contentHash").getAsString().length());
            assertTrue(row.get("content").getAsString().contains("rules:"), row.get("name").getAsString());
        }
        JsonArray bare = JsonParser.parseString(
            handler.listBundled("lal", "false").aggregate().join().contentUtf8()).getAsJsonArray();
        assertFalse(bare.get(0).getAsJsonObject().has("content"));

        // No rule engine claims oal or tal, so upstream refuses them here
        assertEquals("invalid_catalog", applyStatus(handler.listBundled("oal", "true")));
        assertEquals("invalid_catalog", applyStatus(handler.listBundled("tal", "true")));
    }

    @Test
    void getServesYamlWithUpstreamHeadersAndEtag() {
        AggregatedHttpResponse yaml = handler.get("lal", "default", "", "", "").aggregate().join();
        assertEquals(200, yaml.status().code());
        assertEquals("BUNDLED", yaml.headers().get("X-Sw-Status"));
        assertEquals("bundled", yaml.headers().get("X-Sw-Source"));
        String hash = yaml.headers().get("X-Sw-Content-Hash");
        assertNotNull(hash);
        assertEquals("\"" + hash + "\"", yaml.headers().get("ETag"));
        assertTrue(yaml.contentUtf8().contains("rules:"));

        assertEquals(304, handler.get("lal", "default", "bundled", "", "\"" + hash + "\"")
            .aggregate().join().status().code());

        AggregatedHttpResponse json = handler.get("otel-rules", "banyandb/banyandb-service", "",
            "application/json", "").aggregate().join();
        JsonObject env = JsonParser.parseString(json.contentUtf8()).getAsJsonObject();
        assertEquals("banyandb/banyandb-service", env.get("name").getAsString());
        assertEquals("BUNDLED", env.get("status").getAsString());
        assertTrue(env.get("content").getAsString().contains("metricsRules"));

        AggregatedHttpResponse missing = handler.get("lal", "no-such-rule", "", "", "").aggregate().join();
        assertEquals(404, missing.status().code());
        assertEquals("not_found",
            JsonParser.parseString(missing.contentUtf8()).getAsJsonObject().get("applyStatus").getAsString());
        assertEquals(400, handler.get("lal", "default", "weird", "", "").aggregate().join().status().code());
        assertEquals("missing_name", applyStatus(handler.get("lal", "", "", "", "")));
        assertEquals("invalid_name", applyStatus(handler.get("lal", "../default", "", "", "")));
        assertEquals("invalid_catalog", applyStatus(handler.get("oal", "core", "", "", "")));
    }

    private static String applyStatus(final HttpResponse response) {
        AggregatedHttpResponse res = response.aggregate().join();
        assertEquals(400, res.status().code());
        return JsonParser.parseString(res.contentUtf8()).getAsJsonObject().get("applyStatus").getAsString();
    }

    private List<String> names(final String catalog) {
        List<String> names = new ArrayList<>();
        JsonArray rows = JsonParser.parseString(
            handler.listBundled(catalog, "false").aggregate().join().contentUtf8()).getAsJsonArray();
        for (JsonElement e : rows) {
            names.add(e.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }
}
