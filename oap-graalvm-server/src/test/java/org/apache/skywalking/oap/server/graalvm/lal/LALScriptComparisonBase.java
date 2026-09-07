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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
     * Load the pre-compiled LalExpression for a rule by the same source coordinates the runtime
     * DSL replacement uses ({@code sourcePath:lineNo} from lal-v2-rules.txt), falling back to
     * the rule name. Rule names repeat across files (network-profiling-slow-trace), so the
     * coordinates are what disambiguate.
     */
    protected static LalExpression loadPrecompiled(final LALConfig rule) {
        final Map<String, String> manifest = loadManifest();
        String className = manifest.get(rule.getSourcePath() + ":" + rule.getLineNo());
        if (className == null) {
            className = manifest.get("name:" + rule.getName());
        }
        return instantiate(className, rule.getName());
    }

    protected static LalExpression loadPrecompiled(final String ruleName) {
        return instantiate(loadManifest().get("name:" + ruleName), ruleName);
    }

    private static LalExpression instantiate(final String className, final String ruleName) {
        assertNotNull(className, "Pre-compiled LAL expression not found for rule: " + ruleName);
        try {
            final Class<?> exprClass = Class.forName(className);
            return (LalExpression) exprClass.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            throw new AssertionError(
                "Failed to load pre-compiled LAL expression: " + className, e);
        }
    }

    // ── Manifest loading ──

    /** Keys: {@code <sourcePath>:<line>} and {@code name:<ruleName>} (first occurrence) -> FQCN. */
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
                    .getResourceAsStream("META-INF/lal-v2-rules.txt")) {
                if (is == null) {
                    throw new AssertionError(
                        "Manifest META-INF/lal-v2-rules.txt not found");
                }
                final Properties props = new Properties();
                props.load(is);
                for (int i = 0; ; i++) {
                    final String className = props.getProperty("rule." + i + ".class");
                    if (className == null) {
                        break;
                    }
                    map.put(props.getProperty("rule." + i + ".source") + ":"
                        + props.getProperty("rule." + i + ".line"), className);
                    map.putIfAbsent("name:" + props.getProperty("rule." + i + ".name"), className);
                }
            } catch (final Exception e) {
                throw new AssertionError("Failed to load LAL manifest", e);
            }
            MANIFEST = map;
            return map;
        }
    }
}
