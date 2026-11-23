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

package org.apache.skywalking.oap.trace.analyzer.v2.dsl;

import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import org.apache.skywalking.oap.server.graalvm.rules.TalRule;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALFunctionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The same-FQCN {@code TalDSL} picks, among the compiled rules the registry holds at a rule's coordinates, the class
 * compiled from exactly the inputs it is given, and names the module whose function objects the rule needs when the
 * bound function registry lacks them, and refuses two classes compiled from the same inputs, which the build never
 * records (it records one class for rules alike in everything the class and its wiring are compiled from). How the registry carries every input of a rule is pinned by the precompiler's
 * {@code RegistryParityTest} and {@code RegistryGeneratorTest}.
 */
class TalDSLManifestTest {
    @Test
    void theClassCompiledFromTheseInputsIsPicked() {
        // Flow-style YAML puts two rules on one line, so the coordinates hold both.
        final TalRule a = rule("a", "otlp", "", "filter { persist {} }");
        final TalRule b = rule("b", "sw-native", "p", "filter { abort {} }");
        final List<TalRule> candidates = List.of(a, b);

        assertSame(a, TalDSL.select(candidates, "a", "filter { persist {} }", Span.class, "otlp", null));
        assertSame(b, TalDSL.select(candidates, "b", "filter { abort {} }", Span.class, "sw-native", "p"));
        assertNull(TalDSL.select(candidates, "a", "filter { abort {} }", Span.class, "otlp", null),
            "rule text edited after the build");
        assertNull(TalDSL.select(candidates, "a", "filter { persist {} }", String.class, "otlp", null),
            "another input type");
        assertNull(TalDSL.select(candidates, "b", "filter { abort {} }", Span.class, "otlp", "p"),
            "another input alias, whose functions the class was not compiled against");
        assertNull(TalDSL.select(candidates, "b", "filter { abort {} }", Span.class, "sw-native", "q"),
            "another metric prefix");
        assertNull(TalDSL.select(candidates, "c", "filter { persist {} }", Span.class, "otlp", null), "another name");
        assertEquals(2, candidates.size());
    }

    @Test
    void aRuleWithoutANameIsFoundApartFromOneNamedNull() {
        final TalRule unnamed = rule(null, "sw-native", "", "filter { persist {} }");
        final TalRule namedNull = rule("null", "sw-native", "", "filter { persist {} }");
        final List<TalRule> candidates = List.of(unnamed, namedNull);

        assertSame(unnamed, TalDSL.select(candidates, null, "filter { persist {} }", Span.class, "sw-native", null));
        assertSame(namedNull, TalDSL.select(candidates, "null", "filter { persist {} }", Span.class, "sw-native", null));
        assertNull(TalDSL.select(candidates, "", "filter { persist {} }", Span.class, "sw-native", null));
    }

    @Test
    void twoClassesCompiledFromTheSameInputsAreNeverPickedOneForTheOther() {
        final TalRule first = rule("twin", "otlp", "", "filter { persist {} }");
        final TalRule second = rule("twin", "otlp", "", "filter { persist {} }");

        assertThrows(IllegalStateException.class,
            () -> TalDSL.select(List.of(first, second), "twin", "filter { persist {} }", Span.class, "otlp", null));
    }

    @Test
    void aFunctionObjectOfAModuleNotLoadedIsReportedByTheModule() {
        final String provider = "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationTALFunctions";
        final TalRule calling = new TalRule("tal/eval.yaml", 3, "eval", () -> null, Span.class.getName(), "otlp", "",
            TalDSL.sha256("filter {}"), List.of(), List.of(new TalRule.Function("_fn0", provider, provider)));
        // Bound as the trace analyzer binds it while no module of a function provider is loaded.
        final TALFunctionRegistry registry = new TALFunctionRegistry(List.of());
        registry.bind(null, List.of());

        final Exception e = assertThrows(IllegalStateException.class,
            () -> TalDSL.setFunctionFields((filterSpec, ctx) -> { }, calling, registry));
        assertEquals("it calls functions of " + provider + ", which module ai-evaluation provides, and that module "
            + "is not loaded", e.getMessage());
    }

    private static TalRule rule(final String name, final String inputAlias, final String metricPrefix,
                                final String dsl) {
        return new TalRule("tal/inline.yaml", 1, name, () -> null, Span.class.getName(), inputAlias, metricPrefix,
            TalDSL.sha256(dsl), List.of(), List.of());
    }
}
