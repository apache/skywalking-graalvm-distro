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

package org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.RuleSources;
import org.apache.skywalking.oap.server.library.module.ModuleManager;

/**
 * GraalVM replacement for upstream Rules.
 * Original: skywalking/oap-server/analyzer/meter-analyzer/src/main/java/.../v2/prometheus/rule/Rules.java
 * Repackaged into meter-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: the rule files of a catalog are the ones the build activated (every staged file, one yielding no rule too),
 * each by its carried source ({@link RuleSources}, which a missing one fails), instead of the files
 * ResourceUtils.getPath() walks: upstream's enabled-rule patterns select among their paths as among the walked files, the selected files' sources go through upstream's RuleSetMerger, which
 * records them for the rule catalog, and the rules are what the generated {@link CompiledRules} registry holds for
 * them, what upstream's loader read from each at build time; their inline layerDefinitions register as upstream does.
 * Why: The distro ships no rule YAML; the rules are compiled at build time.
 */
public class Rules {

    public static List<Rule> loadRules(final String path, List<String> enabledRules) throws IOException {
        return loadInternal(path, enabledRules, null, true);
    }

    public static List<Rule> loadRules(final String path, List<String> enabledRules,
                                       final ModuleManager manager) throws IOException {
        return loadInternal(path, enabledRules, manager, false);
    }

    private static List<Rule> loadInternal(final String path, final List<String> enabledRules,
                                           final ModuleManager manager, final boolean useInstalledManager)
        throws IOException {
        // Upstream's order: the directory resolved on the class path first (ResourceUtils.getPath throws
        // NullPointerException for a null path or one no directory answers; a catalog the overlay emptied exists),
        // then the list streamed (a null list throws too), an empty one selecting nothing.
        if (!RuleCatalog.exists(path)) {
            throw new NullPointerException();
        }
        if (enabledRules.isEmpty()) {
            return Collections.emptyList();
        }
        // Upstream's patterns: a leading '/' dropped, an extension-less name matching either YAML extension.
        final Map<String, Boolean> formedEnabledRules = enabledRules
            .stream()
            .map(rule -> {
                rule = rule.trim();
                if (rule.startsWith("/")) {
                    rule = rule.substring(1);
                }
                if (!rule.endsWith(".yaml") && !rule.endsWith(".yml")) {
                    return rule + "{.yaml,.yml}";
                }
                return rule;
            })
            .collect(Collectors.toMap(rule -> rule, $ -> false));

        // Matched among every file of the catalog the build activated (each staged one, a file yielding no rule too), as
        // upstream walks them, each by the path of its carried source, which it requires
        final List<String> selected = new ArrayList<>();
        // The distro's catalog boundary, shared with its LALConfigs, TALConfigs and ZabbixConfigs: another loader's
        // directory holds no MAL file (upstream would bind that loader's file as MAL and fail on its YAML)
        final RuleCatalog catalog = RuleCatalog.ofDirectory(path).filter(RuleCatalog.malRules()::contains).orElse(null);
        final String activated = catalog == null ? "" : CompiledRules.activation().get(catalog.setting().key());
        for (final RuleSources.Source file : RuleSources.carried(path, activated)) {
            final Path relativePath = Path.of(file.getPath());
            final boolean matched = formedEnabledRules.keySet().stream().anyMatch(rule -> {
                final boolean matches = FileSystems.getDefault().getPathMatcher("glob:" + rule).matches(relativePath);
                if (matches) {
                    formedEnabledRules.put(rule, true);
                }
                return matches;
            });
            if (matched) {
                selected.add(file.getName());
            }
        }
        if (formedEnabledRules.containsValue(false)) {
            final List<String> rulesNotFound = formedEnabledRules.keySet().stream()
                .filter(rule -> !formedEnabledRules.get(rule))
                .collect(Collectors.toList());
            throw new UnexpectedException("Some configuration files of enabled rules are not found, enabled rules: "
                + rulesNotFound);
        }
        // Every selected file goes through RuleSetMerger, as upstream's loader passes it before parsing; one that yields
        // no rule returns none
        RuleSources.merge(path, selected, useInstalledManager, manager);
        final List<Rule> result = CompiledRules.malFiles().stream()
            .filter(file -> file.getCatalog().equals(path) && selected.contains(file.getRule().getName()))
            .map(MalFile::getRule).collect(Collectors.toList());
        result.forEach(Rules::registerInlineLayers);
        return result;
    }

    // Same registration and message as upstream's registerInlineLayers.
    private static void registerInlineLayers(final Rule rule) {
        final List<LayerDefinition> defs = rule.getLayerDefinitions();
        if (defs == null || defs.isEmpty()) {
            return;
        }
        for (final LayerDefinition def : defs) {
            try {
                def.register();
            } catch (RuntimeException e) {
                throw new UnexpectedException(
                    "MAL rule " + rule.getName() + " layerDefinitions entry rejected: " + def, e);
            }
        }
    }
}
