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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.reflect.ClassPath;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every compiled TAL file is active, in {@code nativeTalFiles} or {@code talFiles} by its input, so the registry fails
 * the build on what upstream's boot refuses in either list, and lists {@code talFiles} in the order upstream's loader
 * returns them. A file requiring a module the image does not ship, which the boot skips, is not compiled, so it takes
 * part in neither.
 */
class TalActivationCheckTest {
    private static final ModuleManager SHIPPED = TalPass.shippedModules();

    @TempDir
    Path out;

    private MeterSystem meterSystem;

    @BeforeEach
    void setUp() throws Exception {
        Precompiler.declareScopes();
        MeterSystem.setFunctionRegister(ClasspathScans.meterFunctionRegister(
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

    /** The files compile, as alternatives would; compiled into one image, all of them would be active. */
    private void compileAndCheck(final String talPath) throws Exception {
        compile(talPath);
    }

    private Properties compile(final String talPath) throws Exception {
        TalPass.compileTAL(out.toString(), meterSystem, talPath, SHIPPED);
        final Properties rules = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("META-INF/tal-v2-rules.txt"), StandardCharsets.UTF_8)) {
            rules.load(reader);
        }
        return rules;
    }

    @Test
    void aFileMixingNativeAndOtherInputsFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> compileAndCheck("tal-fixture-mixed"));
        assertTrue(e.getMessage().contains("TAL file tal/mixed.yaml mixes sw-native rules"), e.getMessage());
    }

    @Test
    void twoNativeRulesOfOneNameFailTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> compileAndCheck("tal-fixture-native-dup"));
        assertTrue(e.getMessage().contains("native rules"), e.getMessage());
        assertTrue(e.getMessage().contains("are both named same-alias"), e.getMessage());
    }

    @Test
    void talFilesAreInTheOrderUpstreamsLoaderReturnsThem() throws Exception {
        TalPass.compileTAL(out.toString(), meterSystem, "tal-fixture-order", SHIPPED);
        final Map<String, TALConfigs> files = configData();

        final List<String> upstream = new ArrayList<>();
        for (final TALConfigs configs : TALConfigs.load("tal-fixture-order", List.of("alpha", "beta", "gamma"))) {
            upstream.add(configs.getRules().get(0).getSourceName());
        }
        assertNotEquals(List.of("alpha.yaml", "beta.yaml", "gamma.yaml"), upstream,
            "the fixture's files must load in another order than by name, or this test pins nothing");
        assertEquals(upstream,
            RegistryGenerator.talFilesInLoadOrder("tal-fixture-order", files, SHIPPED));
    }

    @Test
    void twoOtlpRulesOfOneNameInOneLayerFailTheBuild() throws Exception {
        TalPass.compileTAL(out.toString(), meterSystem, "tal-fixture-otlp-dup", SHIPPED);
        final Map<String, TALConfigs> files = configData();

        final Exception e = assertThrows(IllegalStateException.class,
            () -> RegistryGenerator.talFilesInLoadOrder("tal-fixture-otlp-dup", files, SHIPPED));
        assertTrue(e.getMessage().matches("two TAL rules of layer OTLP_GENERAL are named same-name, in "
            + "tal/[ab]\\.yaml and tal/[ab]\\.yaml; a rule's name is unique within its layer"), e.getMessage());
    }

    @Test
    void anOtlpFileTheBootSkipsTakesPartInNoDuplicateCheck() throws Exception {
        TalPass.compileTAL(out.toString(), meterSystem, "tal-fixture-otlp-dup-unshipped", SHIPPED);
        assertEquals(List.of("a.yaml"), RegistryGenerator.talFilesInLoadOrder(
            "tal-fixture-otlp-dup-unshipped", configData(), SHIPPED));
    }

    @Test
    void aNativeFileTheBootSkipsTakesPartInNoDuplicateCheck() throws Exception {
        compile("tal-fixture-native-dup-unshipped");

        // Where the image ships the module, both files are active and their rules collide.
        assertThrows(IllegalStateException.class, () -> TalPass.compileTAL(out.toString(), meterSystem,
            "tal-fixture-native-dup-unshipped", TalPass.modules(Set.of("fixture-module"))), "both files active");
    }

    /**
     * Two native rules alike on one line share a class, but upstream's factory sees both and refuses their name, so the
     * build checks every rule as loaded, before rules share a class.
     */
    @Test
    void twoNativeRulesAlikeOnOneLineFailTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class, () -> compileAndCheck("tal-fixture-native-twins"));
        assertTrue(e.getMessage().contains("are both named twin"), e.getMessage());
    }

    /**
     * A rule without a name, which upstream's factories install, compiles and is recorded as unnamed; two native ones
     * collide, as upstream's map of native rules by name refuses them.
     */
    @Test
    void aRuleWithoutANameIsCompiledAndRecordedAsUnnamed() throws Exception {
        final Properties rules = compile("tal-fixture-unnamed");
        final List<String> unnamed = new ArrayList<>();
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            if (Boolean.parseBoolean(rules.getProperty("rule." + i + ".unnamed"))) {
                assertEquals(null, rules.getProperty("rule." + i + ".name"));
                unnamed.add(rules.getProperty("rule." + i + ".inputAlias"));
            }
        }
        unnamed.sort(null);
        assertEquals(List.of("otlp", "sw-native"), unnamed);
        assertEquals("null", rules.getProperty(prefixOfNamed(rules, "null") + "name"), "a rule named \"null\" keeps it");

        final Exception e = assertThrows(IllegalStateException.class,
            () -> compileAndCheck("tal-fixture-unnamed-native-dup"));
        assertTrue(e.getMessage().contains("are both named null"), e.getMessage());
    }

    private static String prefixOfNamed(final Properties rules, final String name) {
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            if (name.equals(rules.getProperty("rule." + i + ".name"))) {
                return "rule." + i + ".";
            }
        }
        throw new AssertionError("no rule named " + name);
    }

    private Map<String, TALConfigs> configData() throws Exception {
        return new ObjectMapper().readValue(out.resolve("META-INF/config-data/tal.json").toFile(),
            new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
    }
}
