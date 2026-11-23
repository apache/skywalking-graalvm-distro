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

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.query.type.Service;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.HierarchyRules;

import static java.util.stream.Collectors.toMap;

/**
 * Same-FQCN replacement of upstream HierarchyDefinitionService.
 *
 * <p>Takes the definition upstream reads from {@code hierarchy-definition.yml} from the generated
 * {@link CompiledRules} registry, with each matching rule's class the precompiler compiled (a
 * {@code BiFunction<Service, Service, Boolean>}), instead of parsing the YAML and compiling the rules at runtime.
 * Upstream's {@code HierarchyRuleProvider} SPI and the constructor taking one are left out on purpose: the image
 * compiles nothing at run time.
 */
@Slf4j
public class HierarchyDefinitionService implements org.apache.skywalking.oap.server.library.module.Service {

    @Getter
    private final Map<String, Map<String, MatchingRule>> hierarchyDefinition;
    @Getter
    private Map<String, Integer> layerLevels;
    private Map<String, MatchingRule> matchingRules;

    public HierarchyDefinitionService(final CoreModuleConfig moduleConfig) {
        this.hierarchyDefinition = new HashMap<>();
        this.layerLevels = new HashMap<>();
        if (moduleConfig.isEnableHierarchy()) {
            this.init();
            this.checkLayers();
        }
    }

    private void init() {
        final HierarchyRules definition = CompiledRules.hierarchy();
        this.layerLevels = definition.getLayerLevels();
        this.matchingRules = definition.getAutoMatchingRules().entrySet().stream().map(entry -> {
            final Supplier<BiFunction<Service, Service, Boolean>> matcher =
                definition.getMatchers().get(entry.getKey());
            if (matcher == null) {
                throw new IllegalStateException(
                    "Pre-compiled hierarchy matching rule not found: " + entry.getKey()
                        + ". Available: " + definition.getMatchers().keySet());
            }
            final MatchingRule matchingRule = new MatchingRule(entry.getKey(), entry.getValue(), matcher.get());
            return Map.entry(entry.getKey(), matchingRule);
        }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        definition.getHierarchy().forEach((layer, lowerLayers) -> {
            final Map<String, MatchingRule> rules = new HashMap<>();
            lowerLayers.forEach((lowerLayer, ruleName) -> {
                rules.put(lowerLayer, this.matchingRules.get(ruleName));
            });
            this.hierarchyDefinition.put(layer, rules);
        });
    }

    private void checkLayers() {
        this.layerLevels.keySet().forEach(layer -> {
            if (Layer.nameOf(layer).equals(Layer.UNDEFINED)) {
                throw new IllegalArgumentException(
                    "hierarchy-definition.yml " + layer + " is not a valid layer name.");
            }
        });
        this.hierarchyDefinition.forEach((layer, lowerLayers) -> {
            final Integer layerLevel = this.layerLevels.get(layer);
            if (this.layerLevels.get(layer) == null) {
                throw new IllegalArgumentException(
                    "hierarchy-definition.yml  layer-levels: " + layer + " is not defined");
            }

            for (final String lowerLayer : lowerLayers.keySet()) {
                final Integer lowerLayerLevel = this.layerLevels.get(lowerLayer);
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

        public MatchingRule(final String name, final String expression,
                            final BiFunction<Service, Service, Boolean> matcher) {
            this.name = name;
            this.expression = expression;
            this.matcher = matcher;
        }

        /**
         * Evaluates the compiled matching rule against two services.
         * If the expression throws at runtime (e.g., NPE from null shortName),
         * the exception propagates to the caller
         * ({@link org.apache.skywalking.oap.server.core.hierarchy.HierarchyService}).
         */
        public boolean match(final Service upper, final Service lower) {
            if (log.isDebugEnabled()) {
                log.debug("[Hierarchy] rule={}, class={}, upper=[{}, {}], lower=[{}, {}]",
                    name, matcher.getClass().getName(),
                    upper.getName(), upper.getShortName(),
                    lower.getName(), lower.getShortName());
            }
            return matcher.apply(upper, lower);
        }
    }
}
