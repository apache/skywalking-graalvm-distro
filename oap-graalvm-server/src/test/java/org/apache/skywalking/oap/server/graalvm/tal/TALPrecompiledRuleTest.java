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

package org.apache.skywalking.oap.server.graalvm.tal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.ScopeType;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.dsl.DslClassNaming;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalDSL;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Every TAL rule file under {@code tal/} as the distro boots it: the replacement {@code TALConfigs} hands
 * {@code SpanFilterListener} the rules and coordinates upstream's would, and the replacement {@code TalDSL} loads
 * the class the JVM OAP would name and creates exactly the metrics a fresh compile declares.
 */
class TALPrecompiledRuleTest {

    @Test
    void theConfigDataCarriesEveryRuleOfEveryFile() throws Exception {
        final List<TALConfig> rules = TALTestSupport.precompiledRules();
        final List<String> expected = new ArrayList<>();
        for (final String file : TALTestSupport.ruleFiles()) {
            final Object fileRules = TALTestSupport.fileYaml(file).get("rules");
            final List<Integer> lines = TALTestSupport.entryLines(file);
            final TALConfig stamped = new TALConfig();
            TALConfigs.stampSource(stamped, file);
            for (int i = 0; fileRules instanceof List && i < ((List<?>) fileRules).size(); i++) {
                expected.add(stamped.getSourceName() + ":" + lines.get(i) + "#"
                    + ((Map<?, ?>) ((List<?>) fileRules).get(i)).get("name"));
            }
        }
        assertFalse(expected.isEmpty(), "tal/ holds no rule");
        expected.sort(null);
        assertEquals(expected, rules.stream().map(r -> r.getSourceName() + ":" + r.getLineNo() + "#" + r.getName())
                                    .sorted().collect(Collectors.toList()),
            "every rule, at the entry line upstream's loader stamps");

        for (final TALConfig rule : rules) {
            final Map<String, Object> raw = TALTestSupport.yamlRule(rule);
            assertEquals(TALConfigs.TAL_CATALOG + rule.getSourceName(), rule.getSourcePath());
            assertEquals(raw.get("dsl"), rule.getDsl(), rule.getName());
            assertEquals(raw.get("layer"), rule.getLayer(), rule.getName());
            assertEquals(raw.get("inputType"), rule.getInputType(), rule.getName());
            assertEquals(raw.get("outputType"), rule.getOutputType(), rule.getName());
            assertEquals(TALTestSupport.fileYaml(TALTestSupport.fileOf(rule)).get("metricPrefix"), rule.getMetricPrefix(),
                rule.getName());
        }
    }

    @Test
    void thePrecompiledRuleCreatesTheMetricsAFreshCompileDeclares() throws Exception {
        try (MockedStatic<MetricsStreamProcessor> processor = Mockito.mockStatic(MetricsStreamProcessor.class)) {
            processor.when(MetricsStreamProcessor::getInstance).thenReturn(mock(MetricsStreamProcessor.class));
            for (final TALConfig rule : TALTestSupport.precompiledRules()) {
                final TALClassGenerator generator = TALTestSupport.freshGenerator(rule);
                generator.compile((String) TALTestSupport.yamlRule(rule).get("dsl"));
                final List<String> declared = new ArrayList<>();
                for (final TALMetricDeclaration d : generator.getMetricDeclarations()) {
                    declared.add("create " + d.fullName + " " + d.function + " " + ScopeType.valueOf(d.scopeType));
                    if (d.labelLimit > 0) {
                        declared.add("limitLabels " + d.fullName + " " + d.labelLimit);
                    }
                }

                final MeterSystem[] meterSystem = new MeterSystem[1];
                final ModuleManager moduleManager = TALTestSupport.moduleManager(
                    meterSystem, mock(SourceReceiver.class), TALTestSupport.namingControl());
                meterSystem[0] = spy(new MeterSystem(moduleManager));
                final TalDSL dsl = TALTestSupport.loadPrecompiled(rule, moduleManager);

                assertEquals(declared, meterCalls(meterSystem[0]), rule.getName());
                assertEquals(rule.getName(), dsl.getRuleName());
                // The allocator appends _2, _3 when two rules' stems collide, as it does on the JVM OAP.
                final String stem = "org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt." + DslClassNaming.stem(
                    DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo()), rule.getName(), TALConfigs.TAL_CATALOG);
                final String className = dsl.getExpression().getClass().getName();
                assertTrue(className.equals(stem) || className.matches(Pattern.quote(stem) + "_\\d+"),
                    className + " is not named as the JVM OAP names it, " + stem);
            }
        }
    }

    private static List<String> meterCalls(final MeterSystem meterSystem) {
        final List<String> calls = new ArrayList<>();
        for (final Invocation invocation : Mockito.mockingDetails(meterSystem).getInvocations()) {
            final String method = invocation.getMethod().getName();
            if ("create".equals(method) && invocation.getArguments().length == 3) {
                calls.add("create " + invocation.getArgument(0) + " " + invocation.getArgument(1) + " "
                    + invocation.getArgument(2));
            } else if ("limitLabels".equals(method)) {
                calls.add("limitLabels " + invocation.getArgument(0) + " " + invocation.getArgument(1));
            }
        }
        return calls;
    }
}
