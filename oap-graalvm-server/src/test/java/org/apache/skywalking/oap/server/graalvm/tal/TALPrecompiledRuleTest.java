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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.ScopeType;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.dsl.DslClassNaming;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALFunctionRegistry;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalDSL;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // As counts of (file, line, name): a list keeps a rule without a name apart from one named "null".
        final Map<List<String>, Long> expected = new HashMap<>();
        for (final String file : TALTestSupport.ruleFiles()) {
            if (!TALTestSupport.carried(file)) {
                continue;
            }
            // Bound as upstream's loader binds it (a name written as a one-element sequence is its element)
            final List<TALConfig> fileRules = TALTestSupport.fileConfigs(file).getRules();
            final List<Integer> lines = TALTestSupport.entryLines(file);
            // Upstream's loader names a rule's file as it is on disk.
            for (int i = 0; fileRules != null && i < fileRules.size(); i++) {
                expected.merge(Arrays.asList(file, String.valueOf(lines.get(i)), fileRules.get(i).getName()), 1L,
                    Long::sum);
            }
        }
        // A rules overlay may exclude every TAL file: the staged directory stays, empty.
        assertTrue(Files.isDirectory(TALTestSupport.TAL_DIR), "no staged tal/ at " + TALTestSupport.TAL_DIR);
        assertEquals(expected, rules.stream().collect(Collectors.groupingBy(
                r -> Arrays.asList(r.getSourceName(), String.valueOf(r.getLineNo()), r.getName()), Collectors.counting())),
            "every rule, at the entry line upstream's loader stamps");

        for (final TALConfig rule : rules) {
            final TALConfig entry = TALTestSupport.fileRule(rule);
            assertEquals(TALConfigs.TAL_CATALOG + rule.getSourceName(), rule.getSourcePath());
            assertEquals(entry.getDsl(), rule.getDsl(), rule.getName());
            assertEquals(entry.getLayer(), rule.getLayer(), rule.getName());
            assertEquals(entry.getInputType(), rule.getInputType(), rule.getName());
            assertEquals(TALTestSupport.fileConfigs(TALTestSupport.fileOf(rule)).getMetricPrefix(), rule.getMetricPrefix(),
                rule.getName());
        }
    }

    @Test
    void theConfigDataKeepsEachFilesRequiredModulesAndListedOrder() throws Exception {
        final List<String> withRules = new ArrayList<>();
        final ModuleManager noModules = mock(ModuleManager.class);
        for (final String file : TALTestSupport.ruleFiles()) {
            final String stem = TALTestSupport.stemOf(file);
            final TALConfigs configs = TALTestSupport.fileConfigs(file);
            final List<String> required = configs.getRequiredModules();
            // As sets: upstream reads the modules of every file it reads, by its own read of the key, which need not be
            // the modules its loader binds ([[core]] reads as [core]); the image carries every file, unless upstream's
            // boot skips it for the modules it binds.
            final boolean carried = TALTestSupport.carried(file);
            assertEquals(carried ? TALTestSupport.requiredModulesAsRead(file) : Set.of(),
                new HashSet<>(TALConfigs.requiredModulesOf("tal", List.of(stem))), file);
            final boolean hasRules = carried && configs.getRules() != null && !configs.getRules().isEmpty();
            if (hasRules) {
                withRules.add(stem);
            }
            // Skipped, as upstream skips it, while a module it requires is not loaded; a file with a rules list, empty or
            // not, that requires none is kept.
            final boolean requiresNone = required == null || required.isEmpty();
            assertEquals(carried && configs.getRules() != null && requiresNone ? 1 : 0,
                TALConfigs.load("tal", List.of(stem), noModules).size(),
                file);
        }
        // nativeTalFiles' contract: the files come back as listed, whichever order that is.
        Collections.reverse(withRules);
        // Each file by its rules (name and line).
        final List<List<List<String>>> loaded = new ArrayList<>();
        for (final TALConfigs configs : TALConfigs.loadInOrder("tal", withRules, null)) {
            loaded.add(ruleIds(configs));
        }
        final List<List<List<String>>> expectedOrder = new ArrayList<>();
        for (final String stem : withRules) {
            expectedOrder.add(ruleIds(TALConfigs.load("tal", List.of(stem), null).get(0)));
        }
        assertEquals(expectedOrder, loaded);
        // A stem no staged file has, whatever an overlay names its files.
        final List<String> stems = TALTestSupport.ruleFiles().stream().map(TALTestSupport::stemOf)
                                                 .collect(Collectors.toList());
        String missing = "no-such-tal-file";
        while (stems.contains(missing)) {
            missing += "-x";
        }
        final List<String> listed = List.of(missing);
        assertThrows(ModuleStartException.class, () -> TALConfigs.loadInOrder("tal", listed, null),
            "a listed native file that was not compiled fails the boot, as upstream's missing file does");
    }

    private static List<List<String>> ruleIds(final TALConfigs configs) {
        return configs.getRules().stream().map(rule -> Arrays.asList(rule.getName(), String.valueOf(rule.getLineNo())))
                      .collect(Collectors.toList());
    }

    @Test
    void thePrecompiledRuleCreatesTheMetricsAFreshCompileDeclares() throws Exception {
        try (MockedStatic<MetricsStreamProcessor> processor = Mockito.mockStatic(MetricsStreamProcessor.class)) {
            processor.when(MetricsStreamProcessor::getInstance).thenReturn(mock(MetricsStreamProcessor.class));
            for (final TALConfig rule : TALTestSupport.precompiledRules()) {
                final MeterSystem[] meterSystem = new MeterSystem[1];
                final ModuleManager moduleManager = TALTestSupport.moduleManager(
                    meterSystem, mock(SourceReceiver.class), TALTestSupport.namingControl());
                final TALFunctionRegistry functions = TALTestSupport.functionRegistry(moduleManager);
                final TALClassGenerator generator = TALTestSupport.freshGenerator(rule, functions);
                generator.compile(TALTestSupport.fileRule(rule).getDsl());
                final List<String> declared = new ArrayList<>();
                for (final TALMetricDeclaration d : generator.getMetricDeclarations()) {
                    declared.add("create " + d.fullName + " " + d.function + " " + ScopeType.valueOf(d.scopeType));
                    if (d.labelLimit > 0) {
                        declared.add("limitLabels " + d.fullName + " " + d.labelLimit);
                    }
                }

                meterSystem[0] = spy(new MeterSystem(moduleManager));
                final TalDSL dsl = TALTestSupport.loadPrecompiled(rule, moduleManager, functions);

                assertEquals(declared, meterCalls(meterSystem[0]), rule.getName());
                assertEquals(rule.getName(), dsl.getRuleName());
                // The allocator appends _2, _3 when two rules' stems collide, as it does on the JVM OAP.
                // A rule without a name is named "tal", as the generator names it.
                final String stem = "org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt." + DslClassNaming.stem(
                    DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo()),
                    rule.getName() == null ? "tal" : rule.getName(), TALConfigs.TAL_CATALOG);
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
