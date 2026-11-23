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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricRuleConfig;
import org.apache.skywalking.oap.meter.analyzer.v2.compiler.MALClassGenerator;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.counter.CounterWindow;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.ExpressionMetadata;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Result;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.RuleSourceLines;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 *   <li>Runs the pre-compiled class the runtime DSL returns for the rule's coordinates (Path B)</li>
 *   <li>Asserts both paths produce identical results</li>
 * </ol>
 *
 * Subclasses only need to specify the YAML resource path.
 */
abstract class MALScriptComparisonBase {

    /** Counter for unique class names in fresh compilation (Path A). */
    private static final AtomicInteger FRESH_COUNTER = new AtomicInteger();

    private static final Pattern HISTOGRAM_CALL = Pattern.compile("\\.\\s*histogram\\s*\\(");
    private static final Pattern HISTOGRAM_LABEL =
        Pattern.compile("\\.\\s*histogram\\s*\\(\\s*(['\"])([^'\"]+)\\1");

    /** What {@code Expression.run} reports for an empty result, as opposed to an exception. */
    private static final String EMPTY = "Parsed result is an EMPTY sample family";

    @BeforeAll
    static void initMeterEntity() {
        MeterEntity.setNamingControl(
            new NamingControl(512, 512, 512, new EndpointNameGrouping()));
    }

    @AfterAll
    static void cleanupMeterEntity() {
        MeterEntity.setNamingControl(null);
    }

    // ---------------------------------------------------------------
    // Generate comparison tests — auto-discovery mode
    // ---------------------------------------------------------------

    protected Stream<DynamicTest> generateComparisonTests(final String yamlResource) {
        MetricRuleConfig rule = loadRule(yamlResource);
        String prefix = rule.getMetricPrefix();
        String expSuffix = rule.getExpSuffix();
        String expPrefix = rule.getExpPrefix();
        List<DynamicTest> tests = new ArrayList<>();

        for (MetricRuleConfig.RuleConfig mr : rule.getMetricsRules()) {
            String metricName = prefix + "_" + mr.getName();
            String fullExp = formatExp(expPrefix, expSuffix, mr.getExp());
            boolean needsWarmup = fullExp.contains(".increase(")
                || fullExp.contains(".rate(")
                || fullExp.contains(".irate(");

            tests.add(DynamicTest.dynamicTest(metricName, () ->
                compareWithAutoDiscovery(coordinates(rule, mr), metricName, fullExp, needsWarmup, false)));
        }
        return tests.stream();
    }

    /**
     * Auto-discovery for rules this suite has no inputs for (a rules overlay's): an expression the generated inputs
     * leave empty, or fail, alike on both paths is skipped and reported instead of failing; paths that differ fail.
     */
    protected Stream<DynamicTest> generateOverlayComparisonTests(final String yamlResource,
                                                                 final MetricRuleConfig rule) {
        List<DynamicTest> tests = new ArrayList<>();
        if (rule == null || rule.getMetricsRules() == null) {
            return tests.stream();
        }
        for (MetricRuleConfig.RuleConfig mr : rule.getMetricsRules()) {
            String metricName = rule.getMetricPrefix() + "_" + mr.getName();
            String fullExp = formatExp(rule.getExpPrefix(), rule.getExpSuffix(), mr.getExp());
            boolean needsWarmup = fullExp.contains(".increase(")
                || fullExp.contains(".rate(")
                || fullExp.contains(".irate(");
            tests.add(DynamicTest.dynamicTest(yamlResource + " " + metricName, () ->
                compareWithAutoDiscovery(coordinates(rule, mr), metricName, fullExp, needsWarmup, true)));
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
        return generateComparisonTests(loadRule(yamlResource), input1, input2);
    }

    protected Stream<DynamicTest> generateComparisonTests(
            final MetricRuleConfig rule,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) {
        String prefix = rule.getMetricPrefix();
        String expSuffix = rule.getExpSuffix();
        String expPrefix = rule.getExpPrefix();
        List<DynamicTest> tests = new ArrayList<>();

        for (MetricRuleConfig.RuleConfig mr : rule.getMetricsRules()) {
            String metricName = prefix + "_" + mr.getName();
            String fullExp = formatExp(expPrefix, expSuffix, mr.getExp());
            boolean needsWarmup = fullExp.contains(".increase(")
                || fullExp.contains(".rate(")
                || fullExp.contains(".irate(");

            tests.add(DynamicTest.dynamicTest(metricName, () ->
                compareWithExplicitInput(
                    coordinates(rule, mr), metricName, fullExp, needsWarmup, input1, input2)));
        }
        return tests.stream();
    }

    /**
     * @return the coordinates MetricConvert passes for the metric rule
     */
    private static DslSourceRef coordinates(final MetricRuleConfig rule, final MetricRuleConfig.RuleConfig metric) {
        return DslSourceRef.ofRule(rule.getSourcePath(), metric.getLineNo());
    }

    // ---------------------------------------------------------------
    // Core comparison logic — auto-discovery
    // ---------------------------------------------------------------

    private void compareWithAutoDiscovery(final DslSourceRef sourceRef,
                                          final String metricName,
                                          final String expression,
                                          final boolean needsWarmup,
                                          final boolean generatedInputs) throws Exception {
        Expression freshExpr = compileFresh(metricName + "_fresh", expression);

        ExpressionMetadata metadata = freshExpr.parse();
        List<String> sampleNames = metadata.getSamples();
        Set<String> requiredLabels = new HashSet<>();
        requiredLabels.addAll(metadata.getScopeLabels());
        if (metadata.getAggregationLabels() != null) {
            requiredLabels.addAll(metadata.getAggregationLabels());
        }
        // Metadata only knows scope/aggregation keys; closures (tags['x'], tags.x) and relation
        // key lists reference more. A superset of labels is harmless, a missing one makes the
        // expression drop every sample and the test vacuous.
        requiredLabels.addAll(referencedLabels(expression));
        Map<String, List<String>> tagValues = tagEqualValues(expression);
        String histogramLabel = histogramLabel(expression);

        ImmutableMap<String, SampleFamily> input1 = buildInputForSamples(
            sampleNames, requiredLabels, tagValues, histogramLabel, 100.0,
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli());
        ImmutableMap<String, SampleFamily> input2 = buildInputForSamples(
            sampleNames, requiredLabels, tagValues, histogramLabel, 200.0,
            Instant.parse("2024-01-01T00:00:10Z").toEpochMilli());

        Expression precompiledExpr = DSL.parse(metricName, expression, sourceRef);
        runAndCompare(metricName, freshExpr, precompiledExpr,
            needsWarmup, input1, input2, generatedInputs);
    }

    // ---------------------------------------------------------------
    // Core comparison logic — explicit input
    // ---------------------------------------------------------------

    private void compareWithExplicitInput(
            final DslSourceRef sourceRef,
            final String metricName,
            final String expression,
            final boolean needsWarmup,
            final ImmutableMap<String, SampleFamily> input1,
            final ImmutableMap<String, SampleFamily> input2) throws Exception {
        Expression freshExpr = compileFresh(metricName + "_fresh", expression);
        Expression precompiledExpr = DSL.parse(metricName, expression, sourceRef);
        runAndCompare(metricName, freshExpr, precompiledExpr,
            needsWarmup, input1, input2, false);
    }

    // ---------------------------------------------------------------
    // Shared run-and-compare
    // ---------------------------------------------------------------

    static void runAndCompare(final String metricName,
                                      final Expression freshExpr,
                                      final Expression precompiledExpr,
                                      final boolean needsWarmup,
                                      final ImmutableMap<String, SampleFamily> input1,
                                      final ImmutableMap<String, SampleFamily> input2,
                                      final boolean generatedInputs) {
        Result freshResult;
        Result precompiledResult;

        // Each path on inputs of its own: a family carries the mutable context an expression records its entities in
        if (needsWarmup) {
            // Since upstream #13911 CounterWindow is keyed by sample name, not metric name, so both
            // paths would share one window; run them back to back with a reset in between.
            CounterWindow.INSTANCE.reset();
            freshExpr.run(copyOf(input1));
            freshResult = freshExpr.run(copyOf(input2));
            CounterWindow.INSTANCE.reset();
            precompiledExpr.run(copyOf(input1));
            precompiledResult = precompiledExpr.run(copyOf(input2));
        } else {
            freshResult = freshExpr.run(copyOf(input1));
            precompiledResult = precompiledExpr.run(copyOf(input1));
        }

        assertEquals(freshResult.isSuccess(), precompiledResult.isSuccess(),
            metricName + ": success flag mismatch"
                + " (fresh=" + freshResult.getError()
                + ", precompiled=" + precompiledResult.getError() + ")");

        if (freshResult.isSuccess()) {
            assertSamplesMatch(metricName,
                freshResult.getData(), precompiledResult.getData());
            assertMeterSamplesMatch(metricName,
                freshResult.getData().context.getMeterSamples(), precompiledResult.getData().context.getMeterSamples());
        } else if (generatedInputs && Objects.equals(freshResult.getError(), precompiledResult.getError())) {
            // Generated inputs need not fit the rule (a label it reads as a number, say); both paths failing alike is
            // the same behaviour, and upstream's build does not run a rule either.
            Assumptions.abort(metricName + ": the generated inputs fail both paths alike, compared nothing: "
                + freshResult.getError());
        } else {
            fail(metricName + ": both paths returned EMPTY — test input likely"
                + " missing required tag values or labels"
                + " (fresh=" + freshResult.getError()
                + ", precompiled=" + precompiledResult.getError() + ")");
        }
    }

    /**
     * @return the families anew, each with a context of its own, holding the same (immutable) samples
     */
    static ImmutableMap<String, SampleFamily> copyOf(final ImmutableMap<String, SampleFamily> input) {
        final ImmutableMap.Builder<String, SampleFamily> copy = ImmutableMap.builder();
        input.forEach((name, family) -> copy.put(name, family == SampleFamily.EMPTY || family.samples.length == 0
            ? family : SampleFamilyBuilder.newBuilder(family.samples.clone())
                                          .defaultHistogramBucketUnit(family.context.getDefaultHistogramBucketUnit())
                                          .build()));
        return copy.build();
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
    // Input builders
    // ---------------------------------------------------------------

    static SampleFamily histogramSamples(final String name,
                                          final ImmutableMap<String, String> scope,
                                          final double scale,
                                          final long timestamp) {
        return SampleFamilyBuilder.newBuilder(histogramSampleArray(name, scope, scale, timestamp)).build();
    }

    static Sample[] histogramSampleArray(final String name,
                                         final ImmutableMap<String, String> scope,
                                         final double scale,
                                         final long timestamp) {
        return histogramSampleArray(name, scope, scale, timestamp, "le");
    }

    /**
     * @param bucketLabel the label the bucket bounds go under, as the expression's {@code histogram('<label>')} names it
     */
    static Sample[] histogramSampleArray(final String name,
                                         final ImmutableMap<String, String> scope,
                                         final double scale,
                                         final long timestamp,
                                         final String bucketLabel) {
        String[] les = {
            "0.005", "0.01", "0.025", "0.05", "0.1", "0.25",
            "0.5", "1", "2.5", "5", "10"
        };
        double[] vals = {10, 25, 50, 80, 120, 180, 220, 260, 285, 295, 299};
        Sample[] samples = new Sample[les.length];
        for (int i = 0; i < les.length; i++) {
            samples[i] = Sample.builder()
                .name(name)
                // The bucket bound replaces a scope label of the same name.
                .labels(ImmutableMap.<String, String>builder()
                    .putAll(Maps.filterKeys(scope, key -> !bucketLabel.equals(key))).put(bucketLabel, les[i]).build())
                .value(vals[i] * scale)
                .timestamp(timestamp)
                .build();
        }
        return samples;
    }

    private static final Pattern QUOTED_IN_BRACKETS = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_][A-Za-z0-9_]*)'");
    private static final Pattern TAGS_PROPERTY = Pattern.compile("tags\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TAG_EQUAL = Pattern.compile("tagEqual\\(\\s*'([^']+)'\\s*,\\s*'([^']*)'\\s*\\)");

    /** Every label an expression names in a {@code [...]} list, {@code tags['x']} or {@code tags.x}. */
    static Set<String> referencedLabels(final String expression) {
        Set<String> labels = new HashSet<>();
        Matcher lists = QUOTED_IN_BRACKETS.matcher(expression);
        while (lists.find()) {
            Matcher quoted = QUOTED.matcher(lists.group(1));
            while (quoted.find()) {
                labels.add(quoted.group(1));
            }
        }
        Matcher props = TAGS_PROPERTY.matcher(expression);
        while (props.find()) {
            labels.add(props.group(1));
        }
        return labels;
    }

    /** {@code tagEqual('k','v')} pairs, grouped by key, so each value gets its own sample. */
    static Map<String, List<String>> tagEqualValues(final String expression) {
        Map<String, List<String>> values = new TreeMap<>();
        Matcher m = TAG_EQUAL.matcher(expression);
        while (m.find()) {
            List<String> list = values.computeIfAbsent(m.group(1), k -> new ArrayList<>());
            if (!list.contains(m.group(2))) {
                list.add(m.group(2));
            }
        }
        return values;
    }

    /**
     * @return the label an expression's {@code histogram(...)} reads the bucket bounds from ({@code le} unless it names
     * one), or null for an expression without one
     */
    static String histogramLabel(final String expression) {
        if (!HISTOGRAM_CALL.matcher(expression).find()) {
            return null;
        }
        final Matcher named = HISTOGRAM_LABEL.matcher(expression);
        return named.find() ? named.group(2) : "le";
    }

    private static ImmutableMap<String, SampleFamily> buildInputForSamples(
            final List<String> sampleNames,
            final Set<String> requiredLabels,
            final Map<String, List<String>> tagValues,
            final String histogramLabel,
            final double value,
            final long timestamp) {
        ImmutableMap.Builder<String, String> labelBuilder = ImmutableMap.builder();
        labelBuilder.put("service", "test-svc");
        labelBuilder.put("instance", "test-inst");
        for (String label : requiredLabels) {
            if ("service".equals(label) || "instance".equals(label)
                || tagValues.containsKey(label) || label.equals(histogramLabel)) {
                continue;
            }
            labelBuilder.put(label, "test-" + label);
        }
        // One label set per combination of tagEqual values (cartesian, small in practice).
        List<Map<String, String>> labelSets = new ArrayList<>();
        labelSets.add(new LinkedHashMap<>(labelBuilder.build()));
        for (Map.Entry<String, List<String>> tag : tagValues.entrySet()) {
            List<Map<String, String>> expanded = new ArrayList<>();
            for (Map<String, String> base : labelSets) {
                for (String v : tag.getValue()) {
                    Map<String, String> copy = new LinkedHashMap<>(base);
                    copy.put(tag.getKey(), v);
                    expanded.add(copy);
                }
            }
            labelSets = expanded;
        }

        ImmutableMap.Builder<String, SampleFamily> builder = ImmutableMap.builder();
        for (String sampleName : sampleNames) {
            List<Sample> samples = new ArrayList<>();
            for (Map<String, String> labelSet : labelSets) {
                ImmutableMap<String, String> labels = ImmutableMap.copyOf(labelSet);
                if (histogramLabel != null) {
                    samples.addAll(Arrays.asList(
                        histogramSampleArray(sampleName, labels, value / 100.0, timestamp, histogramLabel)));
                } else {
                    samples.add(Sample.builder()
                        .name(sampleName)
                        .labels(labels)
                        .value(value)
                        .timestamp(timestamp)
                        .build());
                }
            }
            builder.put(sampleName, SampleFamilyBuilder.newBuilder(
                samples.toArray(new Sample[0])).build());
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

    /**
     * What upstream emits from a result: the samples of each entity (its scope, names and layer), compared by entity,
     * then by labels, value and timestamp.
     */
    private static void assertMeterSamplesMatch(final String metricName,
                                                final Map<MeterEntity, Sample[]> expectedSamples,
                                                final Map<MeterEntity, Sample[]> actualSamples) {
        // Hashed anew: an entity can change after the map took it (its attributes set as the expression decorates it)
        final Map<MeterEntity, Sample[]> expected = new HashMap<>(expectedSamples);
        final Map<MeterEntity, Sample[]> actual = new HashMap<>(actualSamples);
        assertEquals(expected.keySet(), actual.keySet(), metricName + ": entities mismatch");
        for (final Map.Entry<MeterEntity, Sample[]> entity : expected.entrySet()) {
            final List<Sample> e = byLabels(entity.getValue());
            final List<Sample> a = byLabels(actual.get(entity.getKey()));
            assertEquals(e.size(), a.size(), metricName + " " + entity.getKey() + ": sample count mismatch");
            for (int i = 0; i < e.size(); i++) {
                final String at = metricName + " " + entity.getKey() + "[" + i + "]";
                assertEquals(e.get(i).getLabels(), a.get(i).getLabels(), at + " labels mismatch");
                assertEquals(e.get(i).getValue(), a.get(i).getValue(), 0.001, at + " value mismatch");
                assertEquals(e.get(i).getTimestamp(), a.get(i).getTimestamp(), at + " timestamp mismatch");
            }
        }
    }

    private static List<Sample> byLabels(final Sample[] samples) {
        final List<Sample> sorted = new ArrayList<>(Arrays.asList(samples));
        sorted.sort(Comparator.comparing(sample -> new TreeMap<>(sample.getLabels()).toString()));
        return sorted;
    }

    // ---------------------------------------------------------------
    // YAML loading
    // ---------------------------------------------------------------

    /**
     * The rule file as upstream's loader binds it, stamped with its source path and lines, which the pre-compiled
     * classes are looked up by.
     */
    static Rule loadRule(final String yamlResource) {
        try (InputStream is = MALScriptComparisonBase.class
                .getClassLoader().getResourceAsStream(yamlResource)) {
            assertNotNull(is, "YAML resource not found: " + yamlResource);
            final String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            final Rule rule = new Yaml().loadAs(text, Rule.class);
            rule.setSourcePath(yamlResource);
            RuleSourceLines.assign(rule, text);
            return rule;
        } catch (Exception e) {
            throw new AssertionError("Failed to load YAML: " + yamlResource, e);
        }
    }

    /**
     * The Zabbix rule file as the Zabbix receiver loads it.
     */
    static ZabbixConfig loadZabbixConfig(final String yamlResource) {
        final String name = yamlResource.substring(yamlResource.lastIndexOf('/') + 1, yamlResource.lastIndexOf('.'));
        try {
            final List<ZabbixConfig> configs = ZabbixConfigs.loadConfigs("zabbix-rules", List.of(name));
            assertEquals(1, configs.size(), "Zabbix rule file not compiled: " + yamlResource);
            return configs.get(0);
        } catch (Exception e) {
            throw new AssertionError("Failed to load Zabbix rule file: " + yamlResource, e);
        }
    }

    // ---------------------------------------------------------------
    // Metric lookup from YAML
    // ---------------------------------------------------------------

    static String findMetricExp(final MetricRuleConfig rule, final String metricName) {
        String prefix = rule.getMetricPrefix();
        for (MetricRuleConfig.RuleConfig mr : rule.getMetricsRules()) {
            if (metricName.equals(prefix + "_" + mr.getName())) {
                return formatExp(rule.getExpPrefix(), rule.getExpSuffix(),
                    mr.getExp());
            }
        }
        throw new AssertionError("Metric not found in YAML: " + metricName);
    }

    // ---------------------------------------------------------------
    // Expression composition — delegates to the real upstream MetricConvert.formatExp
    // ---------------------------------------------------------------

    static String formatExp(final String expPrefix,
                            final String expSuffix,
                            final String exp) {
        // The real formatExp: the pre-compiled class is looked up with the text MetricConvert composes.
        return MetricConvert.formatExp(expPrefix, expSuffix, exp);
    }
}
