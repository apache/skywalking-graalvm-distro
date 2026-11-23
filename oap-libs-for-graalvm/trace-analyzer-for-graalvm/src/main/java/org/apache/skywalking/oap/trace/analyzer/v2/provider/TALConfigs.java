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

package org.apache.skywalking.oap.trace.analyzer.v2.provider;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.RuleSources;
import org.apache.skywalking.oap.server.graalvm.rules.TalFile;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.library.util.CollectionUtils.isEmpty;
import static org.apache.skywalking.oap.server.library.util.StringUtil.isNotBlank;

/**
 * GraalVM replacement for upstream TALConfigs.
 * Original: skywalking/oap-server/analyzer/trace-analyzer/src/main/java/.../v2/provider/TALConfigs.java
 * Repackaged into trace-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Returns the rule files from the generated {@link CompiledRules} registry instead of reading tal/*.yaml from
 * disk. The registry holds what upstream's loader read from each file at build time (a file without rules too), the
 * coordinates it stamped included, and lists the files in the order upstream's loader returns the talFiles;
 * requiredModulesOf returns what upstream's own read of each file's requiredModules gave at build time. The files it
 * reads go through upstream's RuleSetMerger first, from their sources the image carries ({@link RuleSources}), as
 * upstream's loader passes them, which records them.
 * Why: The distro ships no tal/ directory; its rules are compiled at build time. What depends on the booted modules
 * stays here, as upstream's parse() does it: a file whose requiredModules are not all loaded is skipped, and the
 * others register their inline layers.
 */
@Data
@Slf4j
public class TALConfigs {
    private String metricPrefix;

    private List<TALConfig> rules;

    private List<LayerDefinition> layerDefinitions;

    private List<String> requiredModules;

    public boolean requiredModulesLoaded(final ModuleManager moduleManager) {
        if (requiredModules == null || moduleManager == null) {
            return true;
        }
        for (final String module : requiredModules) {
            if (!moduleManager.has(module)) {
                return false;
            }
        }
        return true;
    }

    // Must match upstream: TALClassGenerator strips this catalog prefix when naming classes.
    public static final String TAL_CATALOG = "tal/";

    public static void stampSource(final TALConfig config, final String fileName) {
        stampVerbatim(config, isYamlName(fileName) ? fileName : fileName + ".yaml");
    }

    private static boolean isYamlName(final String fileName) {
        final String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static void stampVerbatim(final TALConfig config, final String fileName) {
        config.setSourceName(fileName);
        config.setSourcePath(TAL_CATALOG + fileName);
    }

    public static List<TALConfigs> load(final String path, final List<String> files) throws Exception {
        return loadInternal(path, files, null, true, false);
    }

    public static List<TALConfigs> load(final String path, final List<String> files,
                                        final ModuleManager manager) throws Exception {
        return loadInternal(path, files, manager, false, false);
    }

    public static List<TALConfigs> loadInOrder(final String path, final List<String> files,
                                               final ModuleManager manager) throws Exception {
        return loadInternal(path, files, manager, false, true);
    }

    private static List<TALConfigs> loadInternal(final String path, final List<String> files,
                                                 final ModuleManager manager,
                                                 final boolean useInstalledManager,
                                                 final boolean inOrder) throws Exception {
        if (isEmpty(files)) {
            return Collections.emptyList();
        }
        checkArgument(isNotBlank(path), "path cannot be blank");
        // As upstream fails on a directory that does not exist; the image compiles tal/ only, so another holds no file
        if (!RuleCatalog.exists(path)) {
            throw new ModuleStartException("Failed to load TAL config rules",
                new FileNotFoundException("path not found: " + path));
        }
        final Map<String, TalFile> compiled = TAL_CATALOG.equals(path + "/") ? compiledFiles() : Map.of();
        for (final String name : files) {
            if (!compiled.containsKey(name)) {
                if (inOrder) {
                    throw new ModuleStartException(
                        "TAL rule file " + path + "/" + name + ".yaml is listed but does not exist");
                }
                log.warn("talFiles names '{}', but no {}/{}.yaml was pre-compiled", name, path, name);
            }
        }
        // The order the rules are installed in decides which runs first on a span (first-wins alias, last-wins tags):
        // the registry's, which the build took from upstream's loader, or as listed for loadInOrder.
        final List<Map.Entry<String, TalFile>> listed = new ArrayList<>();
        for (final Map.Entry<String, TalFile> file : compiled.entrySet()) {
            if (files.contains(file.getKey())) {
                listed.add(file);
            }
        }
        if (inOrder) {
            listed.sort(Comparator.comparingInt(file -> files.indexOf(file.getKey())));
        }
        // As upstream's loader passes every file it reads, before skipping one for its modules
        RuleSources.merge("tal", listed.stream().map(Map.Entry::getKey).collect(Collectors.toList()),
            useInstalledManager, manager);
        final List<TALConfigs> out = new ArrayList<>(listed.size());
        for (final Map.Entry<String, TalFile> file : listed) {
            final TALConfigs configs = file.getValue().getConfigs();
            if (!configs.requiredModulesLoaded(manager)) {
                log.info("TAL rule file {} skipped: it requires modules {} and not all are loaded",
                    file.getKey(), configs.getRequiredModules());
                continue;
            }
            // A file of layerDefinitions alone still declares them.
            registerInlineLayers(file.getKey(), configs);
            if (configs.getRules() != null) {
                out.add(configs);
            }
        }
        log.info("Loaded {} pre-compiled TAL rule file(s) from {} (filtered from {} available)",
            out.size(), path, compiled.size());
        return out;
    }

    public static Set<String> requiredModulesOf(final String path, final List<String> files) {
        final Set<String> modules = new LinkedHashSet<>();
        // Upstream reads no modules from a directory that does not exist.
        if (isEmpty(files) || !TAL_CATALOG.equals(path + "/")) {
            return modules;
        }
        final Map<String, TalFile> compiled = compiledFiles();
        // By name, the order upstream reads the files in.
        // What upstream's own read of each file's key gave at build time, not the modules its loader binds
        for (final String name : new TreeSet<>(files)) {
            final TalFile file = compiled.get(name);
            if (file != null) {
                modules.addAll(file.getRequiredModulesOf());
            }
        }
        return modules;
    }

    /**
     * Kept for API parity with upstream. Parsing rule text that was not pre-compiled would need runtime codegen to
     * be of any use, which the native image cannot do.
     */
    public static TALConfigs parse(final String ruleName, final byte[] bytes, final String sourceFileName) {
        return parse(ruleName, bytes, sourceFileName, null);
    }

    public static TALConfigs parse(final String ruleName, final byte[] bytes, final String sourceFileName,
                                   final ModuleManager manager) {
        throw new UnsupportedOperationException(
            "TAL rule " + ruleName + " (" + sourceFileName + ") was not pre-compiled; this distro runs bundled TAL "
                + "rules only");
    }

    /**
     * @return the compiled rule files by the name talFiles lists them under, in the registry's order
     */
    private static Map<String, TalFile> compiledFiles() {
        final Map<String, TalFile> compiled = new LinkedHashMap<>();
        for (final TalFile file : CompiledRules.talFiles()) {
            final TalFile other = compiled.put(getNameWithoutExtension(file.getFileName()), file);
            if (other != null) {
                throw new IllegalStateException("The compiled TAL rule files " + other.getFileName() + " and "
                    + file.getFileName() + " are both named " + getNameWithoutExtension(file.getFileName())
                    + "; rebuild the distro");
            }
        }
        return compiled;
    }

    private static void registerInlineLayers(final String ruleName, final TALConfigs configs) {
        final List<LayerDefinition> defs = configs.getLayerDefinitions();
        if (defs == null || defs.isEmpty()) {
            return;
        }
        for (final LayerDefinition def : defs) {
            try {
                def.register();
            } catch (RuntimeException e) {
                throw new UnexpectedException(
                    "TAL rule " + ruleName + " layerDefinitions entry rejected: " + def, e);
            }
        }
    }
}
