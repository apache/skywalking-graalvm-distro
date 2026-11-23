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
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-compilation test for lal/default.yaml.
 *
 * <p>Verifies that the pre-compiled LAL class for the default rule
 * can be loaded from the generated rule registry and instantiated.
 */
class LALDefaultTest extends LALScriptComparisonBase {

    @Test
    void defaultRulePrecompiledClassLoads() throws Exception {
        final List<LALConfig> rules = loadLALRules("default.yaml");
        assertEquals(List.of("default", "otlp-general", "default-fallback"),
            rules.stream().map(LALConfig::getName).collect(Collectors.toList()));
        for (final LALConfig rule : rules) {
            assertNotNull(loadPrecompiled(rule), "Pre-compiled class for '" + rule.getName() + "' should be loadable");
        }
    }

    @Test
    void fallbackFlagSurvivesTheConfigData() throws Exception {
        // LogAnalyzer runs fallback rules only after the auto rules; dropped by the rule registry, the rule would be an
        // ordinary auto rule and claim every layerless log first.
        for (final LALConfig rule : loadLALRules("default.yaml")) {
            assertEquals("default-fallback".equals(rule.getName()), rule.isFallback(), rule.getName());
        }
    }

    @Test
    void registryContainsDefaultRule() {
        assertTrue(CompiledRules.lalRules().stream().anyMatch(rule -> "lal/default.yaml".equals(rule.getSource())
                && "default".equals(rule.getName())),
            "The rule registry should contain the 'default' rule");
    }
}
