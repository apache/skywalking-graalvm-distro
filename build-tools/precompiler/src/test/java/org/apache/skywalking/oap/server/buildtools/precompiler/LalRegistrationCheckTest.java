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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LalRegistrationCheckTest {
    private static final AtomicInteger LINE = new AtomicInteger();

    @Test
    void aRuleUpstreamWouldSkipOrRefuseFailsTheBuild() {
        final Set<String> registered = new HashSet<>();
        assertDoesNotThrow(() -> Precompiler.checkRegistration(rule("a", "GENERAL", false), registered));
        assertDoesNotThrow(() -> Precompiler.checkRegistration(rule("a", "MESH", false), registered));
        assertDoesNotThrow(() -> Precompiler.checkRegistration(rule("a", "auto", true), registered));

        // Compiled, as upstream skips such a rule at boot with a warning, before it checks names or fallback.
        assertDoesNotThrow(() -> Precompiler.checkRegistration(rule("b", "NO_SUCH_LAYER", true), registered));
        assertDoesNotThrow(() -> Precompiler.checkRegistration(rule("b", "NO_SUCH_LAYER", false), registered));
        assertFails(rule("b", "GENERAL", true), registered, "only a layer: auto rule may");
        assertFails(rule("a", "GENERAL", false), registered, "two rules of layer GENERAL are named a");
        assertFails(rule("a", "auto", false), registered, "two auto-layer rules are named a");
    }

    @Test
    void twoRulesOnOneLineFailTheBuild() {
        final Set<String> registered = new HashSet<>();
        final LALConfig first = rule("a", "GENERAL", false);
        first.setLineNo(3);
        final LALConfig second = rule("b", "GENERAL", false);
        second.setLineNo(3);
        assertDoesNotThrow(() -> Precompiler.checkRegistration(first, registered));
        assertFails(second, registered, "starts on line 3 with another rule");
    }

    private static void assertFails(final LALConfig rule, final Set<String> registered, final String message) {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Precompiler.checkRegistration(rule, registered));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static LALConfig rule(final String name, final String layer, final boolean fallback) {
        final LALConfig rule = new LALConfig();
        rule.setName(name);
        rule.setLayer(layer);
        rule.setFallback(fallback);
        rule.setSourcePath("lal/test.yaml");
        rule.setLineNo(LINE.incrementAndGet());
        return rule;
    }
}
