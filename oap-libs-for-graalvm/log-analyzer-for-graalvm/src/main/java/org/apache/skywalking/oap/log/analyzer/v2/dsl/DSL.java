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

package org.apache.skywalking.oap.log.analyzer.v2.dsl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.spec.filter.FilterSpec;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

/**
 * Same-FQCN replacement for upstream v2 LAL DSL.
 *
 * <p>Loads pre-compiled {@link LalExpression} classes from the
 * {@code lal-v2-classes.txt} manifest instead of compiling via
 * LALClassGenerator at runtime.
 *
 * <p>The class name is derived from the rule name: each LAL rule is compiled
 * with {@code classNameHint = ruleName}, producing a class like
 * {@code LalExpr_<sanitizedRuleName>}.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DSL {
    private static final String MANIFEST_PATH = "META-INF/lal-v2-classes.txt";
    private static final String PACKAGE_PREFIX =
        "org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.";
    private static volatile Map<String, String> CLASS_MAP;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    private final String ruleName;
    private final LalExpression expression;
    private final FilterSpec filterSpec;

    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl) throws ModuleStartException {
        return of(moduleManager, config, dsl, null, null, "unknown", null);
    }

    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl,
                         final Class<?> inputType,
                         final Class<?> outputType,
                         final String ruleName) throws ModuleStartException {
        return of(moduleManager, config, dsl, inputType, outputType, ruleName, null);
    }

    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl,
                         final Class<?> inputType,
                         final Class<?> outputType,
                         final String ruleName,
                         final String yamlSource) throws ModuleStartException {
        final Map<String, String> classMap = loadManifest();

        // Try to find by sanitized ruleName (matching precompiler's classNameHint).
        // Class names follow pattern: {yamlSource}_{ruleName}
        // e.g., "default_default", "network_profiling_slow_trace_network_profiling_slow_trace"
        final String sanitizedName = sanitizeName(ruleName);

        // First try exact match on simple name
        String className = classMap.get(sanitizedName);
        // Then try {sanitizedName}_{sanitizedName} pattern (yamlSource == ruleName)
        if (className == null) {
            className = classMap.get(sanitizedName + "_" + sanitizedName);
        }
        // Fallback: try suffix match (_ruleName at end of simple name)
        if (className == null) {
            final String suffix = "_" + sanitizedName;
            for (Map.Entry<String, String> entry : classMap.entrySet()) {
                if (entry.getKey().endsWith(suffix)) {
                    className = entry.getValue();
                    break;
                }
            }
        }

        if (className == null) {
            throw new ModuleStartException(
                "Pre-compiled LAL expression not found for rule: " + ruleName
                    + " (sanitized: " + sanitizedName + ")"
                    + ". Available: " + classMap.size() + " expressions"
                    + " keys: " + classMap.keySet());
        }

        try {
            final Class<?> exprClass = Class.forName(className);
            final LalExpression expression = (LalExpression) exprClass.getDeclaredConstructor().newInstance();
            final FilterSpec filterSpec = new FilterSpec(moduleManager, config);
            final int count = LOADED_COUNT.incrementAndGet();
            log.debug("Loaded pre-compiled LAL expression [{}/{}]: {} -> {}",
                count, classMap.size(), ruleName, className);
            return new DSL(ruleName, expression, filterSpec);
        } catch (ClassNotFoundException e) {
            throw new ModuleStartException(
                "Pre-compiled LAL expression class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new ModuleStartException(
                "Failed to instantiate pre-compiled LAL expression: " + className, e);
        }
    }

    public void evaluate(final ExecutionContext ctx) {
        if (log.isDebugEnabled()) {
            final LogMetadata metadata = ctx.metadata();
            log.debug("[LAL] rule={}, class={}, service={}, instance={}, endpoint={}",
                ruleName, expression.getClass().getName(),
                metadata.getService(), metadata.getServiceInstance(),
                metadata.getEndpoint());
        }
        expression.execute(filterSpec, ctx);
    }

    /**
     * Sanitize a name for use as a Java class name identifier.
     * Must match {@code LALCodegenHelper.sanitizeName()} from the upstream compiler.
     */
    private static String sanitizeName(final String name) {
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

    private static Map<String, String> loadManifest() {
        if (CLASS_MAP != null) {
            return CLASS_MAP;
        }
        synchronized (DSL.class) {
            if (CLASS_MAP != null) {
                return CLASS_MAP;
            }
            final Map<String, String> map = new HashMap<>();
            try (InputStream is = DSL.class.getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
                if (is == null) {
                    log.warn("LAL v2 expression manifest not found: {}", MANIFEST_PATH);
                    CLASS_MAP = map;
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
                        // Extract simple name from FQCN for lookup
                        final String simpleName = line.substring(line.lastIndexOf('.') + 1);
                        map.put(simpleName, line);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load LAL v2 expression manifest", e);
            }
            log.info("Loaded {} pre-compiled LAL v2 expressions from manifest", map.size());
            CLASS_MAP = map;
            return map;
        }
    }
}
