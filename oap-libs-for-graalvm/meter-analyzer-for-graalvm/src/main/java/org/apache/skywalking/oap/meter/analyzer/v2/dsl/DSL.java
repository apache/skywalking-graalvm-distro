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

package org.apache.skywalking.oap.meter.analyzer.v2.dsl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Same-FQCN replacement for upstream v2 MAL DSL.
 *
 * <p>Loads pre-compiled {@link MalExpression} classes from per-file configs
 * under {@code META-INF/mal-v2/} instead of compiling via MALClassGenerator
 * at runtime.
 *
 * <p>The per-file configs mirror the original YAML directory structure.
 * Each config file contains rule names, full expressions, filter info,
 * and compiled class FQCNs. The top-level {@code META-INF/mal-v2.manifest}
 * lists all config files.
 *
 * <p>At runtime, all per-file configs are loaded once and indexed by
 * expression text for O(1) lookup in {@link #parse(String, String, String)}.
 *
 * <p>Closure fields (TagFunction, ForEachFunction, etc.) are now self-wired
 * via companion classes generated at build time. The main class static
 * initializer instantiates each companion class directly — no external
 * LambdaMetafactory wiring is needed.
 */
@Slf4j
public final class DSL {
    private static final String MANIFEST_PATH = "META-INF/mal-v2.manifest";
    private static volatile Map<String, String> EXPRESSION_MAP;
    private static volatile Map<String, String> FILTER_MAP;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    public static Expression parse(final String metricName, final String expression) {
        return parse(metricName, expression, null);
    }

    public static Expression parse(final String metricName,
                                   final String expression,
                                   final String yamlSource) {
        loadManifests();
        final String className = EXPRESSION_MAP.get(expression);

        if (className == null) {
            throw new IllegalStateException(
                "Pre-compiled MAL expression not found for metric: " + metricName
                    + " (expression: " + expression + ")"
                    + ". Available: " + EXPRESSION_MAP.size() + " expressions");
        }

        try {
            final Class<?> exprClass = Class.forName(className);
            final MalExpression malExpr = (MalExpression) exprClass.getDeclaredConstructor().newInstance();
            final int count = LOADED_COUNT.incrementAndGet();
            log.debug("Loaded pre-compiled MAL expression [{}/{}]: {} -> {}",
                count, EXPRESSION_MAP.size(), metricName, className);
            return new Expression(metricName, expression, malExpr);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Pre-compiled MAL expression class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate pre-compiled MAL expression: " + className, e);
        }
    }

    /**
     * Look up a pre-compiled filter class by its literal expression text.
     * Called by {@link FilterExpression} to load filter classes from the
     * same per-file manifests.
     */
    static String getFilterClassName(final String filterLiteral) {
        loadManifests();
        return FILTER_MAP.get(filterLiteral);
    }

    static int getFilterMapSize() {
        loadManifests();
        return FILTER_MAP.size();
    }

    private static void loadManifests() {
        if (EXPRESSION_MAP != null) {
            return;
        }
        synchronized (DSL.class) {
            if (EXPRESSION_MAP != null) {
                return;
            }
            final Map<String, String> exprMap = new HashMap<>();
            final Map<String, String> filterMap = new HashMap<>();
            final ClassLoader cl = DSL.class.getClassLoader();

            try (InputStream mis = cl.getResourceAsStream(MANIFEST_PATH)) {
                if (mis == null) {
                    log.warn("MAL v2 manifest not found: {}", MANIFEST_PATH);
                    EXPRESSION_MAP = exprMap;
                    FILTER_MAP = filterMap;
                    return;
                }
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(mis, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        loadPerFileConfig(cl, "META-INF/mal-v2/" + line, exprMap, filterMap);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load MAL v2 manifest", e);
            }
            log.info("Loaded {} pre-compiled MAL v2 expressions and {} filters from per-file configs",
                exprMap.size(), filterMap.size());
            FILTER_MAP = filterMap;
            EXPRESSION_MAP = exprMap;
        }
    }

    private static void loadPerFileConfig(final ClassLoader cl,
                                          final String configPath,
                                          final Map<String, String> exprMap,
                                          final Map<String, String> filterMap) {
        try (InputStream is = cl.getResourceAsStream(configPath)) {
            if (is == null) {
                log.warn("MAL v2 per-file config not found: {}", configPath);
                return;
            }
            final Properties props = new Properties();
            props.load(is);

            // Load filter
            final String filterLiteral = props.getProperty("filter", "");
            final String filterClass = props.getProperty("filter.class", "");
            if (!filterLiteral.isEmpty() && !filterClass.isEmpty()) {
                filterMap.putIfAbsent(filterLiteral, filterClass);
            }

            // Load rules
            for (int i = 0; ; i++) {
                final String exp = props.getProperty("rule." + i + ".exp");
                if (exp == null) {
                    break;
                }
                final String className = props.getProperty("rule." + i + ".class", "");
                if (!className.isEmpty()) {
                    exprMap.put(exp, className);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load MAL v2 per-file config: {}", configPath, e);
        }
    }
}
