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

package org.apache.skywalking.oap.server.graalvm.mal;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.skywalking.oap.meter.analyzer.v2.compiler.MALClassGenerator;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.ExpressionMetadata;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyFunctions;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base class for MAL comparison tests (v2 engine).
 * Loads a MAL YAML rule file, then for every metric:
 * <ol>
 *   <li>Composes the full expression from exp + expPrefix/expSuffix</li>
 *   <li>Discovers required sample names via ExpressionMetadata</li>
 *   <li>Builds SampleFamily input with appropriate labels</li>
 *   <li>Runs the expression via fresh v2 compilation (Path A)</li>
 *   <li>Runs the expression via pre-compiled class from manifest (Path B)</li>
 *   <li>Asserts both paths produce identical results</li>
 * </ol>
 *
 * Subclasses only need to specify the YAML resource path.
 */
abstract class MALScriptComparisonBase {

    /** expression text → FQCN, loaded from per-file MAL configs. */
    private static Map<String, String> EXPRESSION_MAP;

    /** Counter for unique class names in fresh compilation (Path A). */
    private static final AtomicInteger FRESH_COUNTER = new AtomicInteger();

    @BeforeAll
    static void initMeterEntity() {
        MeterEntity.setNamingControl(
            new NamingControl(512, 512, 512, new EndpointNameGrouping()));
        EXPRESSION_MAP = loadExpressionMap();
    }

    @AfterAll
    static void cleanupMeterEntity() {
        MeterEntity.setNamingControl(null);
    }

    // ---------------------------------------------------------------
    // Generate comparison tests — auto-discovery mode
    // ---------------------------------------------------------------

    protected Stream<DynamicTest> generateComparisonTests(final String yamlResource) {
        Rule rule = loadRule(yamlResource);
        String prefix = rule.getMetricPrefix();
        String expSuffix = rule.getExpSuffix();
        String expPrefix = rule.getExpPrefix();
        List<DynamicTest> tests = new ArrayList<>();

        for (MetricsRule mr : rule.getMetricsRules()) {
            String metricName = prefix + "_" + mr.getName();
            String fullExp = formatExp(expPrefix, expSuffix, mr.getExp());
            boolean needsWarmup = fullExp.contains(".increase(")
                || fullExp.contains(".rate(")
                || fullExp.contains(".irate(");

            tests.add(DynamicTest.dynamicTest(metricName, () ->
                compareWithAutoDiscovery(yamlResource, metricName, fullExp, needsWarmup)));
        }
        return tests.stream();
    }

    // ---------------------------------------------------------------
    // Generate comparison tests — explicit input mode
    // ---------------------------------------------------------------

    protected Stream<DynamicTest> generateComparisonTests(
            final String yamlResource,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        return generateComparisonTests(
            yamlResource, loadRule(yamlResource), input1, input2);
    }

    protected Stream<DynamicTest> generateComparisonTests(
            final String yamlResource,
            final Rule rule,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        String prefix = rule.getMetricPrefix();
        String expSuffix = rule.getExpSuffix();
        String expPrefix = rule.getExpPrefix();
        List<DynamicTest> tests = new ArrayList<>();

        for (MetricsRule mr : rule.getMetricsRules()) {
            String metricName = prefix + "_" + mr.getName();
            String fullExp = formatExp(expPrefix, expSuffix, mr.getExp());
            boolean needsWarmup = fullExp.contains(".increase(")
                || fullExp.contains(".rate(")
                || fullExp.contains(".irate(");

            tests.add(DynamicTest.dynamicTest(metricName, () ->
                compareWithExplicitInput(
                    yamlResource, metricName, fullExp, needsWarmup, input1, input2)));
        }
        return tests.stream();
    }

    /**
     * Overload accepting a pre-loaded Rule (for zabbix and similar custom formats).
     */
    protected Stream<DynamicTest> generateComparisonTests(
            final Rule rule,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        // When no yamlResource is available, use empty string for manifest lookup
        return generateComparisonTests("", rule, input1, input2);
    }

    // ---------------------------------------------------------------
    // Core comparison logic — auto-discovery
    // ---------------------------------------------------------------

    private void compareWithAutoDiscovery(final String yamlResource,
                                          final String metricName,
                                          final String expression,
                                          final boolean needsWarmup) throws Exception {
        Expression freshExpr = compileFresh(metricName + "_fresh", expression);

        ExpressionMetadata metadata = freshExpr.parse();
        List<String> sampleNames = metadata.getSamples();
        Set<String> requiredLabels = new HashSet<>();
        requiredLabels.addAll(metadata.getScopeLabels());
        if (metadata.getAggregationLabels() != null) {
            requiredLabels.addAll(metadata.getAggregationLabels());
        }

        ImmutableMap<String, SampleFamily> input1 = buildInputForSamples(
            sampleNames, requiredLabels, 100.0,
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
        ImmutableMap<String, SampleFamily> input2 = buildInputForSamples(
            sampleNames, requiredLabels, 200.0,
            Instant.parse("2024-01-01T00:00:10Z").toEpochMilli());

        Expression precompiledExpr = loadPrecompiled(yamlResource, metricName, expression);
        runAndCompare(metricName, freshExpr, precompiledExpr,
            needsWarmup, input1, input2);
    }

    // ---------------------------------------------------------------
    // Core comparison logic — explicit input
    // ---------------------------------------------------------------

    private void compareWithExplicitInput(
            final String yamlResource,
            final String metricName,
            final String expression,
            final boolean needsWarmup,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) throws Exception {
        Expression freshExpr = compileFresh(metricName + "_fresh", expression);
        Expression precompiledExpr = loadPrecompiled(yamlResource, metricName, expression);
        runAndCompare(metricName, freshExpr, precompiledExpr,
            needsWarmup, input1, input2);
    }

    // ---------------------------------------------------------------
    // Shared run-and-compare
    // ---------------------------------------------------------------

    private static void runAndCompare(final String metricName,
                                      final Expression freshExpr,
                                      final Expression precompiledExpr,
                                      final boolean needsWarmup,
                                      final ImmutableMap<String, SampleFamily> input1,
                                      final ImmutableMap<String, SampleFamily> input2) {
        Result freshResult;
        Result precompiledResult;

        if (needsWarmup) {
            freshExpr.run(input1);
            precompiledExpr.run(input1);
            freshResult = freshExpr.run(input2);
            precompiledResult = precompiledExpr.run(input2);
        } else {
            freshResult = freshExpr.run(input1);
            precompiledResult = precompiledExpr.run(input1);
        }

        assertEquals(freshResult.isSuccess(), precompiledResult.isSuccess(),
            metricName + ": success flag mismatch"
                + " (fresh=" + freshResult.getError()
                + ", precompiled=" + precompiledResult.getError() + ")");

        if (freshResult.isSuccess()) {
            assertSamplesMatch(metricName,
                freshResult.getData(), precompiledResult.getData());
        } else {
            fail(metricName + ": both paths returned EMPTY — test input likely"
                + " missing required tag values or labels"
                + " (fresh=" + freshResult.getError()
                + ", precompiled=" + precompiledResult.getError() + ")");
        }
    }

    // ---------------------------------------------------------------
    // Path A: Fresh v2 compilation via MALClassGenerator
    // ---------------------------------------------------------------

    static Expression compileFresh(final String metricName,
                                   final String expression) throws Exception {
        MALClassGenerator generator = new MALClassGenerator();
        generator.setClassNameHint("Test_" + FRESH_COUNTER.incrementAndGet());
        MalExpression malExpr = generator.compile(metricName, expression);
        return new Expression(metricName, expression, malExpr);
    }

    // ---------------------------------------------------------------
    // Path B: Pre-compiled MalExpression from v2 manifest
    // ---------------------------------------------------------------

    private static Expression loadPrecompiled(final String yamlResource,
                                              final String metricName,
                                              final String expression) {
        // Look up by expression text — each expression is globally unique
        // across all YAML files even when metric names overlap.
        final String className = EXPRESSION_MAP.get(expression);

        if (className == null) {
            throw new AssertionError(
                "manifest missing metric: " + metricName
                    + " (expression: " + expression
                    + ", yamlResource: " + yamlResource + ")");
        }

        try {
            Class<?> exprClass = Class.forName(className);
            MalExpression malExpr =
                (MalExpression) exprClass.getDeclaredConstructor().newInstance();
            wireClosures(exprClass, malExpr);
            return new Expression(metricName, expression, malExpr);
        } catch (Exception e) {
            throw new AssertionError(
                "Failed to load pre-compiled class for " + metricName
                    + " (class: " + className + ")", e);
        }
    }

    // ---------------------------------------------------------------
    // Closure wiring for pre-compiled classes
    // ---------------------------------------------------------------

    private record ClosureInfo(Class<?> interfaceClass, String samName,
                               MethodType samType, MethodType instantiatedType,
                               MethodType methodType) {
    }

    private static final Map<String, ClosureInfo> CLOSURE_TYPES = new HashMap<>();

    static {
        CLOSURE_TYPES.put(
            SampleFamilyFunctions.TagFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.TagFunction.class, "apply",
                MethodType.methodType(Object.class, Object.class),
                MethodType.methodType(Map.class, Map.class),
                MethodType.methodType(Map.class, Map.class)));

        CLOSURE_TYPES.put(
            SampleFamilyFunctions.PropertiesExtractor.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.PropertiesExtractor.class, "apply",
                MethodType.methodType(Object.class, Object.class),
                MethodType.methodType(Map.class, Map.class),
                MethodType.methodType(Map.class, Map.class)));

        CLOSURE_TYPES.put(
            SampleFamilyFunctions.ForEachFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.ForEachFunction.class, "accept",
                MethodType.methodType(void.class, String.class, Map.class),
                MethodType.methodType(void.class, String.class, Map.class),
                MethodType.methodType(void.class, String.class, Map.class)));

        CLOSURE_TYPES.put(
            SampleFamilyFunctions.DecorateFunction.class.getName(),
            new ClosureInfo(SampleFamilyFunctions.DecorateFunction.class, "accept",
                MethodType.methodType(void.class, Object.class),
                MethodType.methodType(void.class, Object.class),
                MethodType.methodType(void.class, Object.class)));
    }

    private static void wireClosures(final Class<?> clazz, final Object instance) {
        try {
            final MethodHandles.Lookup lookup =
                MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());

            for (final Field field : clazz.getFields()) {
                final ClosureInfo info = CLOSURE_TYPES.get(field.getType().getName());
                if (info == null) {
                    continue;
                }
                final String methodName = field.getName() + "_" + info.samName;
                final MethodHandle mh = lookup.findVirtual(
                    clazz, methodName, info.methodType);
                final CallSite site = LambdaMetafactory.metafactory(
                    lookup,
                    info.samName,
                    MethodType.methodType(info.interfaceClass, clazz),
                    info.samType,
                    mh,
                    info.instantiatedType);
                field.set(instance, site.getTarget().invoke(instance));
            }
        } catch (Throwable e) {
            throw new AssertionError("Failed to wire closures for " + clazz.getName(), e);
        }
    }

    // ---------------------------------------------------------------
    // Input builders
    // ---------------------------------------------------------------

    static SampleFamily histogramSamples(final String name,
                                          final ImmutableMap<String, String> scope,
                                          final double scale,
                                          final long timestamp) {
        String[] les = {
            "0.005", "0.01", "0.025", "0.05", "0.1", "0.25",
            "0.5", "1", "2.5", "5", "10"
        };
        double[] vals = {10, 25, 50, 80, 120, 180, 220, 260, 285, 295, 299};
        Sample[] samples = new Sample[les.length];
        for (int i = 0; i < les.length; i++) {
            samples[i] = Sample.builder()
                .name(name)
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(scope).put("le", les[i]).build())
                .value(vals[i] * scale)
                .timestamp(timestamp)
                .build();
        }
        return SampleFamilyBuilder.newBuilder(samples).build();
    }

    private static ImmutableMap<String, SampleFamily> buildInputForSamples(
            final List<String> sampleNames,
            final Set<String> requiredLabels,
            final double value,
            final long timestamp) {
        ImmutableMap.Builder<String, SampleFamily> builder = ImmutableMap.builder();
        ImmutableMap.Builder<String, String> labelBuilder = ImmutableMap.builder();
        labelBuilder.put("service", "test-svc");
        labelBuilder.put("instance", "test-inst");
        for (String label : requiredLabels) {
            if (!"service".equals(label) && !"instance".equals(label)) {
                labelBuilder.put(label, "test-" + label);
            }
        }
        ImmutableMap<String, String> labels = labelBuilder.build();

        for (String sampleName : sampleNames) {
            SampleFamily sf = SampleFamilyBuilder.newBuilder(
                Sample.builder()
                    .name(sampleName)
                    .labels(labels)
                    .value(value)
                    .timestamp(timestamp)
                    .build()
            ).build();
            builder.put(sampleName, sf);
        }
        return builder.build();
    }

    // ---------------------------------------------------------------
    // Assertion helpers
    // ---------------------------------------------------------------

    private static void assertSamplesMatch(final String metricName,
                                           final SampleFamily expected,
                                           final SampleFamily actual) {
        assertNotNull(expected, metricName + ": fresh result data null");
        assertNotNull(actual, metricName + ": precompiled result data null");

        assertEquals(expected.samples.length, actual.samples.length,
            metricName + ": sample count mismatch (fresh="
                + expected.samples.length + ", precompiled="
                + actual.samples.length + ")");

        for (int i = 0; i < expected.samples.length; i++) {
            Sample es = expected.samples[i];
            Sample as = actual.samples[i];
            assertEquals(es.getValue(), as.getValue(), 0.001,
                metricName + "[" + i + "] value mismatch");
            assertEquals(es.getLabels(), as.getLabels(),
                metricName + "[" + i + "] labels mismatch");
        }
    }

    // ---------------------------------------------------------------
    // YAML loading
    // ---------------------------------------------------------------

    static Rule loadRule(final String yamlResource) {
        try (InputStream is = MALScriptComparisonBase.class
                .getClassLoader().getResourceAsStream(yamlResource)) {
            assertNotNull(is, "YAML resource not found: " + yamlResource);
            return new Yaml().loadAs(is, Rule.class);
        } catch (Exception e) {
            throw new AssertionError("Failed to load YAML: " + yamlResource, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Rule loadZabbixRule(final String yamlResource) {
        try (InputStream is = MALScriptComparisonBase.class
                .getClassLoader().getResourceAsStream(yamlResource)) {
            assertNotNull(is, "YAML resource not found: " + yamlResource);
            Map<String, Object> yamlMap = new Yaml().load(is);
            Rule rule = new Rule();
            rule.setMetricPrefix((String) yamlMap.get("metricPrefix"));
            rule.setExpSuffix((String) yamlMap.get("expSuffix"));
            rule.setExpPrefix((String) yamlMap.get("expPrefix"));
            rule.setFilter((String) yamlMap.get("filter"));

            List<Map<String, String>> metrics =
                (List<Map<String, String>>) yamlMap.get("metrics");
            if (metrics != null) {
                List<MetricsRule> metricsRules = new ArrayList<>();
                for (Map<String, String> m : metrics) {
                    metricsRules.add(MetricsRule.builder()
                        .name(m.get("name"))
                        .exp(m.get("exp"))
                        .build());
                }
                rule.setMetricsRules(metricsRules);
            }
            return rule;
        } catch (Exception e) {
            throw new AssertionError("Failed to load YAML: " + yamlResource, e);
        }
    }

    // ---------------------------------------------------------------
    // Metric lookup from YAML
    // ---------------------------------------------------------------

    static String findMetricExp(final Rule rule, final String metricName) {
        String prefix = rule.getMetricPrefix();
        for (MetricsRule mr : rule.getMetricsRules()) {
            if (metricName.equals(prefix + "_" + mr.getName())) {
                return formatExp(rule.getExpPrefix(), rule.getExpSuffix(),
                    mr.getExp());
            }
        }
        throw new AssertionError("Metric not found in YAML: " + metricName);
    }

    // ---------------------------------------------------------------
    // Expression composition (replicates MetricConvert.formatExp)
    // ---------------------------------------------------------------

    static String formatExp(final String expPrefix,
                            final String expSuffix,
                            final String exp) {
        String ret = exp;
        if (!Strings.isNullOrEmpty(expPrefix)) {
            ret = String.format("(%s.%s)",
                StringUtils.substringBefore(exp, "."), expPrefix);
            final String after = StringUtils.substringAfter(exp, ".");
            if (!Strings.isNullOrEmpty(after)) {
                ret = String.format("(%s.%s)", ret, after);
            }
        }
        if (!Strings.isNullOrEmpty(expSuffix)) {
            ret = String.format("(%s).%s", ret, expSuffix);
        }
        return ret;
    }

    // ---------------------------------------------------------------
    // Per-file manifest loader
    // ---------------------------------------------------------------

    private static Map<String, String> loadExpressionMap() {
        final Map<String, String> map = new HashMap<>();
        final ClassLoader cl = MALScriptComparisonBase.class.getClassLoader();
        try (InputStream mis = cl.getResourceAsStream("META-INF/mal-v2.manifest")) {
            if (mis == null) {
                throw new AssertionError("META-INF/mal-v2.manifest not found");
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(mis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    try (InputStream cis = cl.getResourceAsStream("META-INF/mal-v2/" + line)) {
                        if (cis == null) {
                            continue;
                        }
                        Properties props = new Properties();
                        props.load(cis);
                        for (int i = 0; ; i++) {
                            String exp = props.getProperty("rule." + i + ".exp");
                            if (exp == null) {
                                break;
                            }
                            String className = props.getProperty("rule." + i + ".class", "");
                            if (!className.isEmpty()) {
                                map.put(exp, className);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to load per-file MAL manifests", e);
        }
        return map;
    }
}
