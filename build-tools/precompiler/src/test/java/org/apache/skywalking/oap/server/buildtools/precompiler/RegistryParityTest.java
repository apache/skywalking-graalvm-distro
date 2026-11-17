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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.graalvm.rules.HierarchyRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.graalvm.rules.TalFile;
import org.apache.skywalking.oap.server.graalvm.rules.TalRule;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule registry this build generated holds, for every bundled rule file, exactly what upstream's loader reads from
 * it, and every class the engine passes compiled, as their manifests (the build intermediates) record them.
 */
class RegistryParityTest {
    private static final Path OUTPUT = Path.of("target/generated-classes");
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        for (final String catalog : RegistryGenerator.MAL_CATALOGS) {
            if ("zabbix-rules".equals(catalog)) {
                continue;
            }
            final List<String> patterns = "otel-rules".equals(catalog) ? List.of("*", "**/*") : List.of("*");
            final Map<String, JsonNode> upstream = new TreeMap<>();
            for (final Rule rule : Rules.loadRules(catalog, patterns)) {
                upstream.put(rule.getName(), MAPPER.valueToTree(rule));
            }
            final Map<String, JsonNode> compiled = new TreeMap<>();
            files.stream().filter(f -> f.getCatalog().equals(catalog))
                 .forEach(f -> compiled.put(f.getRule().getName(), MAPPER.valueToTree(f.getRule())));
            assertFalse(upstream.isEmpty(), catalog);
            assertEquals(upstream, compiled, catalog);
        }
    }

    @Test
    void everyMalExpressionAndFilterIsTheCompiledClass() throws Exception {
        final Map<String, Supplier<?>> expressions = registry("malExpressions");
        final Map<String, Supplier<?>> filters = registry("malFilters");
        final Map<String, String> expectedExpressions = new LinkedHashMap<>();
        final Map<String, String> expectedFilters = new LinkedHashMap<>();
        for (final String file : lines("mal-v2.manifest")) {
            final Properties props = properties("mal-v2/" + file);
            for (int i = 0; props.getProperty("rule." + i + ".exp") != null; i++) {
                expectedExpressions.put(props.getProperty("rule." + i + ".exp"), props.getProperty("rule." + i + ".class"));
            }
            if (!props.getProperty("filter", "").isEmpty()) {
                expectedFilters.putIfAbsent(props.getProperty("filter"), props.getProperty("filter.class"));
            }
        }
        assertEquals(expectedExpressions.keySet(), expressions.keySet());
        expectedExpressions.forEach((exp, cls) -> assertEquals(cls, expressions.get(exp).get().getClass().getName(), exp));
        assertEquals(expectedFilters.keySet(), filters.keySet());
        expectedFilters.forEach((filter, cls) -> assertEquals(cls, filters.get(filter).get().getClass().getName(), filter));
    }

    @Test
    void everyZabbixConfigIsWhatUpstreamsLoaderReads() throws Exception {
        final List<String> stems = new ArrayList<>();
        for (final File f : ResourceUtils.getPathFiles("zabbix-rules")) {
            stems.add(f.getName().substring(0, f.getName().lastIndexOf('.')));
        }
        final List<Object> compiled = registry("zabbixConfigs");
        assertFalse(compiled.isEmpty());
        assertEquals(MAPPER.valueToTree(ZabbixConfigs.loadConfigs("zabbix-rules", stems)), MAPPER.valueToTree(compiled));
    }

    @Test
    void everyLalFileAndRuleIsWhatTheBuildCompiled() throws Exception {
        final List<LalFile> files = registry("lalFiles");
        final Map<String, LALConfigs> expected = MAPPER.readValue(
            OUTPUT.resolve("META-INF/config-data/lal.json").toFile(), new TypeReference<LinkedHashMap<String, LALConfigs>>() {
            });
        final Map<String, JsonNode> compiled = new LinkedHashMap<>();
        files.forEach(f -> compiled.put(f.getName(), MAPPER.valueToTree(f.getConfigs())));
        assertEquals(MAPPER.valueToTree(expected), MAPPER.valueToTree(compiled));

        final List<LalRule> rules = registry("lalRules");
        final Properties props = properties("lal-v2-rules.txt");
        assertFalse(rules.isEmpty());
        final Map<String, LalRule> byIdentity = new LinkedHashMap<>();
        rules.forEach(r -> byIdentity.put(r.getSource() + ":" + r.getLine() + ":" + r.getName(), r));
        assertEquals(rules.size(), byIdentity.size(), "two LAL rules share an identity");
        for (int i = 0; i < rules.size(); i++) {
            final String p = "rule." + i + ".";
            final LalRule rule = byIdentity.get(
                props.getProperty(p + "source") + ":" + props.getProperty(p + "line") + ":" + props.getProperty(p + "name"));
            assertNotNull(rule, "LAL rule " + i + " of the manifest is missing");
            assertEquals(props.getProperty(p + "source"), rule.getSource());
            assertEquals(Integer.parseInt(props.getProperty(p + "line")), rule.getLine());
            assertEquals(props.getProperty(p + "name"), rule.getName());
            assertEquals(props.getProperty(p + "class"), rule.getExpression().get().getClass().getName());
            assertEquals(props.getProperty(p + "inputType"),
                rule.getInputType() == null ? "" : rule.getInputType().getName());
        }
        assertEquals(null, props.getProperty("rule." + rules.size() + ".class"), "a LAL rule is missing");
    }

    @Test
    void everyTalFileAndRuleIsWhatTheBuildCompiled() throws Exception {
        final List<TalFile> files = registry("talFiles");
        final Map<String, TALConfigs> expected = MAPPER.readValue(
            OUTPUT.resolve("META-INF/config-data/tal.json").toFile(), new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
        final Map<String, JsonNode> compiled = new LinkedHashMap<>();
        files.forEach(f -> compiled.put(f.getFileName(), MAPPER.valueToTree(f.getConfigs())));
        assertEquals(MAPPER.valueToTree(expected), MAPPER.valueToTree(compiled));
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(compiled.keySet()), "listing order");

        final List<TalRule> rules = registry("talRules");
        final Properties props = properties("tal-v2-rules.txt");
        assertFalse(rules.isEmpty());
        final Map<String, TalRule> byIdentity = new LinkedHashMap<>();
        rules.forEach(r -> byIdentity.put(r.getSource() + ":" + r.getLine() + ":" + r.getName(), r));
        assertEquals(rules.size(), byIdentity.size(), "two TAL rules share an identity");
        for (int i = 0; i < rules.size(); i++) {
            final String p = "rule." + i + ".";
            final TalRule rule = byIdentity.get(
                props.getProperty(p + "source") + ":" + props.getProperty(p + "line") + ":" + props.getProperty(p + "name"));
            assertNotNull(rule, "TAL rule " + i + " of the manifest is missing");
            assertEquals(props.getProperty(p + "source"), rule.getSource());
            assertEquals(Integer.parseInt(props.getProperty(p + "line")), rule.getLine());
            assertEquals(props.getProperty(p + "name"), rule.getName());
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
        final Map<String, String> classes = new LinkedHashMap<>();
        for (final String line : lines("hierarchy-v2-rules.txt")) {
            classes.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
        }
        assertEquals(((Map<String, String>) definition.get("auto-matching-rules")).keySet(), compiled.getMatchers().keySet());
        classes.forEach((name, cls) -> assertEquals(cls, compiled.getMatchers().get(name).get().getClass().getName()));
    }

    @Test
    void theLayerExtensionsAreTheBundledFile() throws Exception {
        final LayerExtensionLoader.LayerExtensionsFile file;
        try (Reader reader = new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("layer-extensions.yml"), StandardCharsets.UTF_8)) {
            file = new Yaml().loadAs(reader, LayerExtensionLoader.LayerExtensionsFile.class);
        }
        final List<Object> compiled = registry("layerExtensions");
        assertEquals(MAPPER.valueToTree(file.getLayers() == null ? List.of() : file.getLayers()),
            MAPPER.valueToTree(compiled));
    }

    @Test
    void theOalAndMeterClassesAreTheGeneratedOnes() throws Exception {
        assertEquals(lines("oal-metrics-classes.txt"), sorted(names(registry("oalMetrics"))));
        assertEquals(lines("oal-dispatcher-classes.txt"), sorted(names(registry("oalDispatchers"))));
        assertEquals(lines("oal-disabled-sources.txt"), sorted(registry("oalDisabledSources")));
        final List<OALDefine> defines = registry("oalDefines");
        assertEquals(Arrays.stream(Precompiler.discoverOALDefines()).map(OALDefine::getConfigFile).sorted()
                           .collect(Collectors.toList()),
            defines.stream().map(OALDefine::getConfigFile).sorted().collect(Collectors.toList()));
        final Map<String, Class<?>> meters = registry("meterClasses");
        final List<String> expected = new ArrayList<>();
        for (final String manifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            lines(manifest).forEach(l -> expected.add(l.substring(l.indexOf('=') + 1)));
        }
        assertEquals(expected.stream().sorted().collect(Collectors.toList()),
            meters.keySet().stream().sorted().collect(Collectors.toList()));
        meters.forEach((name, cls) -> assertEquals(name, cls.getName()));
        assertNotNull(meters.values().iterator().next());
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
