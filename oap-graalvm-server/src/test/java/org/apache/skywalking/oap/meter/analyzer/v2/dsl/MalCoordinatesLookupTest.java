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

package org.apache.skywalking.oap.meter.analyzer.v2.dsl;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalExpression;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalFilter;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The same-FQCN MAL {@code DSL} and {@code FilterExpression} return the class compiled for the rule at the coordinates
 * {@code MetricConvert} passes, so rules of equal text keep their own classes, and fail the boot where nothing was
 * compiled for what upstream asks. How the registry carries the coordinates is pinned by the precompiler's
 * {@code RegistryParityTest}.
 */
class MalCoordinatesLookupTest {
    /** A rule file under no rule directory, which no compiled rule can come from. */
    private static final String OUTSIDE_THE_CATALOGS = "no-rule-catalog/fleet.yaml";

    @Test
    void eachRuleGetsTheClassCompiledForItsCoordinates() throws Exception {
        for (final CompiledMalExpression compiled : CompiledRules.malExpressions()) {
            final Expression expression = DSL.parse(compiled.getMetricName(), compiled.getExpression(),
                DslSourceRef.ofRule(compiled.getSource(), compiled.getLine()));
            assertEquals(compiled.getCompiled().get().getClass().getName(), expression.generatedClassName());
        }
        for (final CompiledMalFilter compiled : CompiledRules.malFilters()) {
            final FilterExpression filter = new FilterExpression(compiled.getLiteral(), "filter",
                DslSourceRef.ofRule(compiled.getSource(), compiled.getLine()));
            assertEquals(compiled.getCompiled().get().getClass().getName(), filterClass(filter));
        }
    }

    @Test
    void filtersOfEqualLiteralKeepTheirOwnClasses() throws Exception {
        final Map<String, Set<String>> classes = new HashMap<>();
        final Map<String, Integer> files = new HashMap<>();
        for (final CompiledMalFilter compiled : CompiledRules.malFilters()) {
            final FilterExpression filter = new FilterExpression(compiled.getLiteral(), "filter",
                DslSourceRef.ofRule(compiled.getSource(), compiled.getLine()));
            classes.computeIfAbsent(compiled.getLiteral(), k -> new HashSet<>()).add(filterClass(filter));
            files.merge(compiled.getLiteral(), 1, Integer::sum);
        }
        Assumptions.assumeTrue(files.values().stream().anyMatch(n -> n > 1), "no two rule files share a filter");
        files.forEach((literal, n) -> assertEquals(n, classes.get(literal).size(), literal));
    }

    /**
     * Fixed rules, so the lookup is pinned whatever the build compiled (a rules overlay may compile no MAL file).
     */
    @Test
    void aRuleIsFoundByItsCoordinatesNameAndTextAndNothingElse() {
        final MalExpression first = mock(MalExpression.class);
        final MalExpression second = mock(MalExpression.class);
        final List<CompiledMalExpression> atLine = List.of(
            new CompiledMalExpression("otel-rules/fleet.yaml", 7, "meter_fleet_cpu", "cpu.sum(['host'])", () -> first),
            new CompiledMalExpression("otel-rules/fleet.yaml", 7, "meter_fleet_mem", "cpu.sum(['host'])", () -> second));
        final DslSourceRef at = DslSourceRef.ofRule("otel-rules/fleet.yaml", 7);

        assertSame(first, DSL.select(atLine, "meter_fleet_cpu", "cpu.sum(['host'])", at).get());
        assertSame(second, DSL.select(atLine, "meter_fleet_mem", "cpu.sum(['host'])", at).get());
        final IllegalStateException edited = assertThrows(IllegalStateException.class,
            () -> DSL.select(atLine, "meter_fleet_cpu", "cpu.sum(['x'])", at));
        assertTrue(edited.getMessage().startsWith("No pre-compiled MAL expression for metric: meter_fleet_cpu ("
            + at.describeYaml() + ")"), edited.getMessage());
        assertThrows(IllegalStateException.class, () -> DSL.select(atLine, "meter_fleet_disk", "cpu.sum(['host'])", at));
        assertThrows(IllegalStateException.class, () -> DSL.select(List.of(), "meter_fleet_cpu", "cpu.sum(['host'])",
            at), "nothing compiled at these coordinates");

        final MalFilter filter = labels -> true;
        final List<CompiledMalFilter> filters =
            List.of(new CompiledMalFilter("otel-rules/fleet.yaml", 3, "{ tags -> tags.job == 'fleet' }", () -> filter));
        assertSame(filter, FilterExpression.select(filters, "{ tags -> tags.job == 'fleet' }",
            DslSourceRef.ofRule("otel-rules/fleet.yaml", 3)));
        assertThrows(IllegalStateException.class, () -> FilterExpression.select(filters,
            "{ tags -> tags.edited == 'x' }", DslSourceRef.ofRule("otel-rules/fleet.yaml", 3)));
        assertThrows(IllegalStateException.class, () -> FilterExpression.select(List.of(),
            "{ tags -> tags.job == 'fleet' }", DslSourceRef.ofRule("otel-rules/fleet.yaml", 3)));
    }

    /**
     * Upstream locates every rule; nothing was compiled at coordinates no rule file has, whatever the build compiled.
     * The coordinates name no rule directory, so no rules overlay can have compiled anything there.
     */
    @Test
    void aRuleNothingWasCompiledForFailsTheBoot() {
        assertTrue(RuleCatalog.ofDirectory(OUTSIDE_THE_CATALOGS.substring(0, OUTSIDE_THE_CATALOGS.indexOf('/')))
                              .isEmpty());
        assertThrows(IllegalStateException.class, () -> DSL.parse("meter_fleet_cpu", "cpu.sum(['host'])",
            DslSourceRef.ofRule(OUTSIDE_THE_CATALOGS, 1)));
        assertThrows(IllegalStateException.class, () -> DSL.parse("no_such_metric", "unknown_metric"));
        assertThrows(IllegalStateException.class, () -> new FilterExpression("{ tags -> tags.edited == 'x' }",
            "filter", DslSourceRef.ofRule(OUTSIDE_THE_CATALOGS, 1)));
    }

    @Test
    void withoutCoordinatesTheClassCompiledFromTheSameTextIsReturned() throws Exception {
        for (final CompiledMalExpression expression : CompiledRules.malExpressions()) {
            final String className =
                DSL.parse(expression.getMetricName(), expression.getExpression()).generatedClassName();
            assertTrue(CompiledRules.malExpressions().stream().anyMatch(
                c -> c.getMetricName().equals(expression.getMetricName())
                    && c.getExpression().equals(expression.getExpression())
                    && c.getCompiled().get().getClass().getName().equals(className)), className);
        }
    }

    // The filter is private: FilterExpression has no accessor for it.
    private static String filterClass(final FilterExpression filter) throws Exception {
        final Field field = FilterExpression.class.getDeclaredField("malFilter");
        field.setAccessible(true);
        return field.get(filter).getClass().getName();
    }
}
