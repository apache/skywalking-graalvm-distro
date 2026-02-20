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

import groovy.lang.Closure;
import java.io.FileNotFoundException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 * <p>Replaces {@code GroovyShell.evaluate()} in {@code MatchingRule} with
 * pre-built Java-backed {@link Closure} objects. Eliminates runtime Groovy
 * compilation, which is not available in GraalVM native image.
 *
 * <p>The 4 matching rules from {@code hierarchy-definition.yml} are implemented
 * as anonymous {@code Closure<Boolean>} subclasses in {@link #RULE_REGISTRY}.
 * Unknown rule names fail fast at startup.
 */
@Slf4j
public class HierarchyDefinitionService implements org.apache.skywalking.oap.server.library.module.Service {

    /**
     * Pre-built matching rule closures keyed by rule name from hierarchy-definition.yml.
     * Each closure takes two Service arguments (upper, lower) and returns Boolean.
     */
    private static final Map<String, Closure<Boolean>> RULE_REGISTRY;

    static {
        RULE_REGISTRY = new HashMap<>();

        // name: "{ (u, l) -> u.name == l.name }"
        RULE_REGISTRY.put("name", new Closure<Boolean>(null) {
            public Boolean doCall(final Service u, final Service l) {
                return Objects.equals(u.getName(), l.getName());
            }
        });

        // short-name: "{ (u, l) -> u.shortName == l.shortName }"
        RULE_REGISTRY.put("short-name", new Closure<Boolean>(null) {
            public Boolean doCall(final Service u, final Service l) {
                return Objects.equals(u.getShortName(), l.getShortName());
            }
        });

        // lower-short-name-remove-ns:
        // "{ (u, l) -> { if(l.shortName.lastIndexOf('.') > 0)
        //     return u.shortName == l.shortName.substring(0, l.shortName.lastIndexOf('.'));
        //     return false; } }"
        RULE_REGISTRY.put("lower-short-name-remove-ns", new Closure<Boolean>(null) {
            public Boolean doCall(final Service u, final Service l) {
                int dot = l.getShortName().lastIndexOf('.');
                if (dot > 0) {
                    return Objects.equals(
                        u.getShortName(),
                        l.getShortName().substring(0, dot));
                }
                return false;
            }
        });

        // lower-short-name-with-fqdn:
        // "{ (u, l) -> { if(u.shortName.lastIndexOf(':') > 0)
        //     return u.shortName.substring(0, u.shortName.lastIndexOf(':'))
        //         == l.shortName.concat('.svc.cluster.local');
        //     return false; } }"
        RULE_REGISTRY.put("lower-short-name-with-fqdn", new Closure<Boolean>(null) {
            public Boolean doCall(final Service u, final Service l) {
                int colon = u.getShortName().lastIndexOf(':');
                if (colon > 0) {
                    return Objects.equals(
                        u.getShortName().substring(0, colon),
                        l.getShortName() + ".svc.cluster.local");
                }
                return false;
            }
        });
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
        private final Closure<Boolean> closure;

        public MatchingRule(final String name, final String expression) {
            this.name = name;
            this.expression = expression;
            this.closure = RULE_REGISTRY.get(name);
            if (this.closure == null) {
                throw new IllegalArgumentException(
                    "Unknown hierarchy matching rule: " + name
                        + ". Known rules: " + RULE_REGISTRY.keySet());
            }
        }
    }
}
