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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;

/**
 * Build-time tool that scans all ModuleConfig subclasses used by the fixed
 * provider set and generates a same-FQCN replacement for YamlConfigLoaderUtils.
 *
 * <p>The generated class dispatches copyProperties() by config type, using
 * Lombok-generated setters for all fields. No VarHandle, no reflection fallback.
 * All config classes must have @Setter (class-level or per-field) via the
 * *-for-graalvm repackaged modules.
 *
 * <p>Usage: {@code mvn -pl build-tools/config-generator exec:java -Dexec.args="<output-path>"}
 */
public class ConfigInitializerGenerator {

    /**
     * All ModuleProvider classes from the fixed wiring in GraalVMOAPServerStartUp.
     * Each provider's newConfigCreator() is called to discover the config type.
     */
    private static final String[] PROVIDER_CLASSES = {
        "org.apache.skywalking.oap.server.core.CoreModuleProvider",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageProvider",
        "org.apache.skywalking.oap.server.cluster.plugin.standalone.ClusterModuleStandaloneProvider",
        "org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesProvider",
        "org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationProvider",
        "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusTelemetryProvider",
        "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleProvider",
        "org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleProvider",
        "org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerModuleProvider",
        "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModuleProvider",
        "org.apache.skywalking.oap.server.receiver.register.provider.RegisterModuleProvider",
        "org.apache.skywalking.oap.server.receiver.trace.provider.TraceModuleProvider",
        "org.apache.skywalking.oap.server.receiver.jvm.provider.JVMModuleProvider",
        "org.apache.skywalking.oap.server.receiver.clr.provider.CLRModuleProvider",
        "org.apache.skywalking.oap.server.receiver.profile.provider.ProfileModuleProvider",
        "org.apache.skywalking.oap.server.receiver.asyncprofiler.provider.AsyncProfilerModuleProvider",
        "org.apache.skywalking.oap.server.receiver.pprof.provider.PprofModuleProvider",
        "org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixReceiverProvider",
        "org.apache.skywalking.aop.server.receiver.mesh.MeshReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.meter.provider.MeterReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.browser.provider.BrowserModuleProvider",
        "org.apache.skywalking.oap.server.receiver.log.provider.LogModuleProvider",
        "org.apache.skywalking.oap.server.receiver.event.EventModuleProvider",
        "org.apache.skywalking.oap.server.receiver.ebpf.provider.EBPFReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.telegraf.provider.TelegrafReceiverProvider",
        "org.apache.skywalking.oap.server.receiver.aws.firehose.AWSFirehoseReceiverModuleProvider",
        "org.apache.skywalking.oap.server.receiver.configuration.discovery.ConfigurationDiscoveryProvider",
        "org.apache.skywalking.oap.server.analyzer.agent.kafka.provider.KafkaFetcherProvider",
        "org.apache.skywalking.oap.server.fetcher.cilium.CiliumFetcherProvider",
        "org.apache.skywalking.oap.query.graphql.GraphQLQueryProvider",
        "org.apache.skywalking.oap.query.zipkin.ZipkinQueryProvider",
        "org.apache.skywalking.oap.query.promql.PromQLProvider",
        "org.apache.skywalking.oap.query.logql.LogQLProvider",
        "org.apache.skywalking.oap.query.debug.StatusQueryProvider",
        "org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleProvider",
        "org.apache.skywalking.oap.server.exporter.provider.ExporterProvider",
        "org.apache.skywalking.oap.server.health.checker.provider.HealthCheckerProvider",
        "org.apache.skywalking.oap.server.ai.pipeline.AIPipelineProvider",
    };

    /**
     * Additional config classes used internally (e.g., BanyanDB nested configs
     * that are passed to copyProperties by custom loaders).
     */
    private static final String[] EXTRA_CONFIG_CLASSES = {
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$RecordsNormal",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$RecordsLog",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Trace",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$ZipkinTrace",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$RecordsTrace",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$RecordsZipkinTrace",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$RecordsBrowserErrorLog",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$MetricsMin",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$MetricsHour",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$MetricsDay",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Metadata",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Property",
        "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage",
    };

    record FieldInfo(String name, Class<?> type, boolean hasSetter, boolean isFinal) {
    }

    record ConfigClassInfo(Class<?> configClass, String simpleName, List<FieldInfo> fields) {
    }

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : null;

        // Discover config classes from providers
        Map<String, ConfigClassInfo> configClasses = new LinkedHashMap<>();
        discoverProviderConfigs(configClasses);
        discoverExtraConfigs(configClasses);

        System.out.println("=== Config Classes Found ===");
        List<String> errors = new ArrayList<>();
        for (var entry : configClasses.entrySet()) {
            ConfigClassInfo info = entry.getValue();
            System.out.printf("  %s (%d fields)%n", entry.getKey(), info.fields.size());
            for (FieldInfo f : info.fields) {
                System.out.printf("    %-40s %-20s setter=%s final=%s%n",
                    f.name, f.type.getSimpleName(), f.hasSetter, f.isFinal);
                // Validate: non-final fields MUST have a setter
                if (!f.hasSetter && !f.isFinal) {
                    errors.add(entry.getKey() + "." + f.name
                        + " — non-final field without setter. Add @Setter to the -for-graalvm config class.");
                }
                // Validate: final non-collection fields cannot be set
                if (f.isFinal && !List.class.isAssignableFrom(f.type) && !Map.class.isAssignableFrom(f.type)) {
                    System.out.printf("      WARN: final non-collection field '%s' — will be skipped%n", f.name);
                }
            }
        }

        if (!errors.isEmpty()) {
            System.err.println("\n=== ERRORS: Fields without setters ===");
            for (String err : errors) {
                System.err.println("  " + err);
            }
            throw new IllegalStateException(errors.size()
                + " config field(s) have no setter. All non-final fields must have setters.");
        }

        // Generate the replacement class
        String generated = generateReplacementClass(configClasses);

        if (outputPath != null) {
            Path out = Path.of(outputPath);
            Files.createDirectories(out.getParent());
            Files.writeString(out, generated);
            System.out.println("\nGenerated: " + out.toAbsolutePath());
        } else {
            System.out.println("\n=== Generated YamlConfigLoaderUtils.java ===");
            System.out.println(generated);
        }
    }

    private static void discoverProviderConfigs(Map<String, ConfigClassInfo> configClasses) {
        for (String providerClassName : PROVIDER_CLASSES) {
            try {
                Class<?> providerClass = Class.forName(providerClassName);
                ModuleProvider provider = (ModuleProvider) providerClass.getDeclaredConstructor().newInstance();
                ModuleProvider.ConfigCreator<?> creator = provider.newConfigCreator();
                if (creator == null) {
                    System.out.println("  SKIP (null ConfigCreator): " + providerClassName);
                    continue;
                }
                Class<?> configType = creator.type();
                if (configType == null) {
                    System.out.println("  SKIP (null config type): " + providerClassName);
                    continue;
                }
                if (!configClasses.containsKey(configType.getName())) {
                    configClasses.put(configType.getName(), analyzeConfigClass(configType));
                }
            } catch (Exception e) {
                System.err.println("  ERROR scanning " + providerClassName + ": " + e.getMessage());
            }
        }
    }

    private static void discoverExtraConfigs(Map<String, ConfigClassInfo> configClasses) {
        for (String className : EXTRA_CONFIG_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                if (!configClasses.containsKey(clazz.getName())) {
                    configClasses.put(clazz.getName(), analyzeConfigClass(clazz));
                }
            } catch (Exception e) {
                System.err.println("  ERROR scanning " + className + ": " + e.getMessage());
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
                boolean hasSetter = hasSetterMethod(configClass, field);
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                fields.add(new FieldInfo(field.getName(), field.getType(), hasSetter, isFinal));
            }
            clazz = clazz.getSuperclass();
        }
        return new ConfigClassInfo(configClass, configClass.getSimpleName(), fields);
    }

    static boolean hasSetterMethod(Class<?> configClass, Field field) {
        String setterName = "set" + capitalize(field.getName());
        try {
            configClass.getMethod(setterName, field.getType());
            return true;
        } catch (NoSuchMethodException e) {
            // Also try with boxed type for primitives
            if (field.getType().isPrimitive()) {
                try {
                    configClass.getMethod(setterName, box(field.getType()));
                    return true;
                } catch (NoSuchMethodException e2) {
                    return false;
                }
            }
            return false;
        }
    }

    static boolean hasGetterMethod(Class<?> configClass, Field field) {
        // Try getXxx
        String getterName = "get" + capitalize(field.getName());
        try {
            Method m = configClass.getMethod(getterName);
            return m.getReturnType() != void.class;
        } catch (NoSuchMethodException e) {
            // For boolean, try isXxx
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

    static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        return primitive;
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
            String fqcn = entry.getKey();
            if (fqcn.contains("$")) {
                String enclosing = fqcn.substring(0, fqcn.indexOf('$'));
                imports.put(enclosing, enclosing);
            } else {
                imports.put(fqcn, fqcn);
            }
            for (FieldInfo field : entry.getValue().fields) {
                // Only import types used in cast expressions (fields with setters or final collections)
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
            if (field.hasSetter) {
                String setter = "set" + capitalize(field.name);
                sb.append("                    cfg.").append(setter).append("(")
                    .append(castExpression(field.type, "value")).append(");\n");
            } else if (field.isFinal && List.class.isAssignableFrom(field.type)) {
                String getter = "get" + capitalize(field.name);
                sb.append("                    cfg.").append(getter).append("().clear();\n");
                sb.append("                    cfg.").append(getter).append("().addAll((List) value);\n");
            } else if (field.isFinal) {
                // Final non-collection — cannot be set, log warning
                sb.append("                    log.warn(\"Cannot set final field '").append(field.name)
                    .append("' in {} provider of {} module\", providerName, moduleName);\n");
            } else {
                // Should not reach here — validation in main() catches this
                sb.append("                    throw new UnsupportedOperationException(\"No setter for field: ")
                    .append(field.name).append("\");\n");
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
        if (type == int.class) return "(int) " + varName;
        if (type == long.class) return "(long) " + varName;
        if (type == boolean.class) return "(boolean) " + varName;
        if (type == double.class) return "(double) " + varName;
        if (type == float.class) return "(float) " + varName;
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
