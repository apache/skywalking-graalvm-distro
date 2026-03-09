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
import java.util.Map;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pre-compilation test for lal/default.yaml.
 *
 * <p>Verifies that the pre-compiled LAL class for the default rule
 * can be loaded from the manifest and instantiated.
 */
class LALDefaultTest extends LALScriptComparisonBase {

    @Test
    void defaultRulePrecompiledClassLoads() throws Exception {
        final List<LALConfig> rules = loadLALRules("default.yaml");
        assertEquals(1, rules.size());
        assertEquals("default", rules.get(0).getName());

        final LalExpression expr = loadPrecompiled("default");
        assertNotNull(expr, "Pre-compiled class for 'default' rule should be loadable");
    }

    @Test
    void manifestContainsDefaultRule() {
        final Map<String, String> manifest = loadManifest();
        boolean found = false;
        for (final String key : manifest.keySet()) {
            if (key.contains("default")) {
                found = true;
                break;
            }
        }
        assert found : "Manifest should contain 'default' rule";
    }
}
