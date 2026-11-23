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
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALCallables;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LalInputDebugRenderer;
import org.apache.skywalking.oap.server.core.dsl.DslCallables;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.junit.jupiter.api.Test;

import static org.apache.skywalking.oap.server.buildtools.common.ProtobufMessages.generatedMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LAL input types (Envoy ALS entries) are JSON-printed by upstream's {@code LalPayloadDebugDump}
 * and {@code EnvoyAccessLogBuilder} through protobuf's {@code JsonFormat}, whose accessor table finds
 * the generated getters and setters by reflection, and {@code LalRuntimeHelper} reads a property typed
 * only at run time through a getter it looks up and checks against {@code LALCallables}, first built in
 * the image. Verifies that the precompiler's reflect-config registers every message and enum type
 * reachable from those inputs, plus the Builder classes, with method access (a gap surfaces at runtime
 * as {@code Generated message class ... missing method ...}, or as a property read as missing), and the
 * public methods of every class {@code LALCallables} lists.
 */
class LalInputTypeReflectionTest {
    private static final String REFLECT_CONFIG =
        "META-INF/native-image/org.apache.skywalking/oap-graalvm-distro/reflect-config.json";

    @Test
    void everyProtoTypeReachableFromLalInputsHasMethodAccess() throws Exception {
        Set<String> inputTypes = ruleInputTypes();
        assertFalse(inputTypes.isEmpty(), "no LAL input types recorded in the rule registry");
        // The type each LALSourceTypeProvider declares its layer's logs arrive as, and the log rules run on by default.
        for (LALSourceTypeProvider provider : ServiceLoader.load(LALSourceTypeProvider.class)) {
            inputTypes.add(provider.inputType().getName());
        }
        inputTypes.add(LogData.class.getName());
        assertEverythingReachableHasMethodAccess(inputTypes);
    }

    /**
     * The debug renderers JSON-print their input types whichever rules ran on them: a parser-only rule of a layer runs on
     * every entry the layer receives, of a protocol no rule names too.
     */
    @Test
    void everyProtoTypeTheLalDebugRenderersPrintHasMethodAccess() throws Exception {
        Set<String> inputTypes = new TreeSet<>();
        for (LalInputDebugRenderer renderer : ServiceLoader.load(LalInputDebugRenderer.class)) {
            inputTypes.add(renderer.inputType().getName());
        }
        assertFalse(inputTypes.isEmpty(), "no LalInputDebugRenderer on the class path");
        assertEverythingReachableHasMethodAccess(inputTypes);
    }

    private void assertEverythingReachableHasMethodAccess(Set<String> inputTypes) throws Exception {
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

        Set<String> visited = new HashSet<>();
        Set<String> missing = new TreeSet<>();
        for (String inputType : inputTypes) {
            Class<?> message = generatedMessage(Class.forName(inputType));
            if (message == null) {
                // DynamicMessage or a POJO: JsonFormat reads no generated accessors from it
                continue;
            }
            Descriptor root = (Descriptor) message.getMethod("getDescriptor").invoke(null);
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

    /**
     * The values LAL gives every rule are reflected on like an input's when a rule reads them untyped:
     * {@code def tc = parsed.getMap().getOrDefault("traceContext", log.traceContext)} then {@code tc.otlpSpanId} calls
     * {@code LogMetadata.TraceContext#getOtlpSpanId} by reflection, whatever the rules' input types.
     */
    @Test
    void theLogsOwnValuesHaveTheirPublicMethodsRegistered() throws Exception {
        Set<String> publicMethods = loadEntries("allPublicMethods");
        for (Class<?> type : new Class<?>[] {LogMetadata.class, LogMetadata.TraceContext.class}) {
            assertTrue(publicMethods.contains(type.getName()), type.getName() + " lacks allPublicMethods");
        }
        assertNotNull(LogMetadata.TraceContext.class.getMethod("getOtlpSpanId"));
    }

    @Test
    void everyClassLalCallablesListsHasItsPublicMethodsRegistered() throws Exception {
        DslCallables callables = LALCallables.get();
        Set<Class<?>> listed = new HashSet<>();
        listed.add(Object.class);
        listed.addAll(callables.declaredMethods().keySet());
        listed.addAll(callables.declaredStatics().keySet());
        Set<String> publicMethods = loadEntries("allPublicMethods");
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : listed) {
            if (!publicMethods.contains(type.getName())) {
                missing.add(type.getName());
            }
        }
        assertTrue(missing.isEmpty(), "classes LALCallables lists lack allPublicMethods in reflect-config.json: "
            + missing);
        assertEquals(Map.of(), callables.declaredStaticFields(), "LALCallables lists static fields: check their entries");
    }

    private Set<String> loadMethodAccessEntries() throws Exception {
        return loadEntries("allDeclaredMethods");
    }

    private Set<String> loadEntries(String access) throws Exception {
        Set<String> names = new HashSet<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(REFLECT_CONFIG)) {
            assertNotNull(is, REFLECT_CONFIG + " not found on the test classpath");
            JsonArray entries = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .getAsJsonArray();
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                JsonElement all = entry.get(access);
                if (all != null && all.getAsBoolean()) {
                    names.add(entry.get("name").getAsString());
                }
            }
        }
        return names;
    }

    private Set<String> ruleInputTypes() {
        Set<String> types = new TreeSet<>();
        for (LalRule rule : CompiledRules.lalRules()) {
            if (rule.getInputType() != null) {
                types.add(rule.getInputType().getName());
            }
            // The type upstream passes; with a parser, a builder still prints it.
            if (!rule.getRequestedInputType().isEmpty()) {
                types.add(rule.getRequestedInputType());
            }
        }
        return types;
    }
}
