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
 * Comparison test for mysql-slowsql.yaml, pgsql-slowsql.yaml, and redis-slowsql.yaml.
 *
 * <p>All three files have identical DSL, so they compile to the same pre-compiled
 * class (same SHA-256 hash). Tests run once against the shared DSL and verify
 * all three rule names exist in the manifest.
 *
 * <h3>Shared DSL:</h3>
 * <pre>
 * filter {
 *   json{}
 *   extractor{
 *     layer parsed.layer as String
 *     service parsed.service as String
 *     timestamp parsed.time as String
 *     if (tag("LOG_KIND") == "SLOW_SQL") {
 *       slowSql {
 *         id parsed.id as String
 *         statement parsed.statement as String
 *         latency parsed.query_time as Long
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Branch coverage:</h3>
 * <ul>
 *   <li>tag("LOG_KIND") == "SLOW_SQL" → slowSql block executed</li>
 *   <li>tag("LOG_KIND") != "SLOW_SQL" → slowSql block skipped</li>
 * </ul>
 */
class LALSlowSqlTest extends LALScriptComparisonBase {

    private static String SLOW_SQL_DSL;

    @BeforeAll
    static void loadRules() throws Exception {
        final List<LALConfig> rules = loadLALRules("mysql-slowsql.yaml");
        assertEquals(1, rules.size());
        assertEquals("mysql-slowsql", rules.get(0).getName());
        SLOW_SQL_DSL = rules.get(0).getDsl();
    }

    /**
     * LOG_KIND == "SLOW_SQL" with complete JSON body → json parsed,
     * layer/service/timestamp extracted, slowSql block executed with
     * id/statement/latency.
     *
     * <pre>
     * {
     *   "layer": "MYSQL",
     *   "service": "mysql-svc",
     *   "time": "1705305600000",
     *   "id": "stmt-001",
     *   "statement": "SELECT * FROM users WHERE id = 1",
     *   "query_time": 1500
     * }
     * </pre>
     */
    @Test
    void slowSqlTag_extractsSlowSqlFields() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "SLOW_SQL");

        final LogData logData = buildJsonLogData(
            "mysql-svc", "mysql-inst",
            "{\"layer\":\"MYSQL\",\"service\":\"mysql-svc\","
                + "\"time\":\"1705305600000\","
                + "\"id\":\"stmt-001\","
                + "\"statement\":\"SELECT * FROM users WHERE id = 1\","
                + "\"query_time\":1500}",
            tags);

        runAndCompare(SLOW_SQL_DSL, logData);
    }

    /**
     * LOG_KIND != "SLOW_SQL" → layer/service/timestamp still extracted,
     * but slowSql block is skipped entirely.
     */
    @Test
    void nonSlowSqlTag_skipsSlowSqlBlock() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "NORMAL");

        final LogData logData = buildJsonLogData(
            "mysql-svc", "mysql-inst",
            "{\"layer\":\"MYSQL\",\"service\":\"mysql-svc\","
                + "\"time\":\"1705305600000\","
                + "\"id\":\"stmt-002\","
                + "\"statement\":\"INSERT INTO logs\","
                + "\"query_time\":10}",
            tags);

        runAndCompare(SLOW_SQL_DSL, logData);
    }

    /**
     * Verify all three rule names (mysql, pgsql, redis) exist in the name manifest
     * and map to classes that resolve from the same DSL hash.
     */
    @Test
    void manifestContainsAllThreeSlowSqlRules() throws Exception {
        final Map<String, String> nameManifest = loadNameManifest();
        assertTrue(nameManifest.containsKey("mysql-slowsql"),
            "lal-scripts.txt should contain mysql-slowsql");
        assertTrue(nameManifest.containsKey("pgsql-slowsql"),
            "lal-scripts.txt should contain pgsql-slowsql");
        assertTrue(nameManifest.containsKey("redis-slowsql"),
            "lal-scripts.txt should contain redis-slowsql");

        // Verify DSL from all three files has the same SHA-256
        final List<LALConfig> mysqlRules = loadLALRules("mysql-slowsql.yaml");
        final List<LALConfig> pgsqlRules = loadLALRules("pgsql-slowsql.yaml");
        final List<LALConfig> redisRules = loadLALRules("redis-slowsql.yaml");

        final String mysqlHash = sha256(mysqlRules.get(0).getDsl());
        final String pgsqlHash = sha256(pgsqlRules.get(0).getDsl());
        final String redisHash = sha256(redisRules.get(0).getDsl());

        assertEquals(mysqlHash, pgsqlHash,
            "mysql and pgsql should have identical DSL");
        assertEquals(mysqlHash, redisHash,
            "mysql and redis should have identical DSL");

        // All resolve to the same pre-compiled class
        final Map<String, String> hashManifest = loadManifest();
        assertTrue(hashManifest.containsKey(mysqlHash),
            "Hash manifest should contain the shared slow SQL DSL hash");
    }
}
