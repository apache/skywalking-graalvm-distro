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
 *
 */

package org.apache.skywalking.oap.server.graalvm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LAL input types (Envoy ALS entries) are JSON-printed by upstream's {@code LalPayloadDebugDump}
 * and {@code EnvoyAccessLogBuilder} through protobuf's {@code JsonFormat}, whose accessor table finds
 * the generated getters and setters by reflection. Verifies that the precompiler's reflect-config
 * registers every message and enum type reachable from those inputs, plus the Builder classes, with
 * method access; a gap surfaces at runtime as {@code Generated message class ... missing method ...}
 * and a {@code jsonformat-failed} debug payload / log content.
 */
class LalInputTypeReflectionTest {
    private static final String REFLECT_CONFIG =
        "META-INF/native-image/org.apache.skywalking/oap-graalvm-distro/reflect-config.json";
    private static final String LAL_RULES = "META-INF/lal-v2-rules.txt";

    @Test
    void everyProtoTypeReachableFromLalInputsHasMethodAccess() throws Exception {
        Set<String> fullAccess = loadMethodAccessEntries();
        Set<String> builders = new HashSet<>();
        Map<String, String> registeredMessages = new HashMap<>();
        Set<String> registeredEnums = new HashSet<>();
        ClassLoader loader = getClass().getClassLoader();
        for (String name : fullAccess) {
            Class<?> clazz;
            try {
                clazz = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            if (Message.Builder.class.isAssignableFrom(clazz)) {
                builders.add(name);
            } else if (Message.class.isAssignableFrom(clazz)) {
                Descriptor descriptor = (Descriptor) clazz.getMethod("getDescriptor").invoke(null);
                registeredMessages.put(descriptor.getFullName(), name);
            } else if (clazz.isEnum() && ProtocolMessageEnum.class.isAssignableFrom(clazz)) {
                EnumDescriptor descriptor = (EnumDescriptor) clazz.getMethod("getDescriptor").invoke(null);
                registeredEnums.add(descriptor.getFullName());
            }
        }

        Set<String> inputTypes = lalInputTypes();
        assertFalse(inputTypes.isEmpty(), "no LAL input types recorded in " + LAL_RULES);
        Set<String> visited = new HashSet<>();
        Set<String> missing = new TreeSet<>();
        for (String inputType : inputTypes) {
            Class<?> clazz = Class.forName(inputType);
            assertTrue(Message.class.isAssignableFrom(clazz), inputType + " is not a protobuf message");
            Descriptor root = (Descriptor) clazz.getMethod("getDescriptor").invoke(null);
            walk(root, visited, registeredMessages, builders, registeredEnums, missing);
        }
        assertTrue(visited.size() > 10, "descriptor walk covered only " + visited.size() + " message types");
        assertTrue(missing.isEmpty(),
            "proto types reachable from the LAL input types " + inputTypes
                + " lack method access in reflect-config.json: " + missing);
    }

    /**
     * @param messages registered message types, proto full name to Java class name
     * @param builders registered {@code Message.Builder} class names
     */
    private static void walk(Descriptor descriptor, Set<String> visited, Map<String, String> messages,
                             Set<String> builders, Set<String> enums, Set<String> missing) {
        if (!visited.add(descriptor.getFullName())) {
            return;
        }
        String className = messages.get(descriptor.getFullName());
        if (className == null) {
            missing.add(descriptor.getFullName());
        } else if (!builders.contains(className + "$Builder")) {
            missing.add(className + "$Builder");
        }
        for (FieldDescriptor field : descriptor.getFields()) {
            FieldDescriptor effective = field;
            if (field.isMapField()) {
                effective = field.getMessageType().findFieldByName("value");
            }
            if (effective.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                walk(effective.getMessageType(), visited, messages, builders, enums, missing);
            } else if (effective.getJavaType() == FieldDescriptor.JavaType.ENUM
                && !enums.contains(effective.getEnumType().getFullName())) {
                missing.add(effective.getEnumType().getFullName());
            }
        }
    }

    private Set<String> loadMethodAccessEntries() throws Exception {
        Set<String> names = new HashSet<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(REFLECT_CONFIG)) {
            assertNotNull(is, REFLECT_CONFIG + " not found on the test classpath");
            JsonArray entries = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .getAsJsonArray();
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                JsonElement all = entry.get("allDeclaredMethods");
                if (all != null && all.getAsBoolean()) {
                    names.add(entry.get("name").getAsString());
                }
            }
        }
        return names;
    }

    private Set<String> lalInputTypes() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(LAL_RULES)) {
            assertNotNull(is, LAL_RULES + " not found on the test classpath");
            props.load(is);
        }
        Set<String> types = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.endsWith(".inputType") && !props.getProperty(key).isBlank()) {
                types.add(props.getProperty(key).trim());
            }
        }
        return types;
    }
}
