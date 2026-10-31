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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import javassist.ClassPool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.ScopeType;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
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
 * Change: of() loads the pre-compiled {@link TalExpression} class from META-INF/tal-v2-rules.txt instead of
 * running TALClassGenerator: the class compiled from exactly the inputs it is given, the
 * {@code tal/<file>.yaml:<line>} coordinates upstream passes as {@link DslSourceRef} (several rules can share a line),
 * the rule name, the DSL text, the input and output types and the metric prefix. The metric declarations the
 * compiler collected are read from the same manifest, then
 * claimed and created exactly as upstream does, so the pre-generated meter classes are installed before a span
 * is seen. The pool / target loader of the runtime-rule overload are ignored: TAL has no runtime rules.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TalDSL {
    private static final String RULES_MANIFEST = "META-INF/tal-v2-rules.txt";
    private static volatile Map<String, List<PrecompiledRule>> BY_SOURCE;

    @Getter
    private final String ruleName;
    @Getter
    private final TalExpression expression;
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
                            final Class<?> outputType,
                            final String ruleName,
                            final String metricPrefix,
                            final DslSourceRef sourceRef,
                            final TALMetricShapes metricShapes,
                            final ClassPool pool,
                            final ClassLoader targetClassLoader) throws ModuleStartException {
        loadManifest();
        final List<PrecompiledRule> candidates = sourceRef == null ? List.of()
            : BY_SOURCE.getOrDefault(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine(), List.of());
        final PrecompiledRule rule = select(candidates, ruleName, dsl, inputType, outputType, metricPrefix);
        if (rule == null) {
            throw new ModuleStartException(
                "No pre-compiled TAL expression matches rule " + ruleName + " (source: " + sourceRef
                    + ", input " + typeName(inputType) + ", output " + typeName(outputType) + ", prefix "
                    + metricPrefix + "); compiled at that source: " + candidates.size()
                    + ". The rule files changed after the build; rebuild the distro.");
        }
        try {
            final TalExpression expression = (TalExpression) Class.forName(rule.className)
                                                                  .getDeclaredConstructor().newInstance();
            metricShapes.claim(sourceRef + "#" + ruleName, rule.metrics);
            final MeterSystem meterSystem =
                moduleManager.find(CoreModule.NAME).provider().getService(MeterSystem.class);
            for (final TALMetricDeclaration declaration : rule.metrics) {
                meterSystem.create(
                    declaration.fullName, declaration.function, ScopeType.valueOf(declaration.scopeType));
                if (declaration.labelLimit > 0) {
                    meterSystem.limitLabels(declaration.fullName, declaration.labelLimit);
                }
            }
            log.debug("Loaded pre-compiled TAL expression: {} -> {}", ruleName, rule.className);
            return new TalDSL(ruleName, expression, new FilterSpec(moduleManager, config), inputType);
        } catch (final Exception e) {
            throw new ModuleStartException("Failed to load pre-compiled TAL rule " + ruleName, e);
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
     * input type and builds the output type it was compiled for, so a class compiled for other inputs never runs
     */
    static PrecompiledRule select(final List<PrecompiledRule> candidates, final String ruleName, final String dsl,
                                  final Class<?> inputType, final Class<?> outputType, final String metricPrefix) {
        final String dslSha256 = dsl == null ? "" : sha256(dsl);
        for (final PrecompiledRule rule : candidates) {
            if (rule.name.equals(ruleName) && rule.dslSha256.equals(dslSha256)
                && rule.inputType.equals(typeName(inputType)) && rule.outputType.equals(typeName(outputType))
                && rule.metricPrefix.equals(metricPrefix == null ? "" : metricPrefix)) {
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

    static final class PrecompiledRule {
        final String name;
        final String className;
        final String inputType;
        final String outputType;
        final String metricPrefix;
        final String dslSha256;
        final List<TALMetricDeclaration> metrics;

        private PrecompiledRule(final Properties props, final String prefix) {
            this.name = props.getProperty(prefix + "name");
            this.className = props.getProperty(prefix + "class");
            this.inputType = props.getProperty(prefix + "inputType", "");
            this.outputType = props.getProperty(prefix + "outputType", "");
            this.metricPrefix = props.getProperty(prefix + "metricPrefix", "");
            this.dslSha256 = props.getProperty(prefix + "dslSha256", "");
            this.metrics = readMetrics(props, prefix);
        }
    }

    private static void loadManifest() {
        if (BY_SOURCE != null) {
            return;
        }
        synchronized (TalDSL.class) {
            if (BY_SOURCE != null) {
                return;
            }
            final Properties props = new Properties();
            try (InputStream is = TalDSL.class.getClassLoader().getResourceAsStream(RULES_MANIFEST)) {
                if (is == null) {
                    log.warn("TAL v2 rules manifest not found: {}", RULES_MANIFEST);
                } else {
                    // The precompiler writes it with Properties#store's escapes, in UTF-8.
                    props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load TAL v2 rules manifest", e);
            }
            final Map<String, List<PrecompiledRule>> bySource = parseManifest(props);
            log.info("Loaded {} pre-compiled TAL v2 expressions from manifest",
                bySource.values().stream().mapToInt(List::size).sum());
            BY_SOURCE = bySource;
        }
    }

    /**
     * @return the rules of a {@code tal-v2-rules.txt}, by {@code <sourcePath>:<line>}
     */
    static Map<String, List<PrecompiledRule>> parseManifest(final Properties props) {
        final Map<String, List<PrecompiledRule>> bySource = new HashMap<>();
        for (int i = 0; props.getProperty("rule." + i + ".class") != null; i++) {
            final String prefix = "rule." + i + ".";
            bySource.computeIfAbsent(props.getProperty(prefix + "source") + ":" + props.getProperty(prefix + "line"),
                source -> new ArrayList<>()).add(new PrecompiledRule(props, prefix));
        }
        return bySource;
    }

    private static List<TALMetricDeclaration> readMetrics(final Properties props, final String rulePrefix) {
        final List<TALMetricDeclaration> metrics = new ArrayList<>();
        for (int m = 0; ; m++) {
            final String prefix = rulePrefix + "metric." + m + ".";
            final String fullName = props.getProperty(prefix + "fullName");
            if (fullName == null) {
                return metrics;
            }
            metrics.add(new TALMetricDeclaration(
                fullName,
                props.getProperty(prefix + "function"),
                props.getProperty(prefix + "scopeType"),
                props.getProperty(prefix + "declaredName"),
                parseList(props.getProperty(prefix + "buckets"), Long::valueOf),
                parseList(props.getProperty(prefix + "ranks"), Integer::valueOf),
                Integer.parseInt(props.getProperty(prefix + "labelLimit", "0"))));
        }
    }

    private static <T> List<T> parseList(final String csv, final Function<String, T> parser) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(parser).collect(Collectors.toList());
    }
}
