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

package org.apache.skywalking.oap.server.graalvm.lal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for LAL v2 pre-compilation tests.
 *
 * <p>Verifies that pre-compiled LAL classes from the build-time manifest
 * can be loaded and instantiated. With v2, both fresh compilation and
 * pre-compilation use the same ANTLR4+Javassist engine, so the main
 * verification is that the precompiler correctly captured all classes.
 */
abstract class LALScriptComparisonBase {
    private static volatile Map<String, String> MANIFEST;

    /**
     * Load LAL rules from a YAML file under the lal/ resource directory.
     */
    protected static List<LALConfig> loadLALRules(final String yamlFileName)
            throws Exception {
        final List<String> fileNames = singletonList(
            yamlFileName.replace(".yaml", "").replace(".yml", ""));
        final List<LALConfigs> configs = LALConfigs.load("lal", fileNames);
        final List<LALConfig> rules = new ArrayList<>();
        for (final LALConfigs c : configs) {
            rules.addAll(c.getRules());
        }
        return rules;
    }

    /**
     * Load pre-compiled LalExpression by rule name from manifest.
     */
    protected static LalExpression loadPrecompiled(final String ruleName) {
        final Map<String, String> manifest = loadManifest();
        String className = null;

        // Search for class matching sanitized rule name
        final String sanitizedName = sanitizeName(ruleName);
        for (final Map.Entry<String, String> entry : manifest.entrySet()) {
            final String simpleName = entry.getKey();
            if (simpleName.contains(sanitizedName)) {
                className = entry.getValue();
                break;
            }
        }

        assertNotNull(className,
            "Pre-compiled LAL expression not found for rule: " + ruleName
                + " (sanitized: " + sanitizedName + ")");
        try {
            final Class<?> exprClass = Class.forName(className);
            return (LalExpression) exprClass.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            throw new AssertionError(
                "Failed to load pre-compiled LAL expression: " + className, e);
        }
    }

    // ── Manifest loading ──

    protected static Map<String, String> loadManifest() {
        if (MANIFEST != null) {
            return MANIFEST;
        }
        synchronized (LALScriptComparisonBase.class) {
            if (MANIFEST != null) {
                return MANIFEST;
            }
            final Map<String, String> map = new HashMap<>();
            try (InputStream is = LALScriptComparisonBase.class.getClassLoader()
                    .getResourceAsStream("META-INF/lal-v2-classes.txt")) {
                if (is == null) {
                    throw new AssertionError(
                        "Manifest META-INF/lal-v2-classes.txt not found");
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        // Each line is a FQCN like org.apache.skywalking...LalExpr_default
                        final String simpleName = line.substring(
                            line.lastIndexOf('.') + 1);
                        map.put(simpleName, line);
                    }
                }
            } catch (final Exception e) {
                throw new AssertionError("Failed to load LAL manifest", e);
            }
            MANIFEST = map;
            return map;
        }
    }

    /**
     * Sanitize a name for use in class naming (matches upstream LALCodegenHelper).
     */
    static String sanitizeName(final String name) {
        if (name == null || name.isEmpty()) {
            return "Generated";
        }
        final StringBuilder sb = new StringBuilder(name.length() + 1);
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            sb.append('_');
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }
}
