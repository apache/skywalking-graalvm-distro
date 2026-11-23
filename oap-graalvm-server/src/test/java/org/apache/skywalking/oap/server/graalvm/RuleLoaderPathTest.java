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

package org.apache.skywalking.oap.server.graalvm;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.RuleSources;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Each replacement loader takes its arguments as its upstream class does, pinned against that class: a directory that
 * does not exist fails, as reading a missing one does upstream, instead of silently loading nothing or another
 * directory's rules; a blank path is refused, or not, as upstream does it.
 */
class RuleLoaderPathTest {
    @Test
    void aMalCatalogNoRulesWereCompiledFromFails() throws Exception {
        assertThrows(NullPointerException.class, () -> Rules.loadRules("no-such-rules", List.of("vm")));
        assertFalse(Rules.loadRules("otel-rules", List.of("vm")).isEmpty());
    }

    /**
     * Each replacement loader validates its arguments as its upstream class does, in the same order: a null, empty or
     * blank path, a null or empty list, a directory that does not exist. Upstream's own class, from its jar on the test
     * class path (the replacement shadows it), answers the same calls.
     */
    @Test
    void eachLoaderValidatesItsArgumentsAsUpstreamsDoes() throws Exception {
        final List<String> paths = Arrays.asList(null, "", " ", "no-such-rule-directory");
        final List<List<String>> lists = Arrays.asList(null, List.of(), List.of("vm"));
        try (UpstreamClasses upstream = new UpstreamClasses(Rules.class, LALConfigs.class, TALConfigs.class,
            ZabbixConfigs.class, ResourceUtils.class)) {
            for (final String path : paths) {
                for (final List<String> list : lists) {
                    final String call = "(" + (path == null ? null : "'" + path + "'") + ", " + list + ")";
                    assertEquals(outcome(upstream, Rules.class, "loadRules", path, list),
                        outcome(() -> Rules.loadRules(path, list)), "Rules.loadRules" + call);
                    assertEquals(outcome(upstream, LALConfigs.class, "load", path, list),
                        outcome(() -> LALConfigs.load(path, list)), "LALConfigs.load" + call);
                    assertEquals(outcome(upstream, TALConfigs.class, "load", path, list),
                        outcome(() -> TALConfigs.load(path, list)), "TALConfigs.load" + call);
                    assertEquals(outcome(upstream, TALConfigs.class, "requiredModulesOf", path, list),
                        outcome(() -> TALConfigs.requiredModulesOf(path, list)), "TALConfigs.requiredModulesOf" + call);
                    assertEquals(outcome(upstream, ZabbixConfigs.class, "loadConfigs", path, list),
                        outcome(() -> ZabbixConfigs.loadConfigs(path, list)), "ZabbixConfigs.loadConfigs" + call);
                }
            }
        }
    }

    @Test
    void aMalCatalogTheRulesOverlayEmptiedSelectsNothing() {
        // The overlay excluded every Telegraf file: the directory stays, so upstream's loader finds no file of the name.
        final Map<String, String> activation = new HashMap<>(CompiledRules.activation());
        activation.put(RuleCatalog.TELEGRAF_RULES.setting().key(), "");
        try (MockedStatic<CompiledRules> registry = mockStatic(CompiledRules.class, CALLS_REAL_METHODS)) {
            registry.when(CompiledRules::activation).thenReturn(activation);
            final UnexpectedException e = assertThrows(UnexpectedException.class,
                () -> Rules.loadRules("telegraf-rules", List.of("vm")));
            assertEquals("Some configuration files of enabled rules are not found, enabled rules: [vm{.yaml,.yml}]",
                e.getMessage());
        }
    }

    /**
     * The distro's catalog boundary, no upstream parity: only a MAL catalog's directory holds MAL files, so a name in
     * another loader's directory is not found (upstream would bind that loader's file as MAL and fail on its YAML; no
     * shipped provider passes such a path). A MAL catalog the registry has no activation for fails loudly.
     */
    @Test
    void anotherLoadersDirectoryHoldsNoMalFileAndAMissingActivationFails() {
        for (final String path : List.of("lal", "zabbix-rules", "tal", "oal")) {
            final UnexpectedException e = assertThrows(UnexpectedException.class,
                () -> Rules.loadRules(path, List.of("default")), path);
            assertEquals("Some configuration files of enabled rules are not found, enabled rules: "
                + "[default{.yaml,.yml}]", e.getMessage(), path);
        }
        final Map<String, String> activation = new HashMap<>(CompiledRules.activation());
        activation.remove(RuleCatalog.OTEL_RULES.setting().key());
        try (MockedStatic<CompiledRules> registry = mockStatic(CompiledRules.class, CALLS_REAL_METHODS)) {
            registry.when(CompiledRules::activation).thenReturn(activation);
            final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Rules.loadRules("otel-rules", List.of("*")));
            assertEquals("The rule registry has no activation for the catalog otel-rules", e.getMessage());
        }
    }

    /**
     * The loaders take the files the build activated, each requiring its carried source: a compiled file the source
     * index lacks fails the boot, where filtering the compiled files through the index left it out without a word.
     */
    @Test
    void aCompiledFileTheSourceIndexLacksFailsTheBoot() {
        final List<String> lal = activated(RuleCatalog.LAL);
        final List<String> otel = activated(RuleCatalog.OTEL_RULES);
        try (MockedStatic<RuleSources> sources = mockStatic(RuleSources.class, CALLS_REAL_METHODS)) {
            if (!lal.isEmpty()) {
                sources.when(() -> RuleSources.find("lal", lal.get(0))).thenReturn(null);
                final IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> LALConfigs.load("lal", lal));
                assertTrue(e.getMessage().contains("carries no source of the compiled rule file lal/" + lal.get(0)),
                    e.getMessage());
            }
            if (!otel.isEmpty()) {
                sources.when(() -> RuleSources.find("otel-rules", otel.get(0))).thenReturn(null);
                final IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Rules.loadRules("otel-rules", List.of("*")));
                assertTrue(e.getMessage().contains("carries no source of the compiled rule file otel-rules/"
                    + otel.get(0)), e.getMessage());
            }
        }
    }

    private static List<String> activated(final RuleCatalog catalog) {
        return Arrays.stream(CompiledRules.activation().get(catalog.setting().key()).split(","))
                     .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toList());
    }

    /**
     * A selected file yielding no rule (log-mal-rules/placeholder.yaml holds comments only) is read and recorded for the
     * rule catalog, as upstream's loader records it before parsing, and yields none.
     */
    @Test
    void aSelectedMalFileWithoutRulesIsRecordedAndYieldsNone() throws Exception {
        assertEquals(List.of(), Rules.loadRules("log-mal-rules", List.of("placeholder")));
        assertTrue(StaticRuleRegistry.active().find("log-mal-rules", "placeholder").isPresent());
    }

    @Test
    void aMalRuleNameThatIsNoCompiledRuleOfTheCatalogFails() {
        final UnexpectedException e = assertThrows(UnexpectedException.class,
            () -> Rules.loadRules("otel-rules", List.of("vm", "no-such-rule")));
        assertEquals("Some configuration files of enabled rules are not found, enabled rules: "
            + "[no-such-rule{.yaml,.yml}]", e.getMessage());
    }

    @Test
    void aMalRuleIsSelectedAsUpstreamsPatternsSelectAFile() throws Exception {
        for (final String pattern : List.of("vm", "vm.yaml", "/vm", " vm ", "*")) {
            assertEquals(List.of("vm"), names(Rules.loadRules("telegraf-rules", List.of(pattern))), pattern);
        }
        assertTrue(names(Rules.loadRules("otel-rules", List.of("banyandb/*"))).contains("banyandb/banyandb-instance"));
        assertThrows(UnexpectedException.class, () -> Rules.loadRules("otel-rules", List.of("banyandb")),
            "a directory's name selects none of its files");
        assertThrows(UnexpectedException.class, () -> Rules.loadRules("telegraf-rules", List.of("vm.yml")));
    }

    @Test
    void aLalPathNoRulesWereCompiledFromFailsAndABlankOneIsRefused() throws Exception {
        assertThrows(ModuleStartException.class, () -> LALConfigs.load("no-such-lal", List.of("default")));
        final IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> LALConfigs.load(" ", List.of("default")));
        assertEquals("path cannot be blank", blank.getMessage());
        assertFalse(LALConfigs.load("lal", List.of("default")).isEmpty());
    }

    /**
     * The files come back in the order one upstream load of them all returned at build time (the registry's), the
     * order their rules run on a log, whatever order the selection lists them in.
     */
    @Test
    void theLalFilesComeBackInTheOrderTheBuildRecorded() throws Exception {
        final List<String> recorded = CompiledRules.lalFiles().stream()
            .map(f -> f.getName().substring(0, f.getName().lastIndexOf('.'))).collect(Collectors.toList());
        assertTrue(recorded.size() > 1, "the image compiles more than one LAL file");
        final List<String> reversed = new ArrayList<>(recorded);
        Collections.reverse(reversed);
        final List<LALConfigs> loaded = LALConfigs.load("lal", reversed);
        assertEquals(recorded.size(), loaded.size());
        for (int i = 0; i < loaded.size(); i++) {
            assertEquals(CompiledRules.lalFiles().get(i).getConfigs(), loaded.get(i), "LAL file " + recorded.get(i));
        }
    }

    @Test
    void aTalPathNoRulesWereCompiledFromFailsAndABlankOneIsRefused() throws Exception {
        assertThrows(ModuleStartException.class, () -> TALConfigs.load("no-such-tal", List.of("otlp-general")));
        assertThrows(ModuleStartException.class,
            () -> TALConfigs.loadInOrder("no-such-tal", List.of("native-rpc"), null));
        final IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
            () -> TALConfigs.load(" ", List.of("otlp-general")));
        assertEquals("path cannot be blank", blank.getMessage());
        assertFalse(TALConfigs.load("tal", List.of("otlp-general")).isEmpty());

        assertEquals(Set.of(), TALConfigs.requiredModulesOf("no-such-tal", List.of("otlp-ai-evaluation")));
        assertEquals(Set.of("ai-evaluation"), TALConfigs.requiredModulesOf("tal", List.of("otlp-ai-evaluation")));
    }

    @Test
    void aZabbixPathNoRulesWereCompiledFromFailsAndABlankOneHasNone() throws Exception {
        assertThrows(ModuleStartException.class, () -> ZabbixConfigs.loadConfigs("no-such-zabbix", List.of("agent")));
        assertEquals(List.of(), ZabbixConfigs.loadConfigs("", List.of("agent")));
        assertFalse(ZabbixConfigs.loadConfigs("zabbix-rules", List.of("agent")).isEmpty());
    }

    private static String outcome(final UpstreamClasses upstream, final Class<?> replacement, final String method,
                                  final String path, final List<String> list) throws Exception {
        final Method call = upstream.of(replacement).getMethod(method, String.class, List.class);
        return outcome(() -> UpstreamClasses.invoke(call, path, list));
    }

    /**
     * @return what a call did: the size of what it returned, or the exception it threw (a NullPointerException by its
     *     type alone: its helpful message names the code that dereferenced), with its cause
     */
    private static String outcome(final Callable<?> call) {
        try {
            final Object result = call.call();
            return "returned " + (result instanceof Collection ? ((Collection<?>) result).size() : result);
        } catch (final Throwable e) {
            return thrown(e);
        }
    }

    private static String thrown(final Throwable e) {
        final String self = e instanceof NullPointerException ? e.getClass().getName()
            : e.getClass().getName() + ": " + e.getMessage();
        return e.getCause() == null ? self : self + " caused by " + thrown(e.getCause());
    }

    private static List<String> names(final List<Rule> rules) {
        return rules.stream().map(Rule::getName).collect(Collectors.toList());
    }
}
