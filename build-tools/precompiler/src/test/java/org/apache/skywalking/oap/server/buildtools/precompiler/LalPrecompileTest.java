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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.debug.ToJson;
import org.apache.skywalking.oap.server.core.source.LALOutputBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LalPrecompileTest {
    @TempDir
    Path out;

    @BeforeEach
    void createMetaInf() throws Exception {
        Files.createDirectories(out.resolve("META-INF"));
    }

    @Test
    void twoRulesOnOneLineCompileAsUpstreamCompilesThem() throws Exception {
        LalPass.compileLAL(out.toString(), "lal-fixture-one-line");

        final Properties rules = rules();
        final String first = prefixOf(rules, "first");
        final String second = prefixOf(rules, "second");
        assertEquals(rules.getProperty(first + "line"), rules.getProperty(second + "line"));
        assertNotEquals(rules.getProperty(first + "class"), rules.getProperty(second + "class"));
    }

    /**
     * Rules alike on one line in one layer would share a class, but upstream's factory files a layer's rules by name
     * and refuses the second: the build checks every rule as loaded, before rules share a class.
     */
    @Test
    void twoRulesAlikeOnOneLineInOneLayerFailTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> LalPass.compileLAL(out.toString(), "lal-fixture-one-line-twins-one-layer"));
        assertTrue(e.getMessage().contains("two rules of layer GENERAL are named twin"), e.getMessage());
    }

    /**
     * A rule without a name, which upstream's factory compiles, is compiled and recorded as unnamed, apart from one
     * named "null".
     */
    @Test
    void aRuleWithoutANameIsCompiledAndRecordedAsUnnamed() throws Exception {
        LalPass.compileLAL(out.toString(), "lal-fixture-unnamed");

        final Properties rules = rules();
        assertEquals("true", rules.getProperty("rule.0.unnamed"));
        assertNull(rules.getProperty("rule.0.name"));
        assertEquals("null", rules.getProperty("rule.1.name"));
        assertNull(rules.getProperty("rule.1.unnamed"));
        assertNotEquals(rules.getProperty("rule.0.class"), rules.getProperty("rule.1.class"));
    }

    @Test
    void twoRulesOnOneLineAlikeInEverythingTheClassIsCompiledFromShareOneClass() throws Exception {
        LalPass.compileLAL(out.toString(), "lal-fixture-one-line-twins");

        final Properties rules = rules();
        assertEquals("twin", rules.getProperty("rule.0.name"));
        assertNull(rules.getProperty("rule.1.name"), "the second rule runs the class of the first");
        assertEquals(1, Files.readAllLines(out.resolve("META-INF/lal-v2-classes.txt")).size());
    }

    @Test
    void aRuleIsKeyedByTheSourceRefTheBootLooksItUpBy() throws Exception {
        LalPass.compileLAL(out.toString(), "lal-fixture-unresolved-line");

        // Upstream's loader stamps no line on a rule a merge key brings in; the boot's DslSourceRef marks it unresolved.
        final JsonNode configData = new ObjectMapper().readTree(out.resolve("META-INF/config-data/lal.json").toFile());
        assertEquals(0, configData.at("/merged.yaml/rules/0/lineNo").asInt());
        final Properties rules = rules();
        final String merged = prefixOf(rules, "merged");
        final DslSourceRef bootRef = DslSourceRef.ofRule("lal/merged.yaml", 0);
        assertEquals(bootRef.getYamlFile(), rules.getProperty(merged + "source"));
        assertEquals(String.valueOf(bootRef.getYamlLine()), rules.getProperty(merged + "line"));
    }

    @Test
    void theSpiImplementationsAndTheTypesRulesReadValuesFromAreRecordedForReflection() throws Exception {
        LalPass.compileLAL(out.toString(), "lal-fixture-layers");

        final List<String> io = Files.readAllLines(out.resolve("META-INF/lal-io-classes.txt"));
        final List<String> inputTypes = Files.readAllLines(out.resolve("META-INF/lal-input-types.txt"));
        for (final LALSourceTypeProvider provider : ServiceLoader.load(LALSourceTypeProvider.class)) {
            assertTrue(io.contains(provider.getClass().getName()), provider.getClass().getName());
            assertTrue(inputTypes.contains(provider.inputType().getName()), provider.inputType().getName());
        }
        for (final LALOutputBuilder builder : ServiceLoader.load(LALOutputBuilder.class)) {
            assertTrue(io.contains(builder.getClass().getName()), builder.getClass().getName());
        }
        assertTrue(inputTypes.contains(LogData.class.getName()), inputTypes.toString());
    }

    @Test
    void theMessageAndToJsonValuesOfAnInputsGettersAreWalked() {
        final Set<String> protoRoots = new TreeSet<>();
        final Set<Class<?>> registered = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Event.class, protoRoots, registered);

        assertEquals(Set.of(Event.class, Named.class, Step.class), registered);
        assertEquals(Set.of(LogData.class.getName()), protoRoots);
    }

    @Test
    void thePublicTypeANonPublicInputsGetterIsCalledThroughIsRegistered() {
        final Set<Class<?>> registered = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Owner.class, new TreeSet<>(), registered);

        assertEquals(Set.of(Owner.class, Named.class), registered);
    }

    @Test
    void aGetterLalCallablesRefusesIsNotFollowed() {
        final Set<Class<?>> registered = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Plain.class, new TreeSet<>(), registered);

        assertEquals(Set.of(Plain.class), registered);
    }

    @Test
    void theValuesInAGettersArraysWildcardsAndGenericArraysAreWalked() {
        final Set<Class<?>> registered = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Holder.class, new TreeSet<>(), registered);

        assertEquals(Set.of(Holder.class, InArray.class, Bounded.class, InGenericArray.class, Step.class), registered);
    }

    @Test
    void aTypeVariableIsWalkedByItsBindingInTheHierarchyOrElseItsBounds() {
        final Set<Class<?>> bound = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Bound.class, new TreeSet<>(), bound);
        assertEquals(Set.of(Bound.class, Generic.class, BoundStep.class, Step.class), bound);

        final Set<Class<?>> unbound = new LinkedHashSet<>();
        NativeImageMetadata.collectLalValues(Generic.class, new TreeSet<>(), unbound);
        assertEquals(Set.of(Generic.class, Step.class), unbound);
    }

    private Properties rules() throws Exception {
        final Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("META-INF/lal-v2-rules.txt"), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static String prefixOf(final Properties rules, final String name) {
        for (int i = 0; rules.getProperty("rule." + i + ".name") != null; i++) {
            if (name.equals(rules.getProperty("rule." + i + ".name"))) {
                return "rule." + i + ".";
            }
        }
        throw new AssertionError("no rule " + name + " in " + rules);
    }

    public interface Named extends ToJson {
        String getName();
    }

    public static class Event implements ToJson {
        public Named getOwner() {
            return new Owner();
        }

        public List<Step> getSteps() {
            return List.of();
        }

        public LogData getLog() {
            return LogData.getDefaultInstance();
        }

        public String getId() {
            return "";
        }

        @Override
        public String toJson() {
            return "{}";
        }
    }

    public static class Step implements ToJson {
        public int getIndex() {
            return 0;
        }

        @Override
        public String toJson() {
            return "{}";
        }
    }

    static class Owner implements Named {
        @Override
        public String getName() {
            return "";
        }

        @Override
        public String toJson() {
            return "{}";
        }
    }

    public static class Plain {
        public Step getStep() {
            return new Step();
        }
    }

    public static class Holder implements ToJson {
        public InArray[] getInArray() {
            return new InArray[0];
        }

        public List<? extends Bounded> getBounded() {
            return List.of();
        }

        public List<InGenericArray>[] getInGenericArray() {
            return null;
        }

        @Override
        public String toJson() {
            return "{}";
        }
    }

    public static class InArray extends Step {
    }

    public static class Bounded extends Step {
    }

    public static class InGenericArray extends Step {
    }

    public static class Generic<T extends Step> implements ToJson {
        public T getValue() {
            return null;
        }

        public T[] getValues() {
            return null;
        }

        @Override
        public String toJson() {
            return "{}";
        }
    }

    public static class Bound extends Generic<BoundStep> {
    }

    public static class BoundStep extends Step {
    }

}
