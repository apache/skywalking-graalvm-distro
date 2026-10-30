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
import com.google.common.reflect.ClassPath;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TAL pass over rule files an operator wrote, not the shipped ones: it must compile whatever the grammar
 * accepts and record everything the runtime {@code TalDSL} needs, and fail the build where upstream fails the boot.
 */
class TALPrecompileTest {
    private static final String RT = "org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt.";

    @TempDir
    Path out;

    private MeterSystem meterSystem;

    @BeforeEach
    void setUp() throws Exception {
        MeterSystem.setFunctionRegister(Precompiler.meterFunctionRegister(
            ClassPath.from(getClass().getClassLoader())
                     .getTopLevelClassesRecursive("org.apache.skywalking.oap.server.core.analysis.meter.function")));
        MeterSystem.setOutputDirectory(out.toString());
        meterSystem = new MeterSystem(null);
        Files.createDirectories(out.resolve("META-INF"));
    }

    @AfterEach
    void tearDown() {
        MeterSystem.reset();
    }

    @Test
    void everyRuleFileIsCompiledWithItsOwnShapes() throws Exception {
        Precompiler.compileTAL(out.toString(), meterSystem, "tal-fixture");

        Properties rules = load("tal-v2-rules.txt");
        assertRule(rules, "edge.yml", "edge-calls", RT + "edge_L" + line("edge.yml", "edge-calls") + "_edge_calls");
        assertRule(rules, "fleet.yaml", "fleet-calls",
            RT + "fleet_L" + line("fleet.yaml", "fleet-calls") + "_fleet_calls");
        assertRule(rules, "fleet.yaml", "fleet-slow", RT + "fleet_L" + line("fleet.yaml", "fleet-slow") + "_fleet_slow");

        final String calls = prefixOf(rules, "fleet-calls");
        assertEquals(List.of(
                "fleet_calls|sumPerMin|SERVICE|||0",
                "fleet_latency|avg|SERVICE|||0",
                "fleet_sla|percent|SERVICE|||0",
                "fleet_apdex|apdex|SERVICE|||0",
                "fleet_latency_percentile|sumHistogramPercentile|SERVICE|10,25,50|50,99|0",
                "fleet_calls_by_status|sumLabeled|SERVICE|||7"),
            metrics(rules, calls));
        assertEquals(List.of(), metrics(rules, prefixOf(rules, "fleet-slow")), "a record-only rule creates no metric");
        assertEquals(List.of("edge_calls|sumPerMin|SERVICE|||0"), metrics(rules, prefixOf(rules, "edge-calls")));

        final List<String> meters = Files.readAllLines(out.resolve("META-INF/tal-meter-classes.txt"));
        assertEquals(7, meters.size(), meters.toString());
        assertTrue(meters.contains("fleet_calls_by_status="
            + "org.apache.skywalking.oap.server.core.analysis.meter.dynamic.fleet_calls_by_status"), meters.toString());
        for (final String meter : meters) {
            final String className = meter.substring(meter.indexOf('=') + 1);
            assertTrue(Files.exists(out.resolve(className.replace('.', '/') + ".class")), className);
        }
        final List<String> expressions = Files.readAllLines(out.resolve("META-INF/tal-v2-classes.txt"));
        assertEquals(3, expressions.size(), expressions.toString());

        final List<String> io = Files.readAllLines(out.resolve("META-INF/tal-io-classes.txt"));
        assertTrue(io.contains("org.apache.skywalking.oap.trace.analyzer.v2.spi.OtlpTALSourceTypeProvider"), io.toString());
        assertTrue(io.contains("io.opentelemetry.proto.trace.v1.Span"), io.toString());
        assertTrue(io.contains(
            "org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.DatabaseSlowStatementBuilder"),
            io.toString());

        // Every file, the rules-less one included: its layers are registered when the runtime loads it.
        final JsonNode configData = new ObjectMapper().readTree(out.resolve("META-INF/config-data/tal.json").toFile());
        final List<String> files = new ArrayList<>();
        configData.fieldNames().forEachRemaining(files::add);
        files.sort(null);
        assertEquals(List.of("edge.yml", "fleet.yaml", "layers-only.yaml"), files);
        assertEquals("FIXTURE_FLEET", configData.at("/layers-only.yaml/layerDefinitions/0/name").asText());
        assertEquals("fleet", configData.at("/fleet.yaml/metricPrefix").asText());
        assertEquals(line("fleet.yaml", "fleet-slow"), configData.at("/fleet.yaml/rules/1/lineNo").asInt());
    }

    @Test
    void everyRuleKeepsItsIdentity() throws Exception {
        Precompiler.compileTAL(out.toString(), meterSystem, "tal-fixture-identity");

        // Read back the way the runtime TalDSL reads it.
        final Properties rules = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("META-INF/tal-v2-rules.txt"), StandardCharsets.UTF_8)) {
            rules.load(reader);
        }
        final List<String> compiled = new ArrayList<>();
        for (int i = 0; rules.getProperty("rule." + i + ".name") != null; i++) {
            compiled.add(rules.getProperty("rule." + i + ".source") + ":" + rules.getProperty("rule." + i + ".line")
                + "#" + rules.getProperty("rule." + i + ".name"));
        }
        compiled.sort(null);
        final int inlineLine = line("tal-fixture-identity", "inline.yaml", "rules: [");
        assertEquals(List.of(
            // Upstream's stampSource names a file without a YAML extension <file>.yaml, at build time and at runtime.
            "tal/custom.rules.yaml:" + line("tal-fixture-identity", "custom.rules", "- name: from-custom")
                + "#from-custom",
            "tal/inline.yaml:" + inlineLine + "#a",
            "tal/inline.yaml:" + inlineLine + "#b",
            "tal/names.yaml:" + line("tal-fixture-identity", "names.yaml", "- name: 'zählung-café'") + "#zählung-café",
            "tal/names.yaml:" + line("tal-fixture-identity", "names.yaml", "- name: 'payments\\edge'") + "#payments\\edge"),
            compiled);

        // What the two rules on one line differ in is recorded, so neither shadows the other.
        final List<String> inline = new ArrayList<>();
        for (int i = 0; rules.getProperty("rule." + i + ".name") != null; i++) {
            if ("tal/inline.yaml".equals(rules.getProperty("rule." + i + ".source"))) {
                inline.add(rules.getProperty("rule." + i + ".class") + "|" + rules.getProperty("rule." + i + ".dslSha256"));
            }
        }
        assertEquals(2, inline.size());
        assertNotEquals(inline.get(0).split("\\|")[0], inline.get(1).split("\\|")[0], "distinct classes");
        assertNotEquals(inline.get(0).split("\\|")[1], inline.get(1).split("\\|")[1], "distinct rule text");

        final JsonNode configData = new ObjectMapper().readTree(out.resolve("META-INF/config-data/tal.json").toFile());
        final List<String> files = new ArrayList<>();
        configData.fieldNames().forEachRemaining(files::add);
        files.sort(null);
        assertEquals(List.of("custom.rules", "inline.yaml", "names.yaml"), files);
    }

    @Test
    void filesSharingAStemFailTheBuild() {
        final Exception e = assertThrows(Exception.class,
            () -> Precompiler.compileTAL(out.toString(), meterSystem, "tal-fixture-dup-stem"));
        assertTrue(e.getMessage().contains("team.yaml") && e.getMessage().contains("team.yml"), e.getMessage());
    }

    @Test
    void anOutputTypeThatIsNoBuilderFailsTheBuild() {
        final Exception e = assertThrows(Exception.class,
            () -> Precompiler.compileTAL(out.toString(), meterSystem, "tal-fixture-bad-output"));
        assertTrue(e.getMessage().contains("is not a TALOutputBuilder"), e.getMessage());
    }

    @Test
    void aRuleWithoutLayerFailsTheBuild() {
        final Exception e = assertThrows(Exception.class,
            () -> Precompiler.compileTAL(out.toString(), meterSystem, "tal-fixture-no-layer"));
        assertTrue(e.getMessage().contains("declares no layer"), e.getMessage());
    }

    private Properties load(final String manifest) throws Exception {
        final Properties props = new Properties();
        try (InputStream is = Files.newInputStream(out.resolve("META-INF").resolve(manifest))) {
            props.load(is);
        }
        return props;
    }

    /**
     * The alias form ({@code otlp}), the FQCN form and no {@code inputType} at all resolve to the same input type.
     */
    private static void assertRule(final Properties rules, final String file, final String name,
                                   final String className) throws Exception {
        final String prefix = prefixOf(rules, name);
        assertEquals("tal/" + file, rules.getProperty(prefix + "source"), name);
        assertEquals(line(file, name), Integer.parseInt(rules.getProperty(prefix + "line")), name);
        assertEquals(className, rules.getProperty(prefix + "class"), name);
        assertEquals("io.opentelemetry.proto.trace.v1.Span", rules.getProperty(prefix + "inputType"), name);
    }

    private static String prefixOf(final Properties rules, final String name) {
        for (int i = 0; rules.getProperty("rule." + i + ".name") != null; i++) {
            if (name.equals(rules.getProperty("rule." + i + ".name"))) {
                return "rule." + i + ".";
            }
        }
        throw new AssertionError("no rule " + name + " in " + rules);
    }

    private static List<String> metrics(final Properties rules, final String rulePrefix) {
        final List<String> out = new ArrayList<>();
        for (int m = 0; rules.getProperty(rulePrefix + "metric." + m + ".fullName") != null; m++) {
            final String p = rulePrefix + "metric." + m + ".";
            out.add(String.join("|", rules.getProperty(p + "fullName"), rules.getProperty(p + "function"),
                rules.getProperty(p + "scopeType"), rules.getProperty(p + "buckets"), rules.getProperty(p + "ranks"),
                rules.getProperty(p + "labelLimit")));
        }
        return out;
    }

    /**
     * @return the 1-based line of the rule's {@code - name:} entry in the fixture file
     */
    private static int line(final String file, final String ruleName) throws Exception {
        return line("tal-fixture", file, "- name: " + ruleName);
    }

    /**
     * @return the 1-based line of the fixture file that starts with {@code start}, ignoring indentation
     */
    private static int line(final String dir, final String file, final String start) throws Exception {
        final URL url = TALPrecompileTest.class.getClassLoader().getResource(dir + "/" + file);
        final List<String> lines = Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(start)) {
                return i + 1;
            }
        }
        throw new AssertionError("no line " + start + " in " + file);
    }
}
