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

package org.apache.skywalking.oap.server.core.config;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.query.type.Service;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.yaml.snakeyaml.Yaml;

import static java.util.stream.Collectors.toMap;

/**
 * Same-FQCN replacement of upstream HierarchyDefinitionService.
 *
 * <p>Loads pre-compiled hierarchy matching rule classes from the
 * {@code hierarchy-v2-rules.txt} manifest ({@code ruleName=FQCN}). These classes were
 * compiled at build time by the precompiler using the v2 ANTLR4 + Javassist engine, and
 * each implements {@code BiFunction<Service, Service, Boolean>}.
 */
@Slf4j
public class HierarchyDefinitionService implements org.apache.skywalking.oap.server.library.module.Service {

    private static final String MANIFEST_PATH = "META-INF/hierarchy-v2-rules.txt";
    private static volatile Map<String, BiFunction<Service, Service, Boolean>> RULES;

    @SuppressWarnings("unchecked")
    private static Map<String, BiFunction<Service, Service, Boolean>> loadPrecompiledRules() {
        if (RULES != null) {
            return RULES;
        }
        synchronized (HierarchyDefinitionService.class) {
            if (RULES != null) {
                return RULES;
            }
            final Map<String, BiFunction<Service, Service, Boolean>> rules = new HashMap<>();
            try (InputStream is = HierarchyDefinitionService.class.getClassLoader()
                    .getResourceAsStream(MANIFEST_PATH)) {
                if (is == null) {
                    log.warn("Hierarchy v2 manifest not found: {}", MANIFEST_PATH);
                    RULES = rules;
                    return rules;
                }
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        final int eq = line.indexOf('=');
                        if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                            continue;
                        }
                        final String ruleName = line.substring(0, eq);
                        final String fqcn = line.substring(eq + 1);
                        try {
                            rules.put(ruleName, (BiFunction<Service, Service, Boolean>) Class.forName(fqcn)
                                .getDeclaredConstructor().newInstance());
                            log.debug("Loaded pre-compiled hierarchy rule: {} -> {}", ruleName, fqcn);
                        } catch (Exception e) {
                            log.warn("Failed to load hierarchy rule class: {}", fqcn, e);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load hierarchy v2 manifest", e);
            }
            RULES = rules;
            return rules;
        }
    }

    @Getter
    private final Map<String, Map<String, MatchingRule>> hierarchyDefinition;
    @Getter
    private Map<String, Integer> layerLevels;
    private Map<String, MatchingRule> matchingRules;

    public HierarchyDefinitionService(CoreModuleConfig moduleConfig) {
        this.hierarchyDefinition = new HashMap<>();
        this.layerLevels = new HashMap<>();
        if (moduleConfig.isEnableHierarchy()) {
            this.init();
            this.checkLayers();
        }
    }

    @SuppressWarnings("unchecked")
    private void init() {
        try {
            Reader applicationReader = ResourceUtils.read("hierarchy-definition.yml");
            Yaml yaml = new Yaml();
            Map<String, Map> config = yaml.loadAs(applicationReader, Map.class);
            Map<String, Map<String, String>> hierarchy = (Map<String, Map<String, String>>) config.get("hierarchy");
            Map<String, String> matchingRules = (Map<String, String>) config.get("auto-matching-rules");
            this.layerLevels = (Map<String, Integer>) config.get("layer-levels");
            this.matchingRules = matchingRules.entrySet().stream().map(entry -> {
                MatchingRule matchingRule = new MatchingRule(entry.getKey(), entry.getValue());
                return Map.entry(entry.getKey(), matchingRule);
            }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            hierarchy.forEach((layer, lowerLayers) -> {
                Map<String, MatchingRule> rules = new HashMap<>();
                lowerLayers.forEach((lowerLayer, ruleName) -> {
                    rules.put(lowerLayer, this.matchingRules.get(ruleName));
                });
                this.hierarchyDefinition.put(layer, rules);
            });
        } catch (FileNotFoundException e) {
            throw new UnexpectedException("hierarchy-definition.yml not found.", e);
        }
    }

    private void checkLayers() {
        this.layerLevels.keySet().forEach(layer -> {
            if (Layer.nameOf(layer).equals(Layer.UNDEFINED)) {
                throw new IllegalArgumentException(
                    "hierarchy-definition.yml " + layer + " is not a valid layer name.");
            }
        });
        this.hierarchyDefinition.forEach((layer, lowerLayers) -> {
            Integer layerLevel = this.layerLevels.get(layer);
            if (this.layerLevels.get(layer) == null) {
                throw new IllegalArgumentException(
                    "hierarchy-definition.yml  layer-levels: " + layer + " is not defined");
            }

            for (String lowerLayer : lowerLayers.keySet()) {
                Integer lowerLayerLevel = this.layerLevels.get(lowerLayer);
                if (lowerLayerLevel == null) {
                    throw new IllegalArgumentException(
                        "hierarchy-definition.yml  layer-levels: " + lowerLayer + " is not defined.");
                }
                if (layerLevel <= lowerLayerLevel) {
                    throw new IllegalArgumentException(
                        "hierarchy-definition.yml hierarchy: " + layer + " layer-level should be greater than " + lowerLayer + " layer-level.");
                }
            }
        });
    }

    @Getter
    public static class MatchingRule {
        private final String name;
        private final String expression;
        private final BiFunction<Service, Service, Boolean> matcher;

        public MatchingRule(final String name, final String expression) {
            this.name = name;
            this.expression = expression;
            final Map<String, BiFunction<Service, Service, Boolean>> rules = loadPrecompiledRules();
            this.matcher = rules.get(name);
            if (this.matcher == null) {
                throw new IllegalArgumentException(
                    "Pre-compiled hierarchy matching rule not found: " + name
                        + ". Available: " + rules.keySet());
            }
        }
    }
}
