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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.apache.skywalking.oap.server.buildtools.common.ProviderDiscovery;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;

/**
 * Build-time tool that discovers all ModuleConfig subclasses from accepted
 * providers (via SPI + AcceptedModules filter) and generates a same-FQCN
 * replacement for YamlConfigLoaderUtils.
 *
 * <p>The generated class dispatches copyProperties() by config type. A field its class has a setter for is set through
 * it, the value checked and converted as upstream's Field.set does (ConfigFieldValues). A field without one, a final
 * or a static field among them, is written through Field.set itself, as upstream writes every field
 * (ConfigFieldValues.setByField).
 *
 * <p>Usage: {@code mvn -pl build-tools/config-generator exec:java
 *   -Dexec.args="<output-path> [manifest-path]"}
 *
 * <p>When manifest-path is provided, writes a module-config-classes.txt file listing all discovered config class
 * FQCNs (one per line), and module-config-field-set.txt next to it listing every field the loader writes through
 * Field.set, for the precompiler's reflect-config.json generation (a final instance field writable).
 */
public class ConfigInitializerGenerator {

    /**
     * @param owner the declaring class's name, as {@code Field.set} names a field it refuses a value for
     */
    record FieldInfo(String name, Class<?> type, boolean isFinal, boolean isStatic, String owner, boolean hasSetter) {
        /**
         * @return whether no setter writes the field, so the loader goes through Field.set, as upstream
         */
        boolean isSetByField() {
            return isFinal || isStatic || !hasSetter;
        }

    }

    record ConfigClassInfo(Class<?> configClass, String simpleName, List<FieldInfo> fields) {
    }

    /**
     * Written next to the config class manifest: every field the loader writes through Field.set (no setter writes it:
     * none declared, final or static; on a config class or a superclass up to ModuleConfig), as
     * {@code declaringClass#field}, then {@code static} and {@code final} where they apply.
     */
    static final String SET_BY_FIELD_MANIFEST = "module-config-field-set.txt";

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : null;

        // Discover config classes from providers, then their nested inner classes
        Map<String, ConfigClassInfo> configClasses = new LinkedHashMap<>();
        discoverProviderConfigs(configClasses);
        discoverNestedConfigs(configClasses);
        configClasses = ordered(configClasses);

        System.out.println("=== Config Classes Found ===");
        for (var entry : configClasses.entrySet()) {
            ConfigClassInfo info = entry.getValue();
            System.out.printf("  %s (%d fields)%n", entry.getKey(), info.fields.size());
            for (FieldInfo f : info.fields) {
                System.out.printf("    %-40s %-20s final=%s%n",
                    f.name, f.type.getSimpleName(), f.isFinal);
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
                // Every field the loader reaches through Field.set, which the native image finds only when registered,
                // and writes, when final, only when registered as writable; the precompiler, whose class path lacks
                // some config classes, registers them from this list
                List<String> setByField = configClasses.values().stream()
                    .flatMap(info -> info.fields.stream())
                    .filter(FieldInfo::isSetByField)
                    .map(f -> f.owner() + "#" + f.name() + (f.isStatic() ? " static" : "") + (f.isFinal() ? " final" : ""))
                    .distinct().sorted().toList();
                Path setByFieldManifest = manifest.resolveSibling(SET_BY_FIELD_MANIFEST);
                Files.write(setByFieldManifest, setByField, StandardCharsets.UTF_8);
                System.out.println("Generated manifest: " + setByFieldManifest.toAbsolutePath()
                    + " (" + setByField.size() + " fields set through Field.set)");
            }
        } else {
            System.out.println("\n=== Generated YamlConfigLoaderUtils.java ===");
            System.out.println(generated);
        }
    }

    /**
     * Classpath order would otherwise decide the output. A subclass goes before its superclass, so the instanceof
     * dispatch reaches the most specific branch; then by name.
     */
    private static Map<String, ConfigClassInfo> ordered(Map<String, ConfigClassInfo> discovered) {
        Map<String, ConfigClassInfo> ordered = new LinkedHashMap<>();
        discovered.values().stream()
                  .sorted(Comparator.comparingInt((ConfigClassInfo info) -> -inheritanceDepth(info.configClass()))
                                    .thenComparing(info -> info.configClass().getName()))
                  .forEach(info -> ordered.put(info.configClass().getName(), info));
        return ordered;
    }

    private static int inheritanceDepth(Class<?> type) {
        int depth = 0;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            depth++;
        }
        return depth;
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
                throw new IllegalStateException("Cannot read the config type of " + provider.getClass().getName(), e);
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
        Set<String> names = new HashSet<>();
        Class<?> clazz = configClass;
        // Walk up to ModuleConfig (exclusive, it declares none) or Object, as upstream's getDeclaredField looks a key
        // up: every field, a static one too, the subclass's first.
        while (clazz != null && clazz != ModuleConfig.class && clazz != Object.class) {
            // By name: getDeclaredFields() has no specified order, and the output must not vary.
            Field[] declared = clazz.getDeclaredFields();
            Arrays.sort(declared, Comparator.comparing(Field::getName));
            for (Field field : declared) {
                if (field.isSynthetic() || !names.add(field.getName())) {
                    continue;
                }
                fields.add(new FieldInfo(field.getName(), field.getType(), Modifier.isFinal(field.getModifiers()),
                    Modifier.isStatic(field.getModifiers()), clazz.getName(), hasSetter(configClass, field)));
            }
            clazz = clazz.getSuperclass();
        }
        return new ConfigClassInfo(configClass, configClass.getSimpleName(), fields);
    }

    /**
     * @return whether the class has the setter the generated loader would call, {@code set<Name>(<field type>)}, on the
     *     field's own class or a subclass of it: a superclass's setter writes the superclass's field, not one a subclass
     *     redeclares
     */
    static boolean hasSetter(final Class<?> configClass, final Field field) {
        try {
            final Method setter = configClass.getMethod("set" + capitalize(field.getName()), field.getType());
            return !Modifier.isStatic(setter.getModifiers())
                && field.getDeclaringClass().isAssignableFrom(setter.getDeclaringClass());
        } catch (NoSuchMethodException e) {
            return false;
        }
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
            import org.apache.skywalking.oap.server.graalvm.ConfigFieldValues;
            import org.yaml.snakeyaml.Yaml;

            """);

        // Collect all imports needed for config classes and their field types
        TreeMap<String, String> imports = new TreeMap<>();
        for (var entry : configClasses.entrySet()) {
            // Import every config class for the dispatcher instanceof check (incl. empty ones).
            String fqcn = entry.getKey();
            if (fqcn.contains("$")) {
                String enclosing = fqcn.substring(0, fqcn.indexOf('$'));
                imports.put(enclosing, enclosing);
            } else {
                imports.put(fqcn, fqcn);
            }
            for (FieldInfo field : entry.getValue().fields) {
                // Only import types used in cast expressions (fields set through their setters)
                if (field.isSetByField()) {
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
             * <p>Change: Complete rewrite. Dispatches by config type and sets a field through its class's setter,
             * converting each value as Field.set does (a primitive widening to the field's type, nothing narrowing).
             * A field no setter writes (a final or static one among them) is written through Field.set as upstream,
             * registered in reflect-config.json (a final instance field writable). Reports error for unknown config types.
             * <p>Why: a setter call needs no reflection at run time and is checked when the image compiles.
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
            String typeRef = javaClassRef(info.configClass);
            if (first) {
                sb.append("        if (dest instanceof ").append(typeRef).append(") {\n");
                first = false;
            } else {
                sb.append("        } else if (dest instanceof ").append(typeRef).append(") {\n");
            }
            if (info.fields.isEmpty()) {
                // Empty config still reaches copyProperties() (its provider returns a non-null
                // ConfigCreator), so it needs a branch or it hits the "unknown type" throw. Every key
                // given is one it does not have: upstream warns about each.
                sb.append("            // ").append(info.simpleName).append(" has no configurable fields.\n");
                sb.append("            final Enumeration<?> propertyNames = src.propertyNames();\n");
                sb.append("            while (propertyNames.hasMoreElements()) {\n");
                sb.append("                log.warn(\"{} setting is not supported in {} provider of {} module\",\n");
                sb.append("                    propertyNames.nextElement(), providerName, moduleName);\n");
                sb.append("            }\n");
            } else {
                sb.append("            copyTo").append(methodSuffix(info))
                    .append("((").append(typeRef).append(") dest, src, moduleName, providerName);\n");
            }
        }
        if (!first) {
            sb.append("        } else {\n");
        } else {
            sb.append("        {\n");
        }
        sb.append("            throw new IllegalArgumentException(\"Unknown config type: \"\n");
        sb.append("                + dest.getClass().getName()\n");
        sb.append("                + \" in \" + providerName + \" provider of \" + moduleName + \" module.\"\n");
        sb.append("                + \" Add its provider to AcceptedModules and regenerate this class.\");\n");
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
        sb.append("            final String moduleName, final String providerName) throws IllegalAccessException {\n");
        sb.append("        final Enumeration<?> propertyNames = src.propertyNames();\n");
        sb.append("        while (propertyNames.hasMoreElements()) {\n");
        sb.append("            final String key = (String) propertyNames.nextElement();\n");
        sb.append("            final Object value = src.get(key);\n");
        sb.append("            switch (key) {\n");

        for (FieldInfo field : info.fields) {
            sb.append("                case \"").append(field.name).append("\":\n");
            if (field.isSetByField()) {
                // No setter writes the field (none declared, or a final or static field): Field.set does, as upstream,
                // which takes a final field's own list back unchanged, a null too, refuses a value of another type with
                // its own exception, and a static final field with IllegalAccessException
                sb.append("                    ConfigFieldValues.setByField(cfg, \"").append(field.name)
                    .append("\", value);\n");
            } else {
                // The class's own setter, set<Name>(<field type>)
                String setter = "set" + capitalize(field.name);
                sb.append("                    cfg.").append(setter).append("(")
                    .append(castExpression(field, "value")).append(");\n");
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

    /**
     * The value converted as upstream's {@code Field.set} converts it: a primitive field takes its wrapper or a value of
     * a primitive type that widens to it, a reference field an instance of its type or null; anything else is refused
     * with {@code Field.set}'s {@code IllegalArgumentException}.
     */
    private static String castExpression(FieldInfo field, String varName) {
        final Class<?> type = field.type();
        final String name = "\"" + field.owner() + "." + field.name() + "\"";
        if (type.isPrimitive()) {
            return "ConfigFieldValues." + type.getName() + "Value(" + varName + ", " + name + ")";
        }
        return "ConfigFieldValues.referenceValue(" + varName + ", " + javaClassRef(type) + ".class, " + name + ")";
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
