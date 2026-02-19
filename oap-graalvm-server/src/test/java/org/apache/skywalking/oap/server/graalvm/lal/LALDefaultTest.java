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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparison test for lal/default.yaml.
 *
 * <p>The simplest LAL rule — just saves all logs with no parsing or extraction:
 * <pre>
 * filter {
 *   sink {
 *   }
 * }
 * </pre>
 */
class LALDefaultTest extends LALScriptComparisonBase {

    @Test
    void defaultRuleSavesAllLogs() throws Exception {
        final List<LALConfig> rules = loadLALRules("default.yaml");
        assertEquals(1, rules.size());
        assertEquals("default", rules.get(0).getName());

        final LogData logData = buildTextLogData(
            "test-service", "test-instance", "some log message",
            Collections.emptyMap());

        runAndCompare(rules.get(0).getDsl(), logData);
    }

    @Test
    void manifestContainsDefaultRule() {
        final Map<String, String> nameManifest = loadNameManifest();
        assertTrue(nameManifest.containsKey("default"),
            "lal-scripts.txt should contain 'default' rule");
        assertFalse(nameManifest.get("default").isEmpty(),
            "default rule should map to a class name");
    }
}
