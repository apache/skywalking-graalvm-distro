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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javassist.ClassPool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.spec.filter.FilterSpec;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.debug.GateHolder;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

/**
 * Same-FQCN replacement for upstream v2 LAL DSL.
 *
 * <p>Loads pre-compiled {@link LalExpression} classes from the
 * {@code META-INF/lal-v2-rules.txt} manifest instead of compiling via
 * LALClassGenerator at runtime. Rules are keyed by the same source coordinates
 * ({@code lal/<file>.yaml:<line>}) upstream passes as {@link DslSourceRef}; the rule name is
 * the fallback for callers without one. The manifest also carries the effective input type
 * the compiler resolved, which upstream LogFilterListener uses to route mixed-type inputs
 * (e.g. Envoy HTTP vs TCP access logs) to the right rule.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DSL {
    private static final String RULES_MANIFEST = "META-INF/lal-v2-rules.txt";
    private static volatile Map<String, PrecompiledRule> BY_SOURCE;
    private static volatile Map<String, PrecompiledRule> BY_NAME;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    private final String ruleName;
    // Upstream LogFilterListener.loadStaticRules() reads this at boot via getExpression()
    // (LalStaticBindingHook.publish); @Getter matches upstream's same-FQCN class.
    @Getter
    private final LalExpression expression;
    private final FilterSpec filterSpec;
    @Getter
    private final Class<?> effectiveInputType;

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

    // Runtime-rule overload (upstream signature). We load the pre-compiled LalExpression by
    // source coordinates, so pool/targetClassLoader are ignored; runtime-rule hot-update is unsupported (501).
    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl,
                         final Class<?> inputType,
                         final Class<?> outputType,
                         final String ruleName,
                         final DslSourceRef sourceRef,
                         final ClassPool pool,
                         final ClassLoader targetClassLoader) throws ModuleStartException {
        return of(moduleManager, config, dsl, inputType, outputType, ruleName, sourceRef);
    }

    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl,
                         final Class<?> inputType,
                         final Class<?> outputType,
                         final String ruleName,
                         final DslSourceRef sourceRef) throws ModuleStartException {
        loadManifest();
        PrecompiledRule rule = null;
        if (sourceRef != null && sourceRef.getYamlFile() != null) {
            rule = BY_SOURCE.get(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine());
        }
        if (rule == null) {
            rule = BY_NAME.get(ruleName);
        }
        if (rule == null) {
            throw new ModuleStartException(
                "Pre-compiled LAL expression not found for rule: " + ruleName
                    + " (source: " + sourceRef + "). Available: " + BY_SOURCE.keySet());
        }

        try {
            final Class<?> exprClass = Class.forName(rule.className);
            final LalExpression expression = (LalExpression) exprClass.getDeclaredConstructor().newInstance();
            final Class<?> effectiveInputType = rule.inputType.isEmpty() ? null : Class.forName(rule.inputType);
            // Same metadata upstream DSL.of stamps: the debugger renders it next to the rule text.
            final GateHolder holder = expression.debugHolder();
            if (holder != null) {
                final LinkedHashMap<String, String> meta = new LinkedHashMap<>();
                if (ruleName != null && !ruleName.isEmpty()) {
                    meta.put("ruleName", ruleName);
                }
                if (outputType != null) {
                    meta.put("outputClass", outputType.getName());
                }
                if (inputType != null) {
                    meta.put("inputClass", inputType.getName());
                }
                holder.setMetadata(meta);
            }
            final FilterSpec filterSpec = new FilterSpec(moduleManager, config);
            final int count = LOADED_COUNT.incrementAndGet();
            log.debug("Loaded pre-compiled LAL expression [{}/{}]: {} -> {}",
                count, BY_SOURCE.size(), ruleName, rule.className);
            return new DSL(ruleName, expression, filterSpec, effectiveInputType);
        } catch (ClassNotFoundException e) {
            throw new ModuleStartException(
                "Pre-compiled LAL expression class not found: " + rule.className, e);
        } catch (ReflectiveOperationException e) {
            throw new ModuleStartException(
                "Failed to instantiate pre-compiled LAL expression: " + rule.className, e);
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

    private static final class PrecompiledRule {
        private final String className;
        private final String inputType;

        private PrecompiledRule(final String className, final String inputType) {
            this.className = className;
            this.inputType = inputType;
        }
    }

    private static void loadManifest() {
        if (BY_SOURCE != null) {
            return;
        }
        synchronized (DSL.class) {
            if (BY_SOURCE != null) {
                return;
            }
            final Map<String, PrecompiledRule> bySource = new HashMap<>();
            final Map<String, PrecompiledRule> byName = new HashMap<>();
            try (InputStream is = DSL.class.getClassLoader().getResourceAsStream(RULES_MANIFEST)) {
                if (is == null) {
                    log.warn("LAL v2 rules manifest not found: {}", RULES_MANIFEST);
                } else {
                    final Properties props = new Properties();
                    props.load(is);
                    for (int i = 0; ; i++) {
                        final String className = props.getProperty("rule." + i + ".class");
                        if (className == null) {
                            break;
                        }
                        final PrecompiledRule rule = new PrecompiledRule(
                            className, props.getProperty("rule." + i + ".inputType", "").trim());
                        bySource.put(props.getProperty("rule." + i + ".source") + ":"
                            + props.getProperty("rule." + i + ".line"), rule);
                        byName.putIfAbsent(props.getProperty("rule." + i + ".name"), rule);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load LAL v2 rules manifest", e);
            }
            log.info("Loaded {} pre-compiled LAL v2 expressions from manifest", bySource.size());
            BY_NAME = byName;
            BY_SOURCE = bySource;
        }
    }
}
