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

import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.TalRule;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A string the registry generator writes as a Java literal compiles back to the same string, whatever an operator
 * named a rule or wrote in it: escapes, non-ASCII text, and values past the class-file limit of a string constant. The
 * native TAL files run in the order upstream's {@code application.yml} lists by default, and the build fails when it
 * cannot read that order.
 * Methods split into helpers stay apart in one generated class. A catalog the rules overlay left no file activates an
 * empty list, as upstream accepts one.
 */
class RegistryGeneratorTest {
    @TempDir
    Path dir;

    @Test
    void theNativeTalOrderIsTheDefaultOfTheSettingSplitAsTheTraceAnalyzerSplitsIt() {
        final String yaml = "trace-analyzer:\n  selector: ${SW_TRACE_ANALYZER:default}\n  default:\n"
            + "    nativeTalFiles: ${SW_TRACE_NATIVE_TAL_FILES:native-rpc, native-segment,,native-ai}\n";
        assertEquals(List.of("native-rpc", "native-segment", "native-ai"),
            RegistryGenerator.nativeTalOrder(new StringReader(yaml)));
    }

    @Test
    void aNativeTalOrderThatCannotBeReadFailsTheBuild() {
        final Exception missing = assertThrows(IllegalStateException.class,
            () -> RegistryGenerator.nativeTalOrder(new StringReader("trace-analyzer:\n  default:\n    talFiles: x\n")));
        assertTrue(missing.getMessage().contains("sets no trace-analyzer.default.nativeTalFiles"), missing.getMessage());
        final Exception noDefault = assertThrows(IllegalStateException.class, () -> RegistryGenerator.nativeTalOrder(
            new StringReader("trace-analyzer:\n  default:\n    nativeTalFiles: ${SW_TRACE_NATIVE_TAL_FILES}\n")));
        assertTrue(noDefault.getMessage().contains("has no default"), noDefault.getMessage());
    }

    @Test
    void aCatalogWithoutFilesActivatesAnEmptyList() throws Exception {
        final Path classes = dir.resolve("out");
        Files.createDirectories(classes.resolve("META-INF/config-data"));
        Files.writeString(classes.resolve("META-INF/config-data/lal.json"), "{}");
        Files.writeString(classes.resolve("META-INF/lal-v2-rules.txt"), "");
        final RegistryGenerator generator = new RegistryGenerator(classes, dir.resolve("src"), catalog -> List.of());

        assertEquals(List.of(), generator.lal());
        for (final RuleCatalog.Setting setting : RuleCatalog.allSettings()) {
            assertEquals(List.of(), generator.activation().get(setting.key()), setting.key());
        }
    }

    /**
     * A staged file yielding no rule (comments only, or no rules list) is active: upstream's loader reads it, records it
     * for the rule catalog and returns nothing for it.
     */
    @Test
    void aStagedFileWithoutRulesIsActive() throws Exception {
        final Path classes = dir.resolve("out");
        Files.createDirectories(classes.resolve("META-INF/config-data"));
        Files.writeString(classes.resolve("META-INF/config-data/lal.json"), "{}");
        Files.writeString(classes.resolve("META-INF/lal-v2-rules.txt"), "");
        final RegistryGenerator generator = new RegistryGenerator(classes, dir.resolve("src"),
            catalog -> RuleCatalog.LAL.getDirectory().equals(catalog) ? List.of("commented-out") : List.of());

        assertEquals(List.of(), generator.lal(), "no rule, no class");
        assertEquals(List.of("commented-out"), generator.activation().get(RuleCatalog.LAL.setting().key()));
    }

    @Test
    void twoSplitMethodsOfOneGeneratedClassCompile() throws Exception {
        final Path sources = dir.resolve("src");
        final Path classes = dir.resolve("classes");
        Files.createDirectories(sources.resolve(RegistryGenerator.PACKAGE.replace('.', '/')));
        Files.createDirectories(classes);
        final RegistryGenerator.JavaClass c = new RegistryGenerator(classes, sources).new JavaClass("Split");
        final List<String> adds = IntStream.range(0, 301).mapToObj(i -> "l.add(" + i + ");").collect(Collectors.toList());
        c.chunkedMethod("public static void first(final java.util.List<Integer> l)", "l", adds);
        c.chunkedMethod("public static void second(final java.util.List<Integer> l)", "l", adds);
        c.write();

        final Path source = sources.resolve(RegistryGenerator.PACKAGE.replace('.', '/')).resolve("Split.java");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-encoding", "UTF-8", "-d", classes.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()})) {
            final Class<?> split = loader.loadClass(RegistryGenerator.PACKAGE + ".Split");
            for (final String method : List.of("first", "second")) {
                final List<Integer> filled = new ArrayList<>();
                split.getMethod(method, List.class).invoke(null, filled);
                assertEquals(IntStream.range(0, 301).boxed().collect(Collectors.toList()), filled, method);
            }
        }
    }

    /**
     * A rule object holding a large collection (a Zabbix file of 9000 item keys, which the receiver takes) builds it in
     * helpers of a bounded size, so no generated method exceeds the method-code limit; a large map too.
     */
    @Test
    void largeCollectionsOfARuleObjectCompile() throws Exception {
        final Path sources = dir.resolve("src");
        final Path classes = dir.resolve("classes");
        Files.createDirectories(sources.resolve(RegistryGenerator.PACKAGE.replace('.', '/')));
        Files.createDirectories(classes);
        final List<String> keys = IntStream.range(0, 9000).mapToObj(i -> "system.cpu.util[," + i + "]")
                                           .collect(Collectors.toList());
        final ZabbixConfig config = new ZabbixConfig();
        config.setRequiredZabbixItemKeys(keys);
        final Map<String, Object> levels = new LinkedHashMap<>();
        IntStream.range(0, 1000).forEach(i -> levels.put("LAYER_" + i, i));
        final RegistryGenerator.JavaClass c = new RegistryGenerator(classes, sources).new JavaClass("Large");
        c.imports(ZabbixConfig.class.getName());
        c.method("public static ZabbixConfig config()", List.of("return " + c.bean(config) + ";"));
        c.method("public static java.util.Map<String, Object> levels()", List.of("return " + c.expr(levels) + ";"));
        c.write();

        final Path source = sources.resolve(RegistryGenerator.PACKAGE.replace('.', '/')).resolve("Large.java");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8",
            "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
            getClass().getClassLoader())) {
            final Class<?> large = loader.loadClass(RegistryGenerator.PACKAGE + ".Large");
            assertEquals(keys, ((ZabbixConfig) large.getMethod("config").invoke(null)).getRequiredZabbixItemKeys());
            assertEquals(levels, large.getMethod("levels").invoke(null));
        }
    }

    /**
     * A TAL rule's metric declarations, their bounds and its functions are written in bounded helpers too: a rule of
     * ten percentile metrics of 625 bounds each, which upstream compiles, compiles.
     */
    @Test
    void aTalRuleOfManyMetricsOfManyBoundsCompiles() throws Exception {
        final Properties rules = new Properties();
        final String p = "rule.0.";
        rules.setProperty(p + "source", "tal/large.yaml");
        rules.setProperty(p + "line", "3");
        rules.setProperty(p + "name", "large");
        rules.setProperty(p + "class", NoOpTalExpression.class.getName());
        rules.setProperty(p + "inputType", "otlp");
        rules.setProperty(p + "inputAlias", "otlp");
        rules.setProperty(p + "metricPrefix", "fixture");
        rules.setProperty(p + "dslSha256", "0");
        final List<Long> bounds = LongStream.range(0, 625).map(i -> i * 10).boxed().collect(Collectors.toList());
        for (int m = 0; m < 10; m++) {
            final String mp = p + "metric." + m + ".";
            rules.setProperty(mp + "fullName", "fixture_latency_" + m);
            rules.setProperty(mp + "function", "percentile2");
            rules.setProperty(mp + "scopeType", "SERVICE");
            rules.setProperty(mp + "declaredName", "latency_" + m);
            rules.setProperty(mp + "buckets", bounds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            rules.setProperty(mp + "ranks", "50,75,90,95,99");
            rules.setProperty(mp + "labelLimit", "0");
        }
        for (int f = 0; f < 700; f++) {
            rules.setProperty(p + "function." + f + ".field", "_fn" + f);
            rules.setProperty(p + "function." + f + ".key", "key" + f);
            rules.setProperty(p + "function." + f + ".type", "type" + f);
        }
        final RegistryGenerator generator = new RegistryGenerator(dir.resolve("classes"), dir.resolve("src"));
        final RegistryGenerator.JavaClass c = generator.new JavaClass("TalLarge");
        c.imports("java.util.List", TALMetricDeclaration.class.getName());
        c.method("public static TalRule rule()", List.of(generator.talRule(rules, p).apply(c)));

        final TalRule rule = (TalRule) compileAndCall(c, "TalLarge", "rule");
        assertEquals(10, rule.getMetrics().size());
        for (final TALMetricDeclaration metric : rule.getMetrics()) {
            assertEquals(bounds, metric.buckets);
            assertEquals(List.of(50, 75, 90, 95, 99), metric.ranks);
        }
        assertEquals(700, rule.getFunctions().size());
        assertEquals("type699", rule.getFunctions().get(699).getType());
    }

    /**
     * A list holding a lone null constructs (a lone null argument would be the varargs array itself), as upstream binds
     * {@code requiredZabbixItemKeys: [null]}.
     */
    @Test
    void aListOfALoneNullConstructs() throws Exception {
        final ZabbixConfig config = new ZabbixConfig();
        config.setRequiredZabbixItemKeys(new ArrayList<>(Collections.singletonList(null)));
        final RegistryGenerator.JavaClass c = new RegistryGenerator(dir.resolve("classes"), dir.resolve("src"))
            .new JavaClass("LoneNull");
        c.imports(ZabbixConfig.class.getName());
        c.method("public static ZabbixConfig config()", List.of("return " + c.bean(config) + ";"));
        c.method("public static java.util.List<String> typed()",
            List.of("return " + c.listOf("String", List.of("null")) + ";"));

        assertEquals(Collections.singletonList(null),
            ((ZabbixConfig) compileAndCall(c, "LoneNull", "config")).getRequiredZabbixItemKeys());
        assertEquals(Collections.singletonList(null), compileAndCall(c, "LoneNull", "typed"));
    }

    /**
     * @return what the static method of the generated class returns, the class written and compiled first
     */
    private Object compileAndCall(final RegistryGenerator.JavaClass c, final String name,
                                  final String method) throws Exception {
        final Path sources = dir.resolve("src");
        final Path classes = dir.resolve("classes");
        final Path source = sources.resolve(RegistryGenerator.PACKAGE.replace('.', '/')).resolve(name + ".java");
        if (!Files.exists(source)) {
            Files.createDirectories(source.getParent());
            Files.createDirectories(classes);
            c.write();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
            getClass().getClassLoader())) {
            return loader.loadClass(RegistryGenerator.PACKAGE + "." + name).getMethod(method).invoke(null);
        }
    }

    @Test
    void literalsCompileBackToTheSameString() throws Exception {
        final StringBuilder long1 = new StringBuilder();
        while (long1.length() < 50_000) {
            long1.append("zählung-café 😀 \"q\" \\ \n");
        }
        final List<String> values = List.of(
            "", "zählung-café", " payments\\edge=x:y\n", "tab\there \"quoted\" \r\n", "\u0001\u001f\u007f",
            "😀 surrogate pair", long1.toString());

        final StringBuilder source = new StringBuilder("public class Literals {\n");
        source.append("    public static String[] values() {\n        return new String[] {\n");
        final List<String> literals = new ArrayList<>();
        for (final String value : values) {
            literals.add("            " + RegistryGenerator.literal(value));
        }
        source.append(String.join(",\n", literals)).append("\n        };\n    }\n}\n");
        final Path file = dir.resolve("Literals.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-d", dir.toString(), file.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()})) {
            final String[] compiled = (String[]) loader.loadClass("Literals").getMethod("values").invoke(null);
            assertEquals(values, List.of(compiled));
        }
    }
}
