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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import groovy.lang.Binding;
import groovy.lang.GString;
import groovy.lang.GroovyShell;
import groovy.util.DelegatingScript;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.dsl.ExpressionParsingContext;
import org.apache.skywalking.oap.meter.analyzer.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;
import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for MAL comparison tests.
 * Loads a MAL YAML rule file, then for every metric:
 * <ol>
 *   <li>Composes the full expression from exp + expPrefix/expSuffix</li>
 *   <li>Discovers required sample names via ExpressionParsingContext</li>
 *   <li>Builds SampleFamily input with appropriate labels</li>
 *   <li>Runs the expression via fresh Groovy compilation (Path A)</li>
 *   <li>Runs the expression via pre-compiled class (Path B)</li>
 *   <li>Asserts both paths produce identical results</li>
 * </ol>
 *
 * Subclasses only need to specify the YAML resource path.
 */
abstract class MALScriptComparisonBase {

    private static Map<String, String> MANIFEST;
    private static Map<String, String> EXPRESSION_HASHES;

    @BeforeAll
    static void initMeterEntity() {
        MeterEntity.setNamingControl(
            new NamingControl(512, 512, 512, new EndpointNameGrouping()));
        MANIFEST = loadManifest();
        EXPRESSION_HASHES = loadKeyValueManifest(
            "META-INF/mal-groovy-expression-hashes.txt");
    }

    @AfterAll
    static void cleanupMeterEntity() {
        MeterEntity.setNamingControl(null);
    }

    // ---------------------------------------------------------------
    // Generate comparison tests — auto-discovery mode
    // (for files with no tagEqual/tagMatch/filter)
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
                compareWithAutoDiscovery(metricName, fullExp, needsWarmup)));
        }
        return tests.stream();
    }

    // ---------------------------------------------------------------
    // Generate comparison tests — explicit input mode
    // (for files with tagEqual/tagMatch/filter that need crafted data)
    // ---------------------------------------------------------------

    protected Stream<DynamicTest> generateComparisonTests(
            final String yamlResource,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        return generateComparisonTests(
            loadRule(yamlResource), input1, input2);
    }

    /**
     * Generate comparison tests from a pre-loaded Rule with explicit input.
     * Useful for rule files that need custom loading (e.g., Zabbix).
     */
    protected Stream<DynamicTest> generateComparisonTests(
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
                    metricName, fullExp, needsWarmup, input1, input2)));
        }
        return tests.stream();
    }

    // ---------------------------------------------------------------
    // Core comparison logic — auto-discovery
    // ---------------------------------------------------------------

    private void compareWithAutoDiscovery(final String metricName,
                                          final String expression,
                                          final boolean needsWarmup) {
        Expression groovyExpr = compileWithGroovy(metricName + "_groovy", expression);

        ExpressionParsingContext ctx = groovyExpr.parse();
        List<String> sampleNames = ctx.getSamples();
        Set<String> requiredLabels = new HashSet<>();
        requiredLabels.addAll(ctx.getScopeLabels());
        if (ctx.getAggregationLabels() != null) {
            requiredLabels.addAll(ctx.getAggregationLabels());
        }

        ImmutableMap<String, SampleFamily> input1 = buildInputForSamples(
            sampleNames, requiredLabels, 100.0,
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
        ImmutableMap<String, SampleFamily> input2 = buildInputForSamples(
            sampleNames, requiredLabels, 200.0,
            Instant.parse("2024-01-01T00:02:00Z").toEpochMilli());

        Expression precompiledExpr = loadPrecompiled(metricName, expression);
        runAndCompare(metricName, groovyExpr, precompiledExpr,
            needsWarmup, input1, input2);
    }

    // ---------------------------------------------------------------
    // Core comparison logic — explicit input
    // ---------------------------------------------------------------

    private void compareWithExplicitInput(
            final String metricName,
            final String expression,
            final boolean needsWarmup,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        Expression groovyExpr = compileWithGroovy(metricName + "_groovy", expression);
        Expression precompiledExpr = loadPrecompiled(metricName, expression);
        runAndCompare(metricName, groovyExpr, precompiledExpr,
            needsWarmup, input1, input2);
    }

    // ---------------------------------------------------------------
    // Shared run-and-compare
    // ---------------------------------------------------------------

    private static void runAndCompare(final String metricName,
                                      final Expression groovyExpr,
                                      final Expression precompiledExpr,
                                      final boolean needsWarmup,
                                      final ImmutableMap<String, SampleFamily> input1,
                                      final ImmutableMap<String, SampleFamily> input2) {
        Result groovyResult;
        Result precompiledResult;

        if (needsWarmup) {
            groovyExpr.run(input1);
            precompiledExpr.run(input1);
            groovyResult = groovyExpr.run(input2);
            precompiledResult = precompiledExpr.run(input2);
        } else {
            groovyResult = groovyExpr.run(input1);
            precompiledResult = precompiledExpr.run(input1);
        }

        assertEquals(groovyResult.isSuccess(), precompiledResult.isSuccess(),
            metricName + ": success flag mismatch"
                + " (groovy=" + groovyResult.getError()
                + ", precompiled=" + precompiledResult.getError() + ")");

        if (groovyResult.isSuccess()) {
            assertSamplesMatch(metricName,
                groovyResult.getData(), precompiledResult.getData());
        }
    }

    // ---------------------------------------------------------------
    // Path A: Fresh Groovy compilation (upstream DSL.parse replica)
    // ---------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    static Expression compileWithGroovy(final String metricName,
                                        final String expression) {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.setScriptBaseClass(DelegatingScript.class.getName());

        ImportCustomizer icz = new ImportCustomizer();
        icz.addImport("K8sRetagType", K8sRetagType.class.getName());
        icz.addImport("DetectPoint", DetectPoint.class.getName());
        icz.addImport("Layer", Layer.class.getName());
        icz.addImport("ProcessRegistry", ProcessRegistry.class.getName());
        cc.addCompilationCustomizers(icz);

        SecureASTCustomizer sec = new SecureASTCustomizer();
        sec.setDisallowedStatements(
            ImmutableList.<Class<? extends Statement>>builder()
                .add(WhileStatement.class)
                .add(DoWhileStatement.class)
                .add(ForStatement.class)
                .build());
        sec.setAllowedReceiversClasses(
            ImmutableList.<Class>builder()
                .add(Object.class).add(Map.class).add(List.class)
                .add(Array.class).add(K8sRetagType.class)
                .add(DetectPoint.class).add(Layer.class)
                .add(ProcessRegistry.class).add(GString.class)
                .add(String.class)
                .build());
        cc.addCompilationCustomizers(sec);

        GroovyShell sh = new GroovyShell(new Binding(), cc);
        DelegatingScript script = (DelegatingScript) sh.parse(expression);
        return new Expression(metricName, expression, script);
    }

    // ---------------------------------------------------------------
    // Path B: Pre-compiled class from manifest
    // ---------------------------------------------------------------

    /**
     * Load pre-compiled class from manifest matching the given metric name
     * and expression. Uses expression hash to resolve combination patterns
     * where multiple expressions share the same metric name (within a file
     * or across files like otel/telegraf/zabbix).
     */
    private static Expression loadPrecompiled(final String metricName,
                                              final String expression) {
        String expHash = sha256(expression);

        // Try exact metric name first
        String className = MANIFEST.get(metricName);
        if (className != null
                && expHash.equals(EXPRESSION_HASHES.get(metricName))) {
            return loadScriptClass(metricName, className, expression);
        }

        // Try combination suffixes: metricName_1, metricName_2, ...
        for (int i = 1; i < 100; i++) {
            String suffixed = metricName + "_" + i;
            className = MANIFEST.get(suffixed);
            if (className == null) {
                break;
            }
            if (expHash.equals(EXPRESSION_HASHES.get(suffixed))) {
                return loadScriptClass(metricName, className, expression);
            }
        }

        throw new AssertionError(
            "manifest missing metric: " + metricName
                + " (expression hash: " + expHash + ")");
    }

    private static Expression loadScriptClass(final String metricName,
                                               final String className,
                                               final String expression) {
        try {
            Class<?> scriptClass = Class.forName(className);
            DelegatingScript script =
                (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();
            return new Expression(metricName, expression, script);
        } catch (Exception e) {
            throw new AssertionError(
                "Failed to load pre-compiled class for " + metricName, e);
        }
    }

    private static String sha256(final String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------
    // Input builders
    // ---------------------------------------------------------------

    /**
     * Build a histogram SampleFamily with standard bucket boundaries.
     * Values are cumulative (each bucket >= previous).
     */
    static SampleFamily histogramSamples(final String name,
                                          final ImmutableMap<String, String> scope,
                                          final double scale,
                                          final long timestamp) {
        String[] les = {
            "0.005", "0.01", "0.025", "0.05", "0.1", "0.25",
            "0.5", "1", "2.5", "5", "10", "+Inf"
        };
        double[] vals = {10, 25, 50, 80, 120, 180, 220, 260, 285, 295, 299, 300};
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
        assertNotNull(expected, metricName + ": groovy result data null");
        assertNotNull(actual, metricName + ": precompiled result data null");

        assertEquals(expected.samples.length, actual.samples.length,
            metricName + ": sample count mismatch (groovy="
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
    // YAML loading (same pattern as upstream Rules.java)
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

    /**
     * Load a Zabbix rule file that uses 'metrics' instead of 'metricsRules'.
     */
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
            rule.setInitExp((String) yamlMap.get("initExp"));

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
    // Manifest loader
    // ---------------------------------------------------------------

    private static Map<String, String> loadManifest() {
        return loadKeyValueManifest("META-INF/mal-groovy-scripts.txt");
    }

    private static Map<String, String> loadKeyValueManifest(
            final String resource) {
        Map<String, String> map = new HashMap<>();
        try (InputStream is = MALScriptComparisonBase.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (is == null) {
                return map;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            map.put(parts[0], parts[1]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to load manifest: " + resource, e);
        }
        return map;
    }
}
