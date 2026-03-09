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

import java.util.List;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-compilation test for slow SQL LAL files:
 * mysql-slowsql.yaml, pgsql-slowsql.yaml, redis-slowsql.yaml.
 */
class LALSlowSqlTest extends LALScriptComparisonBase {

    @Test
    void mysqlSlowSqlPrecompiled() throws Exception {
        verifyRulesPrecompiled("mysql-slowsql.yaml", 1);
    }

    @Test
    void pgsqlSlowSqlPrecompiled() throws Exception {
        verifyRulesPrecompiled("pgsql-slowsql.yaml", 1);
    }

    @Test
    void redisSlowSqlPrecompiled() throws Exception {
        verifyRulesPrecompiled("redis-slowsql.yaml", 1);
    }

    private void verifyRulesPrecompiled(String yamlFile, int minRules) throws Exception {
        final List<LALConfig> rules = loadLALRules(yamlFile);
        assertTrue(rules.size() >= minRules,
            yamlFile + " should have at least " + minRules + " rule(s)");
        for (final LALConfig rule : rules) {
            final LalExpression expr = loadPrecompiled(rule.getName());
            assertNotNull(expr,
                "Pre-compiled class for rule '" + rule.getName() + "' should load");
        }
    }
}
