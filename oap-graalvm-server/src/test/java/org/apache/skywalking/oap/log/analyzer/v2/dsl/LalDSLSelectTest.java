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
 *
 */

package org.apache.skywalking.oap.log.analyzer.v2.dsl;

import java.util.List;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same-FQCN LAL {@code DSL} takes only the class compiled from exactly what upstream's loader hands it, so a
 * change in how upstream resolves a rule's text or types fails the boot instead of running another class. How the
 * registry carries those inputs is pinned by the precompiler's {@code RegistryParityTest}.
 */
class LalDSLSelectTest {
    private static final String DSL_TEXT = "filter { sink { } }";

    @Test
    void theClassCompiledFromTheseInputsIsPicked() {
        final LalRule plain = rule("a", DSL_TEXT, "", "");
        final LalRule typed = rule("a", DSL_TEXT, LogData.class.getName(), "");
        final List<LalRule> candidates = List.of(plain, typed);

        assertSame(plain, DSL.select(candidates, "a", DSL_TEXT, null, null));
        assertSame(typed, DSL.select(candidates, "a", DSL_TEXT, LogData.class, null));
        assertNull(DSL.select(candidates, "a", "filter { abort { } }", null, null), "rule text edited after the build");
        assertNull(DSL.select(candidates, "b", DSL_TEXT, null, null), "another name");
        assertNull(DSL.select(candidates, "a", DSL_TEXT, String.class, null), "another input type");
        assertNull(DSL.select(candidates, "a", DSL_TEXT, null, String.class), "another output type");
        assertNull(DSL.select(List.of(), "a", DSL_TEXT, null, null), "nothing compiled at these coordinates");
        assertThrows(IllegalStateException.class,
            () -> DSL.select(List.of(plain, rule("a", DSL_TEXT, "", "")), "a", DSL_TEXT, null, null),
            "two rules of one name and text: which one is not decided");
    }

    @Test
    void aRuleWithoutANameIsFoundApartFromOneNamedNull() {
        final LalRule unnamed = rule(null, DSL_TEXT, "", "");
        final LalRule namedNull = rule("null", DSL_TEXT, "", "");
        final List<LalRule> candidates = List.of(unnamed, namedNull);

        assertSame(unnamed, DSL.select(candidates, null, DSL_TEXT, null, null));
        assertSame(namedNull, DSL.select(candidates, "null", DSL_TEXT, null, null));
        assertNull(DSL.select(candidates, "", DSL_TEXT, null, null));
    }

    @Test
    void anAdHocRuleWithoutSourceCoordinatesIsRefused() {
        // The log test tool's DSL.of(moduleManager, config, dsl): nothing compiled it.
        final ModuleStartException e = assertThrows(ModuleStartException.class, () -> DSL.of(null, null, DSL_TEXT));
        assertTrue(e.getMessage().contains("Ad-hoc LAL (the log test tool) is not supported in the native image"),
            e.getMessage());
    }

    private static LalRule rule(final String name, final String dsl, final String inputType, final String outputType) {
        return new LalRule("lal/inline.yaml", 1, name, () -> null, null, DSL.sha256(dsl), inputType, outputType);
    }
}
