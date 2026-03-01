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

package org.apache.skywalking.oap.meter.analyzer.dsl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Same-FQCN replacement for upstream MAL DSL.
 * Loads transpiled MalExpression classes from mal-expressions.txt manifest
 * instead of Groovy DelegatingScript classes — no Groovy runtime needed.
 *
 * <p>Handles the <b>combination pattern</b>: multiple YAML rule files from different data sources
 * (otel, telegraf, zabbix) may define metrics with the same name (e.g., {@code meter_vm_cpu_load1}).
 * The precompiler assigns deterministic suffixes ({@code _1}, {@code _2}, etc.) and records
 * expression SHA-256 hashes in {@code mal-groovy-expression-hashes.txt}. At runtime, the correct
 * variant is resolved by matching the expression hash.
 */
@Slf4j
public final class DSL {
    private static final String MANIFEST_PATH = "META-INF/mal-expressions.txt";
    private static final String HASHES_PATH = "META-INF/mal-groovy-expression-hashes.txt";
    private static volatile Map<String, String> SCRIPT_MAP;
    private static volatile Map<String, String> HASH_MAP;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    public static Expression parse(final String metricName, final String expression) {
        if (metricName == null) {
            throw new UnsupportedOperationException(
                "Init expressions (metricName=null) are not supported in GraalVM mode. "
                    + "All init expressions must be pre-compiled at build time.");
        }

        Map<String, String> scriptMap = loadManifest();
        Map<String, String> hashMap = loadHashes();
        String resolvedName = resolveMetricName(metricName, expression, scriptMap, hashMap);

        String className = scriptMap.get(resolvedName);
        if (className == null) {
            throw new IllegalStateException(
                "Transpiled MAL expression not found for metric: " + metricName
                    + " (resolved: " + resolvedName + ")"
                    + ". Available: " + scriptMap.size() + " expressions");
        }

        try {
            Class<?> exprClass = Class.forName(className);
            MalExpression malExpr = (MalExpression) exprClass.getDeclaredConstructor().newInstance();
            int count = LOADED_COUNT.incrementAndGet();
            if (!resolvedName.equals(metricName)) {
                log.debug("Loaded transpiled MAL expression [{}/{}]: {} (resolved from {} via hash)",
                    count, scriptMap.size(), resolvedName, metricName);
            } else {
                log.debug("Loaded transpiled MAL expression [{}/{}]: {}", count, scriptMap.size(), metricName);
            }
            return new Expression(metricName, expression, malExpr);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Transpiled MAL expression class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate transpiled MAL expression: " + className, e);
        }
    }

    /**
     * Resolve the correct suffixed metric name for combination patterns.
     *
     * <p>When multiple rule files define the same metric name (e.g., otel and telegraf both define
     * {@code meter_vm_cpu_load1}), the precompiler creates suffixed variants
     * ({@code meter_vm_cpu_load1}, {@code meter_vm_cpu_load1_1}, {@code meter_vm_cpu_load1_2}).
     * This method computes the SHA-256 of the expression and matches it against the recorded
     * hashes to find the correct variant.
     */
    private static String resolveMetricName(String metricName, String expression,
                                            Map<String, String> scriptMap,
                                            Map<String, String> hashMap) {
        // If no hashes available, fall back to direct lookup
        if (hashMap.isEmpty()) {
            return metricName;
        }

        String expressionHash = sha256(expression);

        // Check if the base name's hash matches
        String baseHash = hashMap.get(metricName);
        if (baseHash != null && baseHash.equals(expressionHash)) {
            return metricName;
        }

        // Try suffixed variants: _1, _2, _3, ...
        for (int i = 1; i <= 10; i++) {
            String suffixed = metricName + "_" + i;
            String suffixedHash = hashMap.get(suffixed);
            if (suffixedHash != null && suffixedHash.equals(expressionHash)) {
                return suffixed;
            }
            // Stop if no more suffixed variants exist
            if (!scriptMap.containsKey(suffixed)) {
                break;
            }
        }

        // No hash match found — fall back to base name (will work for non-combination metrics)
        if (scriptMap.containsKey(metricName)) {
            return metricName;
        }

        return metricName;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Map<String, String> loadManifest() {
        if (SCRIPT_MAP != null) {
            return SCRIPT_MAP;
        }
        synchronized (DSL.class) {
            if (SCRIPT_MAP != null) {
                return SCRIPT_MAP;
            }
            Map<String, String> map = new HashMap<>();
            try (InputStream is = DSL.class.getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
                if (is == null) {
                    log.warn("MAL expression manifest not found: {}", MANIFEST_PATH);
                    SCRIPT_MAP = map;
                    return map;
                }
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        // mal-expressions.txt format: one FQCN per line
                        // Class name convention: ...mal.MalExpr_<metricName>
                        String simpleName = line.substring(line.lastIndexOf('.') + 1);
                        if (simpleName.startsWith("MalExpr_")) {
                            String metric = simpleName.substring("MalExpr_".length());
                            map.put(metric, line);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load MAL expression manifest", e);
            }
            log.info("Loaded {} transpiled MAL expressions from manifest", map.size());
            SCRIPT_MAP = map;
            return map;
        }
    }

    private static Map<String, String> loadHashes() {
        if (HASH_MAP != null) {
            return HASH_MAP;
        }
        synchronized (DSL.class) {
            if (HASH_MAP != null) {
                return HASH_MAP;
            }
            Map<String, String> map = new HashMap<>();
            try (InputStream is = DSL.class.getClassLoader().getResourceAsStream(HASHES_PATH)) {
                if (is == null) {
                    log.warn("MAL expression hash manifest not found: {}", HASHES_PATH);
                    HASH_MAP = map;
                    return map;
                }
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            map.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load MAL expression hash manifest", e);
            }
            log.info("Loaded {} MAL expression hashes from manifest", map.size());
            HASH_MAP = map;
            return map;
        }
    }
}
