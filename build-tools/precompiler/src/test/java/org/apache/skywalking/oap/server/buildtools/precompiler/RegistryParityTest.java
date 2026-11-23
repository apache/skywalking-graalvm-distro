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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricRuleConfig;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.core.config.HierarchyDefinitionService;
import org.apache.skywalking.oap.server.core.config.v2.compiler.CompiledHierarchyRuleProvider;
import org.apache.skywalking.oap.server.core.config.v2.compiler.HierarchyRuleClassGenerator;
import org.apache.skywalking.oap.server.core.dsl.DslClassNaming;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.query.type.Service;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalExpression;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalFilter;
import org.apache.skywalking.oap.server.graalvm.rules.HierarchyRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.graalvm.rules.OalScript;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.TalFile;
import org.apache.skywalking.oap.server.graalvm.rules.TalRule;
import org.apache.skywalking.oap.server.graalvm.rules.ZabbixHostPatterns;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule registry this build generated holds, for every bundled rule file, exactly what upstream's loader reads from
 * it, and every class the engine passes compiled, as their manifests (the build intermediates) record them. Its
 * hierarchy matchers decide upstream's hierarchy test pairs as fresh compiles of their rules do.
 */
class RegistryParityTest {
    private static final Path OUTPUT = Path.of("target/generated-classes");
    private static final String MAL_RT_PACKAGE = "org.apache.skywalking.oap.meter.analyzer.v2.compiler.rt.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path HIERARCHY_TEST_DIR = Path.of(System.getProperty("user.dir")).getParent().getParent()
        .resolve("skywalking/oap-server/analyzer/hierarchy/src/test/resources/scripts/hierarchy-rule");
    private static URLClassLoader REGISTRY_LOADER;
    private static Class<?> COMPILED_RULES;

    @BeforeAll
    static void loadRegistry() throws Exception {
        assertTrue(Files.isDirectory(OUTPUT.resolve(RegistryGenerator.PACKAGE.replace('.', '/'))),
            "the build's precompile step has not generated the registry under " + OUTPUT);
        REGISTRY_LOADER = new URLClassLoader(new URL[] {OUTPUT.toUri().toURL()},
            RegistryParityTest.class.getClassLoader());
        COMPILED_RULES = REGISTRY_LOADER.loadClass(RegistryGenerator.PACKAGE + ".CompiledRules");
    }

    @AfterAll
    static void close() throws Exception {
        REGISTRY_LOADER.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T registry(final String method) throws Exception {
        final Method m = COMPILED_RULES.getMethod(method);
        return (T) m.invoke(null);
    }

    @Test
    void everyMalRuleFileIsWhatUpstreamsLoaderReads() throws Exception {
        final List<MalFile> files = registry("malFiles");
        for (final RuleCatalog malCatalog : RuleCatalog.malRules()) {
            final String catalog = malCatalog.getDirectory();
            final List<String> names = Precompiler.ruleNames(catalog);
            final Map<String, JsonNode> upstream = new TreeMap<>();
            if (!names.isEmpty()) {
                for (final Rule rule : Rules.loadRules(catalog, names)) {
                    upstream.put(rule.getName(), MAPPER.valueToTree(rule));
                }
            }
            final Map<String, JsonNode> compiled = new TreeMap<>();
            files.stream().filter(f -> f.getCatalog().equals(catalog))
                 .forEach(f -> compiled.put(f.getRule().getName(), MAPPER.valueToTree(f.getRule())));
            assertEquals(upstream, compiled, catalog);
        }
    }

    @Test
    void everyMalExpressionAndFilterIsTheClassCompiledForItsCoordinates() throws Exception {
        final List<CompiledMalExpression> expressions = registry("malExpressions");
        final List<CompiledMalFilter> filters = registry("malFilters");
        final Properties props = properties("mal-v2-rules.txt");
        final List<String> expectedExpressions = new ArrayList<>();
        for (int i = 0; props.getProperty("expression." + i + ".class") != null; i++) {
            final String p = "expression." + i + ".";
            expectedExpressions.add(props.getProperty(p + "source") + ":" + props.getProperty(p + "line") + " "
                + props.getProperty(p + "name") + " " + props.getProperty(p + "text") + " -> "
                + props.getProperty(p + "class"));
        }
        final List<String> expectedFilters = new ArrayList<>();
        for (int i = 0; props.getProperty("filter." + i + ".class") != null; i++) {
            final String p = "filter." + i + ".";
            expectedFilters.add(props.getProperty(p + "source") + ":" + props.getProperty(p + "line") + " "
                + props.getProperty(p + "text") + " -> " + props.getProperty(p + "class"));
        }
        assertEquals(sorted(expectedExpressions), sorted(expressions.stream().map(
            e -> e.getSource() + ":" + e.getLine() + " " + e.getMetricName() + " " + e.getExpression() + " -> "
                + e.getCompiled().get().getClass().getName()).collect(Collectors.toList())));
        assertEquals(sorted(expectedFilters), sorted(filters.stream().map(
            f -> f.getSource() + ":" + f.getLine() + " " + f.getLiteral() + " -> "
                + f.getCompiled().get().getClass().getName()).collect(Collectors.toList())));
    }

    @Test
    void everyRuleUpstreamsLoadersReadHasItsExpressionsAndFilterWhereMetricConvertLooksThemUp() throws Exception {
        final List<CompiledMalExpression> expressions = registry("malExpressions");
        final List<CompiledMalFilter> filters = registry("malFilters");
        final Set<String> compiledExpressions = expressions.stream()
            .map(e -> e.getSource() + ":" + e.getLine() + " " + e.getMetricName() + " " + e.getExpression())
            .collect(Collectors.toSet());
        final Set<String> compiledFilters = filters.stream()
            .map(f -> f.getSource() + ":" + f.getLine() + " " + f.getLiteral()).collect(Collectors.toSet());
        final List<MetricRuleConfig> configs = new ArrayList<>();
        for (final RuleCatalog malCatalog : RuleCatalog.malRules()) {
            final String catalog = malCatalog.getDirectory();
            final List<String> names = Precompiler.ruleNames(catalog);
            if (!names.isEmpty()) {
                configs.addAll(Rules.loadRules(catalog, names));
            }
        }
        // The Zabbix receiver compiles a file's expressions for its host patterns: a file without one compiles none
        ZabbixConfigs.loadConfigs("zabbix-rules", Precompiler.ruleNames("zabbix-rules")).stream()
                     .filter(ZabbixHostPatterns::compiledAtBoot).forEach(configs::add);
        // One expression for each distinct coordinate, metric name and text: two alike at one line run one class
        final Set<String> expectedExpressions = new HashSet<>();
        for (final MetricRuleConfig config : configs) {
            for (final MetricRuleConfig.RuleConfig rule : config.getMetricsRules()) {
                final String coordinates = DslSourceRef.ofRule(config.getSourcePath(), rule.getLineNo()).describeYaml();
                final String expected = coordinates + " " + config.getMetricPrefix() + "_" + rule.getName() + " "
                    + MetricConvert.formatExp(config.getExpPrefix(), config.getExpSuffix(), rule.getExp());
                assertTrue(compiledExpressions.contains(expected), "nothing compiled for " + expected);
                expectedExpressions.add(expected);
            }
            if (config.getFilter() != null && !config.getFilter().isEmpty()) {
                final String expected = DslSourceRef.ofRule(config.getSourcePath(), config.getFilterLine())
                    .describeYaml() + " " + config.getFilter();
                assertTrue(compiledFilters.contains(expected), "nothing compiled for " + expected);
            }
        }
        assertEquals(expectedExpressions.size(), expressions.size(), "a compiled expression no metric rule asks for");
    }

    @Test
    void everyMalClassIsNamedAsTheJvmOapNamesIt() throws Exception {
        final List<CompiledMalExpression> expressions = registry("malExpressions");
        final List<CompiledMalFilter> filters = registry("malFilters");
        for (final CompiledMalExpression e : expressions) {
            assertNamed(e.getCompiled().get().getClass().getName(), e.getSource(), e.getLine(), e.getMetricName());
        }
        for (final CompiledMalFilter f : filters) {
            assertNamed(f.getCompiled().get().getClass().getName(), f.getSource(), f.getLine(), "filter");
        }
    }

    // As MALBytecodeHelper names a class: the stem, with _2, _3 when two stems collide.
    private static void assertNamed(final String className, final String source, final int line, final String hint) {
        final String stem = MAL_RT_PACKAGE + DslClassNaming.stem(DslSourceRef.ofRule(source, line), hint);
        assertTrue(className.equals(stem) || className.matches(Pattern.quote(stem) + "_\\d+"),
            className + " is not named as the JVM OAP names it, " + stem);
    }

    @Test
    void everyZabbixConfigIsWhatUpstreamsLoaderReads() throws Exception {
        final List<String> stems = Precompiler.ruleNames("zabbix-rules");
        final List<Object> compiled = registry("zabbixConfigs");
        assertEquals(MAPPER.valueToTree(ZabbixConfigs.loadConfigs("zabbix-rules", stems)), MAPPER.valueToTree(compiled));
    }

    @Test
    void everyLalFileIsWhatUpstreamsLoaderReads() throws Exception {
        final Map<String, LalFile> compiled = new TreeMap<>();
        for (final LalFile file : RegistryParityTest.<List<LalFile>>registry("lalFiles")) {
            assertNull(compiled.put(stemOf(file.getName()), file), "two LAL files named " + stemOf(file.getName()));
        }
        for (final String stem : Precompiler.ruleNames("lal")) {
            final List<LALConfigs> upstream = LALConfigs.load("lal", List.of(stem));
            final LalFile file = compiled.remove(stem);
            if (upstream.isEmpty()) {
                // Upstream's loader returns nothing for a file without rules, and registers none of its layers.
                assertNull(file, "lal/" + stem + " is in the registry, though upstream's loader returns nothing for it");
                continue;
            }
            assertNotNull(file, "lal/" + stem + " is missing from the registry");
            assertEquals(null, StateComparison.firstDifference(upstream.get(0), file.getConfigs(), "lal/" + stem));
        }
        assertTrue(compiled.isEmpty(), "LAL files upstream's loader does not read are in the registry: "
            + compiled.keySet());
    }

    @Test
    void theLalFilesAreInTheOrderOneUpstreamLoadOfThemAllReturns() throws Exception {
        // The log analyzer loads every lalFiles entry in one call; its rules run on a log in the order it returns
        final List<LALConfigs> upstream = LALConfigs.load("lal", Precompiler.ruleNames("lal"));
        final List<LalFile> compiled = registry("lalFiles");
        assertEquals(upstream.size(), compiled.size(), "LAL files in the registry");
        for (int i = 0; i < upstream.size(); i++) {
            assertEquals(null, StateComparison.firstDifference(upstream.get(i), compiled.get(i).getConfigs(),
                "LAL file " + i + " (" + compiled.get(i).getName() + ")"));
        }
    }

    @Test
    void everyLalRuleIsTheClassCompiledAtItsSourceRef() throws Exception {
        // Upstream's loader's rules at the DslSourceRef the boot builds from the stamped coordinates, by name: rules on
        // one line may share a name, in other layers.
        final Map<List<String>, List<LALConfig>> configs = new LinkedHashMap<>();
        for (final String stem : Precompiler.ruleNames("lal")) {
            for (final LALConfigs file : LALConfigs.load("lal", List.of(stem))) {
                for (final LALConfig config : file.getRules()) {
                    configs.computeIfAbsent(Arrays.asList(DslSourceRef.ofRule(config.getSourcePath(),
                        config.getLineNo()).describeYaml(), config.getName()), k -> new ArrayList<>()).add(config);
                }
            }
        }
        final List<LalRule> rules = registry("lalRules");
        final Properties props = properties("lal-v2-rules.txt");
        // Keyed as the runtime DSL selects a class: the coordinates, the name, the DSL text and the requested types.
        // A list keeps a rule without a name apart from one named "null".
        final Map<List<String>, LalRule> byInputs = new LinkedHashMap<>();
        rules.forEach(r -> byInputs.put(Arrays.asList(r.getSource(), String.valueOf(r.getLine()), r.getName(),
            r.getDslSha256(), r.getRequestedInputType(), r.getRequestedOutputType()), r));
        assertEquals(rules.size(), byInputs.size(), "two LAL rules were compiled from the same inputs");
        for (int i = 0; i < rules.size(); i++) {
            final String p = "rule." + i + ".";
            final String name = ruleName(props, p);
            final LalRule rule = byInputs.get(Arrays.asList(props.getProperty(p + "source"),
                props.getProperty(p + "line"), name, props.getProperty(p + "dslSha256"),
                props.getProperty(p + "requestedInputType"), props.getProperty(p + "requestedOutputType")));
            assertNotNull(rule, "LAL rule " + i + " of the manifest is missing");
            assertEquals(props.getProperty(p + "source"), rule.getSource());
            assertEquals(Integer.parseInt(props.getProperty(p + "line")), rule.getLine());
            assertEquals(name, rule.getName());
            assertEquals(props.getProperty(p + "class"), rule.getExpression().get().getClass().getName());
            assertEquals(props.getProperty(p + "inputType"),
                rule.getInputType() == null ? "" : rule.getInputType().getName());
            assertEquals(props.getProperty(p + "requestedInputType"), rule.getRequestedInputType());
            assertEquals(props.getProperty(p + "requestedOutputType"), rule.getRequestedOutputType());
            // The runtime matches the DSL text upstream's loader hands it against this.
            final String at = props.getProperty(p + "source") + ":" + props.getProperty(p + "line");
            assertTrue(configs.getOrDefault(Arrays.asList(at, name), List.of()).stream()
                              .anyMatch(config -> sha256(config.getDsl()).equals(rule.getDslSha256())),
                "upstream's loader has no rule " + name + " at " + at + " with the DSL text the class was compiled from");
        }
        assertEquals(null, props.getProperty("rule." + rules.size() + ".class"), "a LAL rule is missing");
    }

    @Test
    void eachTalFileCarriesTheModulesUpstreamsRequiredModulesOfReadsFromIt() throws Exception {
        for (final TalFile file : RegistryParityTest.<List<TalFile>>registry("talFiles")) {
            assertEquals(TALConfigs.requiredModulesOf("tal", List.of(stemOf(file.getFileName()))),
                new LinkedHashSet<>(file.getRequiredModulesOf()), file.getFileName());
        }
    }

    @Test
    void theTalFilesAreWhatUpstreamsLoaderReturnsInItsOrder() throws Exception {
        final List<TalFile> files = registry("talFiles");
        final Map<String, String> activation = registry("activation");
        final List<String> talFiles = csv(activation.get(RuleCatalog.talFiles().key()));
        final List<String> nativeTalFiles = csv(activation.get(RuleCatalog.nativeTalFiles().key()));
        // The modules the image ships: upstream's boot skips a file requiring another.
        final ModuleManager image = Precompiler.shippedModules();
        // Active, in one list or the other: every staged file upstream's loader returns with those modules, and every
        // file of layerDefinitions alone it registers.
        final Set<String> loaded = new TreeSet<>();
        for (final String stem : Precompiler.talStems("tal")) {
            if (!TALConfigs.load("tal", List.of(stem), image).isEmpty()) {
                loaded.add(stem);
            }
        }
        files.stream().filter(f -> f.getConfigs().getRules() == null).forEach(f -> loaded.add(stemOf(f.getFileName())));
        final Set<String> active = new TreeSet<>(talFiles);
        active.addAll(nativeTalFiles);
        assertEquals(loaded, active);
        // As the trace analyzer loads them: one load over talFiles, whose order is the order their rules run in.
        assertSameFiles(TALConfigs.load("tal", talFiles, image), filesWithRules(files, talFiles), "talFiles");
        assertSameFiles(TALConfigs.loadInOrder("tal", nativeTalFiles, image), filesWithRules(files, nativeTalFiles),
            "nativeTalFiles");

        // Upstream's loader reads a file without rules, registers its layerDefinitions and returns nothing for it; the
        // registry carries it as written (empty for a document of comments only).
        for (final TalFile file : files) {
            if (file.getConfigs().getRules() == null) {
                final URL staged = getClass().getClassLoader().getResource("tal/" + file.getFileName());
                assertNotNull(staged, file.getFileName());
                try (Reader reader = new InputStreamReader(staged.openStream(), StandardCharsets.UTF_8)) {
                    final TALConfigs written = new Yaml().loadAs(reader, TALConfigs.class);
                    assertEquals(null, StateComparison.firstDifference(written == null ? new TALConfigs() : written,
                        file.getConfigs(), file.getFileName()));
                }
            }
        }
    }

    @Test
    void theNativeTalFilesRunInUpstreamsDefaultOrderThenByName() throws Exception {
        final List<String> upstreamOrder;
        try (Reader reader = new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("application.yml"), StandardCharsets.UTF_8)) {
            upstreamOrder = RegistryGenerator.nativeTalOrder(reader);
        }
        final List<String> nativeTalFiles = csv(RegistryParityTest.<Map<String, String>>registry("activation")
                                                    .get(RuleCatalog.nativeTalFiles().key()));
        final List<String> expected = new ArrayList<>(upstreamOrder);
        expected.retainAll(nativeTalFiles);
        nativeTalFiles.stream().filter(f -> !upstreamOrder.contains(f)).sorted().forEach(expected::add);
        assertEquals(expected, nativeTalFiles);
    }

    @Test
    void everyTalFileAndRuleIsWhatTheBuildCompiled() throws Exception {
        final List<TalFile> files = registry("talFiles");
        // The coordinates the boot builds a rule's DslSourceRef from are the ones its class is registered at.
        // A list keeps a rule without a name apart from one named "null".
        final Set<List<String>> coordinates = RegistryParityTest.<List<TalRule>>registry("talRules").stream()
            .map(r -> Arrays.asList(DslSourceRef.ofRule(r.getSource(), r.getLine()).describeYaml(), r.getName()))
            .collect(Collectors.toSet());
        for (final TalFile file : files) {
            for (final TALConfig rule : file.getConfigs().getRules() == null ? List.<TALConfig>of()
                : file.getConfigs().getRules()) {
                final DslSourceRef ref = DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo());
                assertTrue(coordinates.contains(Arrays.asList(ref.describeYaml(), rule.getName())),
                    "nothing compiled at " + ref.describeYaml() + " for " + rule.getName());
            }
        }

        final List<TalRule> rules = registry("talRules");
        final Properties props = properties("tal-v2-rules.txt");
        // Keyed as the runtime TalDSL selects a class: the coordinates, the name, the DSL text, the input and the prefix.
        final Map<List<String>, TalRule> byInputs = new LinkedHashMap<>();
        rules.forEach(r -> byInputs.put(Arrays.asList(r.getSource(), String.valueOf(r.getLine()), r.getName(),
            r.getDslSha256(), r.getInputType(), r.getInputAlias(), r.getMetricPrefix()), r));
        assertEquals(rules.size(), byInputs.size(), "two TAL rules were compiled from the same inputs");
        for (int i = 0; i < rules.size(); i++) {
            final String p = "rule." + i + ".";
            final TalRule rule = byInputs.get(Arrays.asList(props.getProperty(p + "source"),
                props.getProperty(p + "line"), ruleName(props, p), props.getProperty(p + "dslSha256"),
                props.getProperty(p + "inputType"), props.getProperty(p + "inputAlias"),
                props.getProperty(p + "metricPrefix")));
            assertNotNull(rule, "TAL rule " + i + " of the manifest is missing");
            assertEquals(props.getProperty(p + "source"), rule.getSource());
            assertEquals(Integer.parseInt(props.getProperty(p + "line")), rule.getLine());
            assertEquals(ruleName(props, p), rule.getName());
            assertEquals(props.getProperty(p + "class"), rule.getExpression().get().getClass().getName());
            assertEquals(props.getProperty(p + "inputType"), rule.getInputType());
            assertEquals(props.getProperty(p + "inputAlias"), rule.getInputAlias());
            assertEquals(props.getProperty(p + "metricPrefix"), rule.getMetricPrefix());
            assertEquals(props.getProperty(p + "dslSha256"), rule.getDslSha256());
            for (int m = 0; m < rule.getMetrics().size(); m++) {
                final String mp = p + "metric." + m + ".";
                assertEquals(props.getProperty(mp + "fullName"), rule.getMetrics().get(m).fullName);
                assertEquals(props.getProperty(mp + "function"), rule.getMetrics().get(m).function);
                assertEquals(props.getProperty(mp + "scopeType"), rule.getMetrics().get(m).scopeType);
                assertEquals(props.getProperty(mp + "declaredName"), rule.getMetrics().get(m).declaredName);
                assertEquals(props.getProperty(mp + "buckets"), join(rule.getMetrics().get(m).buckets));
                assertEquals(props.getProperty(mp + "ranks"), join(rule.getMetrics().get(m).ranks));
                assertEquals(props.getProperty(mp + "labelLimit"), String.valueOf(rule.getMetrics().get(m).labelLimit));
            }
            assertEquals(null, props.getProperty(p + "metric." + rule.getMetrics().size() + ".fullName"));
            for (int f = 0; f < rule.getFunctions().size(); f++) {
                final String fp = p + "function." + f + ".";
                assertEquals(props.getProperty(fp + "field"), rule.getFunctions().get(f).getField());
                assertEquals(props.getProperty(fp + "key"), rule.getFunctions().get(f).getKey());
                assertEquals(props.getProperty(fp + "type"), rule.getFunctions().get(f).getType());
            }
            assertEquals(null, props.getProperty(p + "function." + rule.getFunctions().size() + ".field"));
        }
        assertEquals(null, props.getProperty("rule." + rules.size() + ".class"), "a TAL rule is missing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHierarchyIsTheDefinitionFileWithItsCompiledRules() throws Exception {
        final Map<String, Object> definition;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("hierarchy-definition.yml")) {
            definition = new Yaml().load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        final HierarchyRules compiled = registry("hierarchy");
        assertEquals(definition.get("hierarchy"), compiled.getHierarchy());
        assertEquals(definition.get("auto-matching-rules"), compiled.getAutoMatchingRules());
        assertEquals(definition.get("layer-levels"), compiled.getLayerLevels());
        final Properties classes = properties("hierarchy-v2-rules.txt");
        assertEquals(((Map<String, String>) definition.get("auto-matching-rules")).keySet(), compiled.getMatchers().keySet());
        assertEquals(classes.stringPropertyNames(), compiled.getMatchers().keySet());
        precompiledMatchers(compiled).forEach(
            (name, matcher) -> assertEquals(classes.getProperty(name), matcher.getClass().getName()));
    }

    @Test
    void theHierarchyIsWhatUpstreamsHierarchyDefinitionServiceBuilds() throws Exception {
        final HierarchyRules compiled = registry("hierarchy");
        final Map<String, String> precompiledClasses = new TreeMap<>();
        precompiledMatchers(compiled).forEach((name, matcher) -> precompiledClasses.put(name, matcher.getClass().getName()));
        // The layers Core has registered when its hierarchy service starts.
        LayerExtensionLoader.load();
        final Map<String, String> expressions = new LinkedHashMap<>();
        final Map<String, String> classes = new TreeMap<>();
        final HierarchyDefinitionService upstream = new HierarchyDefinitionService(new CoreModuleConfig(),
            (rules, lines) -> {
                expressions.putAll(rules);
                final Map<String, BiFunction<Service, Service, Boolean>> built =
                    new CompiledHierarchyRuleProvider().buildRules(rules, lines);
                built.forEach((name, matcher) -> classes.put(name, matcher.getClass().getName()));
                return built;
            });
        assertEquals(expressions, compiled.getAutoMatchingRules());
        assertEquals(upstream.getLayerLevels(), compiled.getLayerLevels());
        assertEquals(upstream.getHierarchyDefinition().keySet(), compiled.getHierarchy().keySet());
        upstream.getHierarchyDefinition().forEach((layer, lowers) -> {
            final Map<String, String> compiledLowers = compiled.getHierarchy().get(layer);
            assertEquals(lowers.keySet(), compiledLowers.keySet(), layer);
            lowers.forEach((lower, rule) -> {
                // A name that is no matching rule relates the two layers without automatic matching.
                final String name = compiledLowers.get(lower);
                assertEquals(rule == null ? null : rule.getName() + " " + rule.getExpression(),
                    compiled.getAutoMatchingRules().containsKey(name)
                        ? name + " " + compiled.getAutoMatchingRules().get(name) : null,
                    layer + " > " + lower);
            });
        });
        assertEquals(classes, precompiledClasses, "the compiled classes are not named as the JVM OAP names them");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyMatchingRuleDecidesUpstreamsTestPairsAsAFreshCompileDoes() throws Exception {
        final HierarchyRules compiled = registry("hierarchy");
        final Map<String, BiFunction<Service, Service, Boolean>> precompiled = precompiledMatchers(compiled);
        final Map<String, String> upstreamRules =
            (Map<String, String>) yaml(HIERARCHY_TEST_DIR.resolve("test-hierarchy-definition.yml")).get("auto-matching-rules");
        final Map<String, List<Map<String, Object>>> pairs = (Map<String, List<Map<String, Object>>>)
            yaml(HIERARCHY_TEST_DIR.resolve("test-hierarchy-definition.data.yaml")).get("input");
        assertTrue(pairs != null && !pairs.isEmpty(), "no test pairs in upstream's hierarchy test data");
        // Not at the rule's line: the fresh class must not take the precompiled class's name.
        final HierarchyRuleClassGenerator generator = new HierarchyRuleClassGenerator();
        for (final Map.Entry<String, String> rule : compiled.getAutoMatchingRules().entrySet()) {
            final BiFunction<Service, Service, Boolean> fresh = generator.compile(rule.getKey(), rule.getValue());
            final boolean upstreamTestsIt = rule.getValue().equals(upstreamRules.get(rule.getKey()));
            for (final Map.Entry<String, List<Map<String, Object>>> group : pairs.entrySet()) {
                for (final Map<String, Object> pair : group.getValue()) {
                    final Service upper = service((Map<String, String>) pair.get("upper"));
                    final Service lower = service((Map<String, String>) pair.get("lower"));
                    final String at = rule.getKey() + " on " + group.getKey() + " pair " + pair.get("description");
                    final Object freshOutcome = outcome(fresh, upper, lower);
                    assertEquals(freshOutcome, outcome(precompiled.get(rule.getKey()), upper, lower), at);
                    if (upstreamTestsIt && group.getKey().equals(rule.getKey())) {
                        assertEquals(pair.get("expected"), freshOutcome, at);
                    }
                }
            }
        }
    }

    /**
     * Instantiated before anything compiles a matching rule afresh in this JVM: a fresh class defined first under a
     * precompiled class's name would be the one the registry loader finds.
     */
    private static Map<String, BiFunction<Service, Service, Boolean>> precompiledMatchers(final HierarchyRules compiled) {
        final Map<String, BiFunction<Service, Service, Boolean>> matchers = new LinkedHashMap<>();
        compiled.getMatchers().forEach((name, supplier) -> {
            final BiFunction<Service, Service, Boolean> matcher = supplier.get();
            assertSame(REGISTRY_LOADER, matcher.getClass().getClassLoader(), name + " is not the precompiled class");
            matchers.put(name, matcher);
        });
        return matchers;
    }

    private static Service service(final Map<String, String> names) {
        final Service service = new Service();
        service.setName(names.get("name"));
        service.setShortName(names.get("shortName"));
        return service;
    }

    private static Object outcome(final BiFunction<Service, Service, Boolean> matcher, final Service upper,
                                  final Service lower) {
        try {
            return matcher.apply(upper, lower);
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }

    private static Map<String, Object> yaml(final Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    @Test
    void theLayerExtensionsAreTheBundledFile() throws Exception {
        // A rules overlay may exclude the file or leave it empty: no layer then.
        List<?> layers = List.of();
        final InputStream resource = getClass().getClassLoader().getResourceAsStream("layer-extensions.yml");
        if (resource != null) {
            try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                final LayerExtensionLoader.LayerExtensionsFile file =
                    new Yaml().loadAs(reader, LayerExtensionLoader.LayerExtensionsFile.class);
                if (file != null && file.getLayers() != null) {
                    layers = file.getLayers();
                }
            }
        }
        final List<Object> compiled = registry("layerExtensions");
        assertEquals(MAPPER.valueToTree(layers), MAPPER.valueToTree(compiled));
    }

    @Test
    void theOalAndMeterClassesAreTheGeneratedOnes() throws Exception {
        final List<OalScript> scripts = registry("oalScripts");
        assertEquals(lines("oal-metrics-classes.txt"), sorted(scripts.stream()
            .flatMap(s -> names(s.getMetrics()).stream()).collect(Collectors.toList())));
        assertEquals(lines("oal-dispatcher-classes.txt"), sorted(scripts.stream()
            .flatMap(s -> names(s.getDispatchers()).stream()).collect(Collectors.toList())));
        assertEquals(lines("oal-disabled-sources.txt"), scripts.stream().flatMap(s -> s.getDisabledSources().stream())
                                                               .distinct().sorted().collect(Collectors.toList()));
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        final List<OALDefine> defines = scripts.stream().map(OalScript::getDefine).collect(Collectors.toList());
        assertEquals(Arrays.stream(Precompiler.effectiveOALDefines(Precompiler.discoverOALDefines(), overlay))
                           .map(OALDefine::getConfigFile).sorted()
                           .collect(Collectors.toList()),
            defines.stream().map(OALDefine::getConfigFile).sorted().collect(Collectors.toList()));
        // By the metric name the rule passes, to the class the build generated and named for it
        final Map<String, Class<?>> meters = registry("meterClasses");
        final Map<String, String> expected = new TreeMap<>();
        for (final String manifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            lines(manifest).forEach(l -> expected.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1)));
        }
        assertEquals(expected, meters.entrySet().stream().collect(
            Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName(), (a, b) -> a, TreeMap::new)));
        final Map<String, Class<?>> functions = registry("meterFunctions");
        assertEquals(lines("annotation-scan/MeterFunction.txt"), functions.entrySet().stream()
            .map(f -> f.getKey() + "=" + f.getValue().getName()).sorted().collect(Collectors.toList()));
    }

    /**
     * A provider's define finds its script: the registry holds the define object it loads, the build's own define of
     * an overlay script no provider loads, and the scripts the overlay excluded.
     */
    @Test
    void everyOalScriptIsFoundByTheDefineItsProviderLoads() throws Exception {
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        final Map<String, OALDefine> built = new TreeMap<>();
        for (final OALDefine define : Precompiler.effectiveOALDefines(Precompiler.discoverOALDefines(), overlay)) {
            built.put(define.getConfigFile(), define);
        }
        final List<OalScript> scripts = registry("oalScripts");
        assertEquals(built.size(), scripts.size());
        for (final OalScript script : scripts) {
            final OALDefine define = built.get(script.getDefine().getConfigFile());
            final boolean overlayAdded = define instanceof Precompiler.OverlayOALDefine;
            assertEquals(overlayAdded, script.isOverlayAdded(), define.getConfigFile());
            if (overlayAdded) {
                assertEquals(define, script.getDefine());
            } else {
                assertSame(define, script.getDefine());
            }
        }
        assertEquals(overlay.excluded().stream().filter(f -> f.startsWith("oal/")).collect(Collectors.toList()),
            registry("oalExcludedScripts"));
    }

    /**
     * @return the registry's files {@code listed} names that upstream's loader returns (those with rules), in the
     *     registry's order
     */
    private static List<TALConfigs> filesWithRules(final List<TalFile> files, final List<String> listed) {
        return files.stream().filter(f -> listed.contains(stemOf(f.getFileName())) && f.getConfigs().getRules() != null)
                    .map(TalFile::getConfigs).collect(Collectors.toList());
    }

    private static void assertSameFiles(final List<TALConfigs> upstream, final List<TALConfigs> compiled,
                                        final String setting) {
        assertEquals(upstream.size(), compiled.size(), setting);
        for (int i = 0; i < upstream.size(); i++) {
            assertEquals(null, StateComparison.firstDifference(upstream.get(i), compiled.get(i), setting + "[" + i + "]"));
        }
    }

    /**
     * @return a rule's name in a build manifest, null for one written as unnamed
     */
    private static String ruleName(final Properties props, final String prefix) {
        return Boolean.parseBoolean(props.getProperty(prefix + "unnamed")) ? null : props.getProperty(prefix + "name");
    }

    private static String sha256(final String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stemOf(final String file) {
        return file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
    }

    private static List<String> csv(final String value) {
        return value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split(",")));
    }

    private static List<String> sorted(final List<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }

    private static List<String> names(final List<Class<?>> classes) {
        return classes.stream().map(Class::getName).collect(Collectors.toList());
    }

    private static String join(final List<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<String> lines(final String manifest) throws Exception {
        return Files.readAllLines(OUTPUT.resolve("META-INF").resolve(manifest), StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toList());
    }

    private static Properties properties(final String manifest) throws Exception {
        final Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(OUTPUT.resolve("META-INF").resolve(manifest), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
