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

package org.apache.skywalking.oap.meter.analyzer.prometheus.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;

/**
 * GraalVM replacement for upstream Rules.
 * Original: skywalking/oap-server/analyzer/meter-analyzer/src/main/java/.../prometheus/rule/Rules.java
 * Repackaged into meter-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled rule data from JSON manifests
 * (META-INF/config-data/{path}.json) instead of filesystem YAML files via ResourceUtils.getPath() + Files.walk().
 * Why: The distro intentionally excludes raw YAML config directories — their Groovy expressions are
 * pre-compiled at build time. Rule data (metric prefixes, expressions, rule names) is serialized as JSON
 * by the precompiler for runtime wiring.
 */
@Slf4j
public class Rules {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<Rule> loadRules(final String path) throws IOException {
        return loadRules(path, Collections.emptyList());
    }

    public static List<Rule> loadRules(final String path, List<String> enabledRules) throws IOException {
        log.info("Loading rules from pre-compiled distro ({})", path);

        String resourcePath = "META-INF/config-data/" + path + ".json";
        try (InputStream is = Rules.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException(
                    "Pre-compiled config data not found: " + resourcePath
                    + ". Ensure the precompiler has been run.");
            }

            List<Rule> allRules = MAPPER.readValue(is, new TypeReference<List<Rule>>() { });

            // Apply glob matching on rule names — same logic as upstream
            Map<String, Boolean> formedEnabledRules = enabledRules.stream()
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
                .collect(Collectors.toMap(rule -> rule, $ -> false, (a, b) -> a));

            List<Rule> filtered = allRules.stream()
                .filter(rule -> {
                    // Match rule name (relative path without extension) against enabled patterns
                    // Add .yaml suffix for glob matching (same as upstream file-based matching)
                    Path rulePath = Path.of(rule.getName() + ".yaml");
                    return formedEnabledRules.keySet().stream().anyMatch(pattern -> {
                        PathMatcher matcher = FileSystems.getDefault()
                            .getPathMatcher("glob:" + pattern);
                        boolean matches = matcher.matches(rulePath);
                        if (matches) {
                            formedEnabledRules.put(pattern, true);
                        }
                        return matches;
                    });
                })
                .collect(Collectors.toList());

            if (formedEnabledRules.containsValue(false)) {
                List<String> rulesNotFound = formedEnabledRules.entrySet().stream()
                    .filter(e -> !e.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
                throw new UnexpectedException(
                    "Some configuration files of enabled rules are not found, enabled rules: "
                    + rulesNotFound);
            }

            log.info("Loaded {} pre-compiled rules from {} (filtered from {} available)",
                filtered.size(), path, allRules.size());
            return filtered;
        }
    }
}
