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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GraalVM replacement for upstream Rules.
 * Original: skywalking/oap-server/analyzer/meter-analyzer/src/main/java/.../v2/prometheus/rule/Rules.java
 * Repackaged into meter-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled rule data from JSON manifests
 * (META-INF/config-data/{path}.json) instead of filesystem YAML files via ResourceUtils.getPath().
 * Why: The distro intentionally excludes raw YAML config directories (otel-rules, envoy-metrics-rules, etc.)
 * — their Groovy/DSL expressions are pre-compiled at build time. Config data (metric prefixes, rule names)
 * is serialized as JSON by the precompiler for runtime wiring.
 */
public class Rules {
    private static final Logger LOG = LoggerFactory.getLogger(Rules.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static List<Rule> loadRules(final String path) throws IOException {
        return loadRules(path, Collections.emptyList());
    }

    public static List<Rule> loadRules(final String path, List<String> enabledRules) throws IOException {
        if (enabledRules == null || enabledRules.isEmpty()) {
            return Collections.emptyList();
        }

        LOG.info("Loading rules from pre-compiled distro ({})", path);

        String resourcePath = "META-INF/config-data/" + path + ".json";
        try (InputStream is = Rules.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException(
                    "Pre-compiled config data not found: " + resourcePath
                    + ". Ensure the precompiler has been run.");
            }

            List<Rule> allRules = MAPPER.readValue(is, new TypeReference<List<Rule>>() { });

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
                .filter(r -> normalizedEnabled.contains(r.getName()))
                .collect(Collectors.toList());

            LOG.info("Loaded {} pre-compiled rules from {} (filtered from {} available, enabled: {})",
                result.size(), path, allRules.size(), normalizedEnabled);
            return result;
        }
    }
}
