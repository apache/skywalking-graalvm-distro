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

package org.apache.skywalking.oap.server.graalvm.rules;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.oap.server.core.rule.ext.RuleSetMerger;
import org.apache.skywalking.oap.server.core.rule.ext.RuntimeRuleOverrideResolver;
import org.apache.skywalking.oap.server.core.rule.ext.RuntimeRuleOverrideResolver.Resolution;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The files a replacement loader loads go through upstream's {@code RuleSetMerger}, which records them for the rule
 * catalog; a resolver that would run other bytes than those compiled, or none, fails the boot.
 */
class RuleSourcesTest {
    private static final Map<String, byte[]> FILES = Map.of(
        "vm", "metricsRules: []\n".getBytes(StandardCharsets.UTF_8),
        "k8s/node", "metricsRules: []\n".getBytes(StandardCharsets.UTF_8));

    @Test
    void theMergedFilesAreRecordedAndUnchangedOnesPass() {
        final Map<String, byte[]> merged = RuleSetMerger.merge("rule-sources-test", new HashMap<>(FILES), List.of(), null);
        assertDoesNotThrow(() -> RuleSources.requireUnchanged("rule-sources-test", FILES, merged));
        assertEquals("metricsRules: []\n", StaticRuleRegistry.active().find("rule-sources-test", "k8s/node").orElse(null));
    }

    @Test
    void aResolverChangingOrDroppingACompiledFileFailsTheBoot() {
        final RuntimeRuleOverrideResolver changes = (catalog, manager) ->
            Map.of("vm", Resolution.active("metricsRules: [x]\n".getBytes(StandardCharsets.UTF_8)));
        final RuntimeRuleOverrideResolver drops = (catalog, manager) -> Map.of("k8s/node", Resolution.inactive());
        for (final RuntimeRuleOverrideResolver resolver : List.of(changes, drops)) {
            final Map<String, byte[]> merged =
                RuleSetMerger.merge("rule-sources-test", new HashMap<>(FILES), List.of(resolver), null);
            assertThrows(IllegalStateException.class,
                () -> RuleSources.requireUnchanged("rule-sources-test", FILES, merged));
        }
    }
}
