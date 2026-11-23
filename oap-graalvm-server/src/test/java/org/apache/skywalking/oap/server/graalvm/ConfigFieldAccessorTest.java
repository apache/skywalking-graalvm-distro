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

package org.apache.skywalking.oap.server.graalvm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that every field of the config classes the generated YamlConfigLoaderUtils loads that no setter writes is
 * listed for {@code Field.set} and found (a final instance one writable) in the native image, and that every field has a
 * getter or another accessor.
 */
class ConfigFieldAccessorTest {

    private static final String REFLECT_CONFIG =
        "META-INF/native-image/org.apache.skywalking/oap-graalvm-distro/reflect-config.json";

    /** The fields the config-generator's loader writes through {@code Field.set}. */
    private static final Path SET_BY_FIELD = Path.of(System.getProperty("user.dir")).getParent()
        .resolve("build-tools/precompiler/src/main/resources/META-INF/module-config-field-set.txt");

    /** The config classes the config-generator wrote the loader for. */
    private static final Path CONFIG_CLASSES = Path.of(System.getProperty("user.dir")).getParent()
        .resolve("build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt");

    /**
     * Fields upstream reads through another accessor than a getter:
     * - virtualPeers: derived from noUpstreamRealAddressAgents, read through shouldIgnorePeerIPDue2Virtual()
     * - meterAnalyzerActiveFiles: read through meterAnalyzerActiveFileNames() (parsed list)
     * - meterConfigs: loaded and read through malConfigs()
     * - serviceMetaInfoFactory: final, read through serviceMetaInfoFactory()
     */
    private static final Set<String> CUSTOM_ACCESSOR_FIELDS = Set.of(
        "AnalyzerModuleConfig.virtualPeers",
        "AnalyzerModuleConfig.meterAnalyzerActiveFiles",
        "LogAnalyzerModuleConfig.meterConfigs",
        "EnvoyMetricReceiverConfig.serviceMetaInfoFactory"
    );

    /**
     * The binder writes a field no setter writes (none declared, or a final or static field) through {@code Field.set},
     * as upstream: the config-generator lists every such field (on a config class or a superclass up to
     * {@code ModuleConfig}), and the native image finds each one listed, a final instance field writable, as
     * {@code reflect-config.json} registers it from that list.
     */
    @Test
    void everyFieldSetThroughFieldSetIsInTheNativeImage() throws Exception {
        final Map<String, Boolean> registered = new TreeMap<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(REFLECT_CONFIG)) {
            assertNotNull(is, REFLECT_CONFIG + " not found on the test classpath");
            for (JsonElement element : JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                                                 .getAsJsonArray()) {
                final JsonObject entry = element.getAsJsonObject();
                if (entry.has("fields")) {
                    for (JsonElement field : entry.getAsJsonArray("fields")) {
                        final JsonObject f = field.getAsJsonObject();
                        registered.merge(entry.get("name").getAsString() + "#" + f.get("name").getAsString(),
                            f.has("allowWrite") && f.get("allowWrite").getAsBoolean(), Boolean::logicalOr);
                    }
                }
            }
        }
        // What each field is, from the field itself: the manifest and reflect-config.json are each checked against it
        final Map<String, String> expected = new TreeMap<>();
        final Map<String, Boolean> writable = new TreeMap<>();
        for (Class<?> configClass : discoverConfigClasses().values()) {
            // As upstream's getDeclaredField finds a key: the subclass's field of the name first
            final Set<String> names = new HashSet<>();
            for (Class<?> c = configClass; c != null && c != ModuleConfig.class && c != Object.class;
                 c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    final boolean isStatic = Modifier.isStatic(field.getModifiers());
                    final boolean isFinal = Modifier.isFinal(field.getModifiers());
                    if (!field.isSynthetic() && names.add(field.getName())
                        && (isFinal || isStatic || !hasSetter(configClass, field))) {
                        final String key = c.getName() + "#" + field.getName();
                        expected.put(key, key + (isStatic ? " static" : "") + (isFinal ? " final" : ""));
                        writable.put(key, isFinal && !isStatic);
                    }
                }
            }
        }
        assertEquals("org.apache.skywalking.oap.server.core.CoreModuleConfig#downsampling final",
            expected.get("org.apache.skywalking.oap.server.core.CoreModuleConfig#downsampling"));
        assertEquals("org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig#DEFAULT_HTTP_TIMEOUT_SECONDS"
            + " static final",
            expected.get("org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig#DEFAULT_HTTP_TIMEOUT_SECONDS"));
        final List<String> listed = new ArrayList<>();
        for (String line : Files.readAllLines(SET_BY_FIELD)) {
            if (!line.isBlank()) {
                listed.add(line.trim());
            }
        }
        assertEquals(new ArrayList<>(expected.values()), listed,
            "the fields set through Field.set, with their modifiers, the config-generator lists");
        for (Map.Entry<String, Boolean> field : writable.entrySet()) {
            assertTrue(registered.containsKey(field.getKey()), field.getKey() + " is not in reflect-config.json");
            assertEquals(field.getValue(), registered.get(field.getKey()), field.getKey() + " allowWrite");
        }
    }

    @Test
    void allFieldsHaveGetterOrCustomAccessor() throws Exception {
        Map<String, Class<?>> configClasses = discoverConfigClasses();

        List<String> errors = new ArrayList<>();

        for (var entry : configClasses.entrySet()) {
            Class<?> configClass = entry.getValue();
            Class<?> clazz = configClass;
            while (clazz != null && clazz != ModuleConfig.class && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    String fieldKey = configClass.getSimpleName() + "." + field.getName();
                    if (CUSTOM_ACCESSOR_FIELDS.contains(fieldKey)) {
                        continue;
                    }
                    if (!hasGetter(configClass, field)) {
                        errors.add(configClass.getName() + "." + field.getName() + " — missing getter/is");
                    }
                }
                clazz = clazz.getSuperclass();
            }
        }

        if (!errors.isEmpty()) {
            fail("Config fields missing getter/is (not in custom accessor whitelist):\n  "
                + String.join("\n  ", errors));
        }
    }

    private static Map<String, Class<?>> discoverConfigClasses() throws Exception {
        final Map<String, Class<?>> configClasses = new LinkedHashMap<>();
        for (final String name : Files.readAllLines(CONFIG_CLASSES)) {
            if (!name.isBlank()) {
                configClasses.put(name, Class.forName(name.trim()));
            }
        }
        return configClasses;
    }

    /** The setter the generated loader calls, {@code set<Name>(<field type>)}, as the config-generator decides it. */
    private static boolean hasSetter(Class<?> configClass, Field field) {
        try {
            final Method setter = configClass.getMethod("set" + capitalize(field.getName()), field.getType());
            return !Modifier.isStatic(setter.getModifiers())
                && field.getDeclaringClass().isAssignableFrom(setter.getDeclaringClass());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasGetter(Class<?> configClass, Field field) {
        String getterName = "get" + capitalize(field.getName());
        try {
            Method m = configClass.getMethod(getterName);
            return m.getReturnType() != void.class;
        } catch (NoSuchMethodException e) {
            if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                String isName = "is" + capitalize(field.getName());
                try {
                    Method m = configClass.getMethod(isName);
                    return m.getReturnType() != void.class;
                } catch (NoSuchMethodException e2) {
                    return false;
                }
            }
            return false;
        }
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
