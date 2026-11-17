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

package org.apache.skywalking.oap.trace.analyzer.v2.dsl;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javassist.ClassPool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.ScopeType;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.TalRule;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALFunctionRegistry;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricShapes;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.spec.FilterSpec;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.PairingFact;

/**
 * GraalVM replacement for upstream TalDSL.
 * Original: skywalking/oap-server/analyzer/trace-analyzer/src/main/java/.../v2/dsl/TalDSL.java
 * Repackaged into trace-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: of() returns the pre-compiled {@link TalExpression} from the generated {@link CompiledRules} registry
 * instead of running TALClassGenerator: the class compiled from exactly the inputs it is given, the
 * {@code tal/<file>.yaml:<line>} coordinates upstream passes as {@link DslSourceRef} (several rules can share a line),
 * the rule name, the DSL text, the input type and alias and the metric prefix. Upstream's generator sets the
 * class's function-object fields from the registry after instantiating it; the rule registry records each field's
 * function-registry key, so the same objects of the function registry passed in are set here. The metric
 * declarations the compiler collected come from the same rule registry, then
 * claimed and created exactly as upstream does, so the pre-generated meter classes are installed before a span
 * is seen. The pool / target loader of the runtime-rule overload are ignored: TAL has no runtime rules.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TalDSL {
    @Getter
    private final String ruleName;
    @Getter
    private final TalExpression expression;
    @Getter
    private final FilterSpec filterSpec;

    @Getter
    private final Class<?> inputType;

    private final AtomicBoolean failureReported = new AtomicBoolean();

    public boolean firstFailure() {
        return failureReported.compareAndSet(false, true);
    }

    public static TalDSL of(final ModuleManager moduleManager,
                            final TraceAnalyzerModuleConfig config,
                            final String dsl,
                            final Class<?> inputType,
                            final String inputAlias,
                            final TALFunctionRegistry functions,
                            final String ruleName,
                            final String metricPrefix,
                            final DslSourceRef sourceRef,
                            final TALMetricShapes metricShapes,
                            final ClassPool pool,
                            final ClassLoader targetClassLoader) throws ModuleStartException {
        final List<TalRule> candidates = sourceRef == null ? List.of()
            : Index.BY_SOURCE.getOrDefault(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine(), List.of());
        final TalRule rule = select(candidates, ruleName, dsl, inputType, inputAlias, metricPrefix);
        if (rule == null) {
            throw new ModuleStartException(
                "No pre-compiled TAL expression matches rule " + ruleName + " (source: " + sourceRef
                    + ", input " + typeName(inputType) + " (" + inputAlias + "), prefix "
                    + metricPrefix + "); compiled at that source: " + candidates.size()
                    + ". The rule files changed after the build; rebuild the distro.");
        }
        try {
            final TalExpression expression = rule.getExpression().get();
            setFunctionFields(expression, rule, functions == null ? TALFunctionRegistry.defaults() : functions);
            metricShapes.claim(sourceRef + "#" + ruleName, rule.getMetrics());
            final MeterSystem meterSystem =
                moduleManager.find(CoreModule.NAME).provider().getService(MeterSystem.class);
            for (final TALMetricDeclaration declaration : rule.getMetrics()) {
                meterSystem.create(
                    declaration.fullName, declaration.function, ScopeType.valueOf(declaration.scopeType));
                if (declaration.labelLimit > 0) {
                    meterSystem.limitLabels(declaration.fullName, declaration.labelLimit);
                }
            }
            log.debug("Loaded pre-compiled TAL expression: {} -> {}", ruleName, expression.getClass().getName());
            return new TalDSL(ruleName, expression, new FilterSpec(moduleManager, config), inputType);
        } catch (final ModuleStartException e) {
            throw e;
        } catch (final Exception e) {
            throw new ModuleStartException("Failed to load pre-compiled TAL rule " + ruleName, e);
        }
    }

    static void setFunctionFields(final TalExpression expression, final TalRule rule,
                                  final TALFunctionRegistry registry)
        throws ReflectiveOperationException, ModuleStartException {
        for (final TalRule.Function function : rule.getFunctions()) {
            final Object owner = registry.instance(function.getKey());
            if (owner == null || !owner.getClass().getName().equals(function.getType())) {
                throw new ModuleStartException(
                    "TAL rule " + rule.getName() + " was compiled against " + function.getType()
                        + " under the function key " + function.getKey() + ", but the bound function registry holds "
                        + (owner == null ? "nothing" : owner.getClass().getName()) + " there; rebuild the distro.");
            }
            final Field field = expression.getClass().getDeclaredField(function.getField());
            field.setAccessible(true);
            field.set(expression, owner);
        }
    }

    public String networkAliasAddress(final PairingFact caller) {
        return expression.networkAliasAddress(filterSpec, new ExecutionContext(), caller);
    }

    public void evaluate(final ExecutionContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("[TAL] rule={}, class={}, service={}, kind={}",
                ruleName, expression.getClass().getName(),
                ctx.getMeta().getService(), ctx.getMeta().getKind());
        }
        expression.execute(filterSpec, ctx);
    }

    /**
     * @return the candidate compiled from these inputs, or null; the generated execute() casts the span to the
     * input type and calls the functions of the input alias it was compiled for, so a class compiled for other
     * inputs never runs
     */
    static TalRule select(final List<TalRule> candidates, final String ruleName, final String dsl,
                          final Class<?> inputType, final String inputAlias, final String metricPrefix) {
        final String dslSha256 = dsl == null ? "" : sha256(dsl);
        for (final TalRule rule : candidates) {
            if (rule.getName().equals(ruleName) && rule.getDslSha256().equals(dslSha256)
                && rule.getInputType().equals(typeName(inputType))
                && rule.getInputAlias().equals(inputAlias == null ? "" : inputAlias)
                && rule.getMetricPrefix().equals(metricPrefix == null ? "" : metricPrefix)) {
                return rule;
            }
        }
        return null;
    }

    private static String typeName(final Class<?> type) {
        return type == null ? "" : type.getName();
    }

    static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Index {
        private static final Map<String, List<TalRule>> BY_SOURCE = new HashMap<>();

        static {
            for (final TalRule rule : CompiledRules.talRules()) {
                BY_SOURCE.computeIfAbsent(rule.getSource() + ":" + rule.getLine(), source -> new ArrayList<>()).add(rule);
            }
        }
    }
}
