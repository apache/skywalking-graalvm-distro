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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javassist.ClassPool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.spec.filter.FilterSpec;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.debug.GateHolder;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

/**
 * Same-FQCN replacement for upstream v2 LAL DSL.
 *
 * <p>Returns the {@link LalExpression} the precompiler compiled, from the generated {@link CompiledRules}
 * registry, instead of compiling via LALClassGenerator at runtime. Rules are keyed by the same source coordinates
 * ({@code lal/<file>.yaml:<line>}) upstream passes as {@link DslSourceRef}; the rule name is
 * the fallback for callers without one. The registry also carries the effective input type
 * the compiler resolved, which upstream LogFilterListener uses to route mixed-type inputs
 * (e.g. Envoy HTTP vs TCP access logs) to the right rule.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DSL {

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
        LalRule rule = null;
        if (sourceRef != null && sourceRef.getYamlFile() != null) {
            rule = Index.BY_SOURCE.get(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine());
        }
        if (rule == null) {
            rule = Index.BY_NAME.get(ruleName);
        }
        if (rule == null) {
            throw new ModuleStartException(
                "Pre-compiled LAL expression not found for rule: " + ruleName
                    + " (source: " + sourceRef + "). Available: " + Index.BY_SOURCE.keySet());
        }

        final LalExpression expression = rule.getExpression().get();
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
        log.debug("Loaded pre-compiled LAL expression: {} -> {}", ruleName, expression.getClass().getName());
        return new DSL(ruleName, expression, new FilterSpec(moduleManager, config), rule.getInputType());
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

    private static final class Index {
        private static final Map<String, LalRule> BY_SOURCE = new HashMap<>();
        private static final Map<String, LalRule> BY_NAME = new HashMap<>();

        static {
            for (final LalRule rule : CompiledRules.lalRules()) {
                BY_SOURCE.put(rule.getSource() + ":" + rule.getLine(), rule);
                BY_NAME.putIfAbsent(rule.getName(), rule);
            }
        }
    }
}
