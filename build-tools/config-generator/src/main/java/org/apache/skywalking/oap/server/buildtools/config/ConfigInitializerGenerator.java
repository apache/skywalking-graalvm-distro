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

package org.apache.skywalking.oap.server.buildtools.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.skywalking.oap.server.buildtools.common.ProviderDiscovery;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;

/**
 * Build-time tool that discovers all ModuleConfig subclasses from accepted
 * providers (via SPI + AcceptedModules filter) and generates a same-FQCN
 * replacement for YamlConfigLoaderUtils.
 *
 * <p>The generated class dispatches copyProperties() by config type, using
 * Lombok-generated setters for all fields. No VarHandle, no reflection fallback.
 * All config classes must have @Setter (class-level or per-field) via the
 * *-for-graalvm repackaged modules.
 *
 * <p>Usage: {@code mvn -pl build-tools/config-generator exec:java
 *   -Dexec.args="<output-path> [manifest-path]"}
 *
 * <p>When manifest-path is provided, writes a module-config-classes.txt file
 * listing all discovered config class FQCNs (one per line) for the precompiler's
 * reflect-config.json generation.
 */
public class ConfigInitializerGenerator {

    // Provider discovery is now done via ServiceLoader + AcceptedModules filter.
    // See build-tools/build-common for the accepted module+provider name list.
    // Nested config classes (e.g., BanyanDB inner classes) are discovered
    // automatically by scanning declared inner classes of ModuleConfig types.

    record FieldInfo(String name, Class<?> type, boolean isFinal) {
    }

    record ConfigClassInfo(Class<?> configClass, String simpleName, List<FieldInfo> fields) {
    }

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : null;

        // Discover config classes from providers, then their nested inner classes
        Map<String, ConfigClassInfo> configClasses = new LinkedHashMap<>();
        discoverProviderConfigs(configClasses);
        discoverNestedConfigs(configClasses);

        System.out.println("=== Config Classes Found ===");
        for (var entry : configClasses.entrySet()) {
            ConfigClassInfo info = entry.getValue();
            System.out.printf("  %s (%d fields)%n", entry.getKey(), info.fields.size());
            for (FieldInfo f : info.fields) {
                System.out.printf("    %-40s %-20s final=%s%n",
                    f.name, f.type.getSimpleName(), f.isFinal);
                if (f.isFinal && !List.class.isAssignableFrom(f.type) && !Map.class.isAssignableFrom(f.type)) {
                    System.out.printf("      WARN: final non-collection field '%s' — will be skipped%n", f.name);
                }
            }
        }

        // Generate the replacement class
        String generated = generateReplacementClass(configClasses);

        if (outputPath != null) {
            Path out = Path.of(outputPath);
            Files.createDirectories(out.getParent());
            Files.writeString(out, generated);
            System.out.println("\nGenerated: " + out.toAbsolutePath());

            // Write config class manifest for precompiler's reflect-config.json generation.
            String manifestPath = args.length > 1 ? args[1] : null;
            if (manifestPath != null) {
                Path manifest = Path.of(manifestPath);
                Files.createDirectories(manifest.getParent());
                List<String> configClassNames = configClasses.keySet().stream().sorted().toList();
                Files.write(manifest, configClassNames, StandardCharsets.UTF_8);
                System.out.println("Generated manifest: " + manifest.toAbsolutePath()
                    + " (" + configClassNames.size() + " config classes)");
            }
        } else {
            System.out.println("\n=== Generated YamlConfigLoaderUtils.java ===");
            System.out.println(generated);
        }
    }

    private static void discoverProviderConfigs(Map<String, ConfigClassInfo> configClasses) {
        for (ModuleProvider provider : ProviderDiscovery.discoverAccepted()) {
            try {
                ModuleProvider.ConfigCreator<?> creator = provider.newConfigCreator();
                if (creator == null) {
                    System.out.println("  SKIP (null ConfigCreator): " + provider.getClass().getName());
                    continue;
                }
                Class<?> configType = creator.type();
                if (configType == null) {
                    System.out.println("  SKIP (null config type): " + provider.getClass().getName());
                    continue;
                }
                if (!configClasses.containsKey(configType.getName())) {
                    configClasses.put(configType.getName(), analyzeConfigClass(configType));
                }
            } catch (Exception e) {
                System.err.println("  ERROR scanning " + provider.getClass().getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Discover nested config classes by scanning declared inner classes of all
     * discovered ModuleConfig subclasses. Includes non-enum, non-interface,
     * non-abstract inner classes (e.g., BanyanDBStorageConfig$Global, $Stage, etc.).
     */
    private static void discoverNestedConfigs(Map<String, ConfigClassInfo> configClasses) {
        List<Class<?>> parentConfigs = configClasses.values().stream()
            .map(ConfigClassInfo::configClass).toList();
        for (Class<?> parent : parentConfigs) {
            for (Class<?> inner : parent.getDeclaredClasses()) {
                if (inner.isEnum() || inner.isInterface()
                        || Modifier.isAbstract(inner.getModifiers())) {
                    continue;
                }
                if (!configClasses.containsKey(inner.getName())) {
                    configClasses.put(inner.getName(), analyzeConfigClass(inner));
                    System.out.println("  Discovered nested config: " + inner.getName());
                }
            }
        }
    }

    static ConfigClassInfo analyzeConfigClass(Class<?> configClass) {
        List<FieldInfo> fields = new ArrayList<>();
        Class<?> clazz = configClass;
        // Walk up to ModuleConfig (exclusive) or Object
        while (clazz != null && clazz != ModuleConfig.class && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                fields.add(new FieldInfo(field.getName(), field.getType(), isFinal));
            }
            clazz = clazz.getSuperclass();
        }
        return new ConfigClassInfo(configClass, configClass.getSimpleName(), fields);
    }

    static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ======================== Code Generation ========================

    private static String generateReplacementClass(Map<String, ConfigClassInfo> configClasses) {
        StringBuilder sb = new StringBuilder();
        sb.append(LICENSE_HEADER);
        sb.append("""

            package org.apache.skywalking.oap.server.library.util;

            import java.util.ArrayList;
            import java.util.Enumeration;
            import java.util.List;
            import java.util.Map;
            import java.util.Properties;
            import lombok.extern.slf4j.Slf4j;
            import org.yaml.snakeyaml.Yaml;

            """);

        // Collect all imports needed for config classes and their field types
        TreeMap<String, String> imports = new TreeMap<>();
        for (var entry : configClasses.entrySet()) {
            // Skip imports for config classes with no fields (not used in generated code)
            if (entry.getValue().fields.isEmpty()) {
                continue;
            }
            String fqcn = entry.getKey();
            if (fqcn.contains("$")) {
                String enclosing = fqcn.substring(0, fqcn.indexOf('$'));
                imports.put(enclosing, enclosing);
            } else {
                imports.put(fqcn, fqcn);
            }
            for (FieldInfo field : entry.getValue().fields) {
                // Only import types used in cast expressions (non-final fields or final collections)
                if (field.isFinal && !List.class.isAssignableFrom(field.type)) {
                    continue;
                }
                Class<?> fieldType = field.type;
                if (fieldType.isPrimitive() || fieldType == String.class
                    || fieldType == Object.class || fieldType == List.class
                    || fieldType == Map.class || fieldType == Properties.class
                    || fieldType.getPackageName().equals("java.lang")) {
                    continue;
                }
                String fieldFqcn = fieldType.getName();
                if (fieldFqcn.contains("$")) {
                    String enclosing = fieldFqcn.substring(0, fieldFqcn.indexOf('$'));
                    imports.put(enclosing, enclosing);
                } else {
                    imports.put(fieldFqcn, fieldFqcn);
                }
            }
        }
        for (String imp : imports.values()) {
            sb.append("import ").append(imp).append(";\n");
        }

        sb.append("""

            /**
             * GraalVM replacement for upstream YamlConfigLoaderUtils.
             * Original: skywalking/oap-server/server-library/library-util/src/main/java/.../util/YamlConfigLoaderUtils.java
             * Lives in oap-graalvm-server (not in library-util-for-graalvm) because it imports config types from
             * 30+ upstream modules. The original .class is excluded from library-util-for-graalvm via shade filter.
             *
             * <p>Change: Complete rewrite. Uses type-dispatch with Lombok @Setter methods to set ModuleConfig fields.
             * No reflection (Field.setAccessible + field.set), no VarHandle. Reports error for unknown config types.
             * <p>Why: Field.setAccessible is incompatible with GraalVM native image without reflect-config.json for every field.
             *
             * <p>Generated by: build-tools/config-generator
             */
            @Slf4j
            public class YamlConfigLoaderUtils {

            """);

        // Generate replacePropertyAndLog (unchanged from upstream)
        sb.append("""
                public static void replacePropertyAndLog(final String propertyName,
                                                         final Object propertyValue,
                                                         final Properties target,
                                                         final Object providerName,
                                                         final Yaml yaml) {
                    final String valueString = PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(
                        String.valueOf(propertyValue), target);
                    if (valueString.trim().length() == 0) {
                        target.replace(propertyName, valueString);
                        log.info("Provider={} config={} has been set as an empty string", providerName, propertyName);
                    } else {
                        final Object replaceValue = convertValueString(valueString, yaml);
                        if (replaceValue != null) {
                            target.replace(propertyName, replaceValue);
                        }
                    }
                }

                public static Object convertValueString(final String valueString, final Yaml yaml) {
                    try {
                        Object replaceValue = yaml.load(valueString);
                        if (replaceValue instanceof String || replaceValue instanceof Integer || replaceValue instanceof Long || replaceValue instanceof Boolean || replaceValue instanceof ArrayList) {
                            return replaceValue;
                        } else {
                            return valueString;
                        }
                    } catch (Exception e) {
                        log.warn("yaml convert value type error, use origin values string. valueString={}", valueString, e);
                        return valueString;
                    }
                }

            """);

        // Generate copyProperties dispatcher
        generateCopyPropertiesDispatcher(sb, configClasses);

        // Generate per-type copy methods
        for (var entry : configClasses.entrySet()) {
            generateTypeCopyMethod(sb, entry.getValue());
        }

        sb.append("}\n");

        return sb.toString();
    }

    private static void generateCopyPropertiesDispatcher(StringBuilder sb,
                                                          Map<String, ConfigClassInfo> configClasses) {
        sb.append("    public static void copyProperties(final Object dest,\n");
        sb.append("                                      final Properties src,\n");
        sb.append("                                      final String moduleName,\n");
        sb.append("                                      final String providerName) throws IllegalAccessException {\n");
        sb.append("        if (dest == null) {\n");
        sb.append("            return;\n");
        sb.append("        }\n");

        boolean first = true;
        for (var entry : configClasses.entrySet()) {
            ConfigClassInfo info = entry.getValue();
            if (info.fields.isEmpty()) {
                continue;
            }
            String typeRef = javaClassRef(info.configClass);
            if (first) {
                sb.append("        if (dest instanceof ").append(typeRef).append(") {\n");
                first = false;
            } else {
                sb.append("        } else if (dest instanceof ").append(typeRef).append(") {\n");
            }
            sb.append("            copyTo").append(methodSuffix(info))
                .append("((").append(typeRef).append(") dest, src, moduleName, providerName);\n");
        }
        if (!first) {
            sb.append("        } else {\n");
        } else {
            sb.append("        {\n");
        }
        sb.append("            throw new IllegalArgumentException(\"Unknown config type: \"\n");
        sb.append("                + dest.getClass().getName()\n");
        sb.append("                + \" in \" + providerName + \" provider of \" + moduleName + \" module.\"\n");
        sb.append("                + \" Add it to ConfigInitializerGenerator and regenerate.\");\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    private static void generateTypeCopyMethod(StringBuilder sb, ConfigClassInfo info) {
        if (info.fields.isEmpty()) {
            return;
        }
        String typeRef = javaClassRef(info.configClass);
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    private static void copyTo").append(methodSuffix(info)).append("(\n");
        sb.append("            final ").append(typeRef).append(" cfg, final Properties src,\n");
        sb.append("            final String moduleName, final String providerName) {\n");
        sb.append("        final Enumeration<?> propertyNames = src.propertyNames();\n");
        sb.append("        while (propertyNames.hasMoreElements()) {\n");
        sb.append("            final String key = (String) propertyNames.nextElement();\n");
        sb.append("            final Object value = src.get(key);\n");
        sb.append("            log.debug(\"{}.{} config: {} = {}\", moduleName, providerName, key, value);\n");
        sb.append("            switch (key) {\n");

        for (FieldInfo field : info.fields) {
            sb.append("                case \"").append(field.name).append("\":\n");
            if (field.isFinal && List.class.isAssignableFrom(field.type)) {
                // Final list — mutate in place via getter
                String getter = "get" + capitalize(field.name);
                sb.append("                    cfg.").append(getter).append("().clear();\n");
                sb.append("                    cfg.").append(getter).append("().addAll((List) value);\n");
            } else if (field.isFinal) {
                // Final non-collection — cannot be set, log warning
                sb.append("                    log.warn(\"Cannot set final field '").append(field.name)
                    .append("' in {} provider of {} module\", providerName, moduleName);\n");
            } else {
                // Non-final field — use setter. The for-graalvm modules add @Setter;
                // javac in oap-graalvm-server catches missing setters at compile time.
                String setter = "set" + capitalize(field.name);
                sb.append("                    cfg.").append(setter).append("(")
                    .append(castExpression(field.type, "value")).append(");\n");
            }
            sb.append("                    break;\n");
        }

        sb.append("                default:\n");
        sb.append("                    log.warn(\"{} setting is not supported in {} provider of {} module\",\n");
        sb.append("                        key, providerName, moduleName);\n");
        sb.append("                    break;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    // ======================== Helpers ========================

    private static String methodSuffix(ConfigClassInfo info) {
        return info.simpleName.replace("$", "_");
    }

    static String javaClassRef(Class<?> clazz) {
        if (clazz.getEnclosingClass() != null) {
            return javaClassRef(clazz.getEnclosingClass()) + "." + clazz.getSimpleName();
        }
        return clazz.getSimpleName();
    }

    private static String castExpression(Class<?> type, String varName) {
        if (type == int.class) return "((Number) " + varName + ").intValue()";
        if (type == long.class) return "((Number) " + varName + ").longValue()";
        if (type == boolean.class) return "(boolean) " + varName;
        if (type == double.class) return "((Number) " + varName + ").doubleValue()";
        if (type == float.class) return "((Number) " + varName + ").floatValue()";
        if (type == String.class) return "(String) " + varName;
        return "(" + javaClassRef(type) + ") " + varName;
    }

    private static final String LICENSE_HEADER = """
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
        """;
}
