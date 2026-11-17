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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.library.util.CollectionUtils.isEmpty;

/**
 * GraalVM replacement for upstream TALConfigs.
 * Original: skywalking/oap-server/analyzer/trace-analyzer/src/main/java/.../v2/provider/TALConfigs.java
 * Repackaged into trace-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Loads the rule files from the JSON the precompiler serialized (META-INF/config-data/tal.json)
 * instead of reading tal/*.yaml from disk through RuleSetMerger; requiredModulesOf reads the same JSON.
 * Why: The distro ships no tal/ directory; its rules are compiled at build time. RuleSetMerger only overlays
 * runtime rules, and upstream's Catalog has no TAL entry, so nothing is lost. lineNo comes from the JSON;
 * sourceName/sourcePath/metricPrefix are re-stamped, files whose requiredModules are not all loaded skipped and
 * inline layers registered exactly as upstream parse() does, because SpanFilterListener builds the DslSourceRef the
 * pre-compiled class is looked up by from them.
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

    private static final String CONFIG_DATA = "META-INF/config-data/tal.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void stampSource(final TALConfig config, final String fileName) {
        final String canonical = fileName.endsWith(".yaml") || fileName.endsWith(".yml")
            ? fileName : fileName + ".yaml";
        config.setSourceName(canonical);
        config.setSourcePath(TAL_CATALOG + canonical);
    }

    public static List<TALConfigs> load(final String path, final List<String> files) throws Exception {
        return loadInternal(path, files, null, false);
    }

    public static List<TALConfigs> load(final String path, final List<String> files,
                                        final ModuleManager manager) throws Exception {
        return loadInternal(path, files, manager, false);
    }

    public static List<TALConfigs> loadInOrder(final String path, final List<String> files,
                                               final ModuleManager manager) throws Exception {
        return loadInternal(path, files, manager, true);
    }

    private static List<TALConfigs> loadInternal(final String path, final List<String> files,
                                                 final ModuleManager manager,
                                                 final boolean inOrder) throws Exception {
        if (isEmpty(files)) {
            return Collections.emptyList();
        }
        // Keyed by the rule file's name with its extension, which stampSource needs.
        final Map<String, TALConfigs> all = readPrecompiled();
        final Map<String, Map.Entry<String, TALConfigs>> byStem = new HashMap<>();
        for (final Map.Entry<String, TALConfigs> e : all.entrySet()) {
            if (files.contains(getNameWithoutExtension(e.getKey()))) {
                byStem.put(getNameWithoutExtension(e.getKey()), e);
            }
        }
        for (final String name : files) {
            if (!byStem.containsKey(name)) {
                if (inOrder) {
                    throw new ModuleStartException(
                        "TAL rule file " + path + "/" + name + ".yaml is listed but does not exist");
                }
                log.warn("talFiles names '{}', but no {}/{}.yaml was pre-compiled", name, path, name);
            }
        }
        // The order rules are installed in decides which runs first on a span (first-wins tags and alias), so
        // the files come in upstream's order: by stem through the HashMap RuleSetMerger.merge returns, or as
        // listed for loadInOrder.
        final List<Map.Entry<String, TALConfigs>> entries = new ArrayList<>(new HashMap<>(byStem).values());
        if (inOrder) {
            entries.sort(Comparator.comparingInt(e -> files.indexOf(getNameWithoutExtension(e.getKey()))));
        }
        final List<TALConfigs> out = new ArrayList<>();
        for (final Map.Entry<String, TALConfigs> e : entries) {
            final TALConfigs configs = e.getValue();
            if (configs == null || configs.getRules() == null) {
                continue;
            }
            if (!configs.requiredModulesLoaded(manager)) {
                log.info("TAL rule file {} skipped: it requires modules {} and not all are loaded",
                    getNameWithoutExtension(e.getKey()), configs.getRequiredModules());
                continue;
            }
            registerInlineLayers(getNameWithoutExtension(e.getKey()), configs);
            for (final TALConfig config : configs.getRules()) {
                stampSource(config, e.getKey());
                config.setMetricPrefix(configs.getMetricPrefix());
            }
            out.add(configs);
        }
        log.info("Loaded {} pre-compiled TAL rule file(s) from {} (filtered from {} available)",
            out.size(), path, all.size());
        return out;
    }

    public static Set<String> requiredModulesOf(final String path, final List<String> files) {
        final Set<String> modules = new LinkedHashSet<>();
        if (isEmpty(files)) {
            return modules;
        }
        try {
            final Map<String, TALConfigs> all = readPrecompiled();
            for (final String name : files) {
                for (final Map.Entry<String, TALConfigs> e : all.entrySet()) {
                    if (name.equals(getNameWithoutExtension(e.getKey())) && e.getValue() != null
                        && e.getValue().getRequiredModules() != null) {
                        modules.addAll(e.getValue().getRequiredModules());
                    }
                }
            }
        } catch (final IOException | ModuleStartException e) {
            log.debug("TAL config data not read for the requiredModules of {}", files, e);
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

    private static Map<String, TALConfigs> readPrecompiled() throws IOException, ModuleStartException {
        try (InputStream is = TALConfigs.class.getClassLoader().getResourceAsStream(CONFIG_DATA)) {
            if (is == null) {
                throw new ModuleStartException(
                    "Pre-compiled TAL config data not found: " + CONFIG_DATA + ". Ensure the precompiler has been run.");
            }
            return MAPPER.readValue(is, new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
        }
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
