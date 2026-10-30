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
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.DatabaseSlowStatementBuilder;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The same-FQCN {@code TalDSL} reads back what the precompiler writes for an operator's rules, the ones the shipped
 * rules never exercise included, and picks the class compiled from exactly the inputs it is given. The writer side
 * is pinned by the precompiler's {@code TALPrecompileTest}.
 */
class TalDSLManifestTest {
    private static final String SPAN = Span.class.getName();
    private static final String SLOW_SQL = DatabaseSlowStatementBuilder.class.getName();

    @Test
    void everyFieldAndNameRoundTrips() throws Exception {
        final Properties written = new Properties();
        rule(written, 0, "tal/flotte.yaml", 19, "zählung-café", "rt.flotte_L19_a", SPAN, "", "fleet", "filter { persist {} }");
        metric(written, 0, 0, "fleet_latency_percentile", "sumHistogramPercentile", "10,25,50", "50,99", 0);
        metric(written, 0, 1, "fleet_calls_by_status", "sumLabeled", "", "", 7);
        rule(written, 1, "tal/flotte.yaml", 30, " payments\\edge=x:y\n", "rt.flotte_L30_b", SPAN, SLOW_SQL, "fleet",
            "filter { sink {} }");

        final Map<String, List<TalDSL.PrecompiledRule>> rules = TalDSL.parseManifest(readBack(written));

        final TalDSL.PrecompiledRule first = rules.get("tal/flotte.yaml:19").get(0);
        assertEquals("zählung-café", first.name);
        assertEquals(List.of(
                new TALMetricDeclaration("fleet_latency_percentile", "sumHistogramPercentile", "SERVICE",
                    "fleet_latency_percentile", List.of(10L, 25L, 50L), List.of(50, 99), 0),
                new TALMetricDeclaration("fleet_calls_by_status", "sumLabeled", "SERVICE",
                    "fleet_calls_by_status", List.of(), List.of(), 7)),
            first.metrics);
        final TalDSL.PrecompiledRule second = rules.get("tal/flotte.yaml:30").get(0);
        assertEquals(" payments\\edge=x:y\n", second.name);
        assertEquals(SLOW_SQL, second.outputType);
        assertEquals(List.of(), second.metrics);
    }

    @Test
    void theClassCompiledFromTheseInputsIsPicked() throws Exception {
        // Flow-style YAML puts two rules on one line, so the coordinates hold both.
        final Properties written = new Properties();
        rule(written, 0, "tal/inline.yaml", 1, "a", "rt.inline_L1_a", SPAN, "", "", "filter { persist {} }");
        rule(written, 1, "tal/inline.yaml", 1, "b", "rt.inline_L1_b", SPAN, SLOW_SQL, "p", "filter { sink {} }");
        final List<TalDSL.PrecompiledRule> candidates = TalDSL.parseManifest(readBack(written)).get("tal/inline.yaml:1");
        assertEquals(2, candidates.size());

        assertEquals("rt.inline_L1_a",
            TalDSL.select(candidates, "a", "filter { persist {} }", Span.class, null, null).className);
        assertEquals("rt.inline_L1_b",
            TalDSL.select(candidates, "b", "filter { sink {} }", Span.class, DatabaseSlowStatementBuilder.class, "p")
                  .className);
        assertNull(TalDSL.select(candidates, "a", "filter { abort {} }", Span.class, null, null),
            "rule text edited after the build");
        assertNull(TalDSL.select(candidates, "a", "filter { persist {} }", String.class, null, null),
            "another input type");
        assertNull(TalDSL.select(candidates, "b", "filter { sink {} }", Span.class, null, "p"), "another output type");
        assertNull(TalDSL.select(candidates, "b", "filter { sink {} }", Span.class, DatabaseSlowStatementBuilder.class,
            "q"), "another metric prefix");
        assertNull(TalDSL.select(candidates, "c", "filter { persist {} }", Span.class, null, null), "another name");
    }

    private static void rule(final Properties props, final int i, final String source, final int line,
                             final String name, final String className, final String inputType,
                             final String outputType, final String metricPrefix, final String dsl) {
        final String prefix = "rule." + i + ".";
        props.setProperty(prefix + "source", source);
        props.setProperty(prefix + "line", String.valueOf(line));
        props.setProperty(prefix + "name", name);
        props.setProperty(prefix + "class", className);
        props.setProperty(prefix + "inputType", inputType);
        props.setProperty(prefix + "outputType", outputType);
        props.setProperty(prefix + "metricPrefix", metricPrefix);
        props.setProperty(prefix + "dslSha256", TalDSL.sha256(dsl));
    }

    private static void metric(final Properties props, final int rule, final int m, final String fullName,
                               final String function, final String buckets, final String ranks, final int labelLimit) {
        final String prefix = "rule." + rule + ".metric." + m + ".";
        props.setProperty(prefix + "fullName", fullName);
        props.setProperty(prefix + "function", function);
        props.setProperty(prefix + "scopeType", "SERVICE");
        props.setProperty(prefix + "declaredName", fullName);
        props.setProperty(prefix + "buckets", buckets);
        props.setProperty(prefix + "ranks", ranks);
        props.setProperty(prefix + "labelLimit", String.valueOf(labelLimit));
    }

    /**
     * The precompiler's format: {@code Properties#store}'s escapes, written and read as UTF-8.
     */
    private static Properties readBack(final Properties written) throws Exception {
        final StringWriter out = new StringWriter();
        written.store(out, null);
        final Properties read = new Properties();
        read.load(new InputStreamReader(
            new ByteArrayInputStream(out.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        return read;
    }
}
