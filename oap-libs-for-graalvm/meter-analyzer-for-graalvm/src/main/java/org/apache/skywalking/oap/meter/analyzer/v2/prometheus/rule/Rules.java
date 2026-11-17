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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraalVM replacement for upstream Rules.
 * Original: skywalking/oap-server/analyzer/meter-analyzer/src/main/java/.../v2/prometheus/rule/Rules.java
 * Repackaged into meter-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Returns the rules of a catalog from the generated {@link CompiledRules} registry, which
 * holds what upstream's loader read from each rule file at build time, instead of reading YAML through
 * ResourceUtils.getPath(); registers their inline layerDefinitions as upstream does.
 * Why: The distro ships no rule YAML; the rules are compiled at build time.
 */
public class Rules {
    private static final Logger LOG = LoggerFactory.getLogger(Rules.class);

    public static List<Rule> loadRules(final String path) throws IOException {
        return loadRules(path, Collections.emptyList());
    }

    public static List<Rule> loadRules(final String path, List<String> enabledRules) throws IOException {
        if (enabledRules == null || enabledRules.isEmpty()) {
            return Collections.emptyList();
        }

        final List<MalFile> files = CompiledRules.malFiles();
        if (files.stream().noneMatch(f -> f.getCatalog().equals(path))) {
            throw new IOException("No pre-compiled MAL rules under " + path + "; the catalogs compiled into this image "
                + "are " + files.stream().map(MalFile::getCatalog).distinct().collect(Collectors.toList()));
        }
        final List<Rule> allRules = files.stream()
            .filter(f -> f.getCatalog().equals(path))
            .map(MalFile::getRule)
            .collect(Collectors.toList());

        // Normalize enabled rules: trim, remove leading "/", remove extension
        Set<String> normalizedEnabled = enabledRules.stream()
            .map(String::trim)
            .map(r -> r.startsWith("/") ? r.substring(1) : r)
            .map(r -> {
                if (r.endsWith(".yaml")) {
                    return r.substring(0, r.length() - 5);
                } else if (r.endsWith(".yml")) {
                    return r.substring(0, r.length() - 4);
                }
                return r;
            })
            .collect(Collectors.toSet());

        List<Rule> result = allRules.stream()
            .filter(r -> matchesAny(r.getName(), normalizedEnabled))
            .collect(Collectors.toList());
        result.forEach(Rules::registerInlineLayers);

        LOG.info("Loaded {} pre-compiled rules from {} (filtered from {} available, enabled: {})",
            result.size(), path, allRules.size(), normalizedEnabled);
        return result;
    }

    /**
     * Check if a rule name matches any of the enabled patterns.
     * Supports glob patterns: {@code rabbitmq/*} matches {@code rabbitmq/rabbitmq-cluster}.
     * A pattern ending with {@code /*} matches any rule whose name starts with the prefix before {@code /*}.
     * Patterns without wildcards require exact match.
     */
    private static boolean matchesAny(final String ruleName, final Set<String> patterns) {
        for (final String pattern : patterns) {
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (ruleName.startsWith(prefix)) {
                    return true;
                }
            } else if (pattern.equals(ruleName)) {
                return true;
            }
        }
        return false;
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
