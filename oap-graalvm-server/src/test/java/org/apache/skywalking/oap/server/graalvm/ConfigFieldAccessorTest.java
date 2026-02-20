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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that all ModuleConfig fields used by the fixed provider set have
 * setter methods. This ensures YamlConfigLoaderUtils can set all config fields
 * without reflection.
 *
 * <p>Also verifies getter/is methods exist for all fields, except for known
 * fields that use custom accessors instead of standard Lombok-generated getters.
 */
class ConfigFieldAccessorTest {

    /**
     * Same provider list as ConfigInitializerGenerator. Each provider's
     * newConfigCreator().type() discovers the config class.
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

    /**
     * Fields with custom accessors instead of standard getXxx/isXxx.
     * These are deliberately designed without standard getters in upstream SkyWalking.
     * - virtualPeers: derived from noUpstreamRealAddressAgents, accessed via shouldIgnorePeerIPDue2Virtual()
     * - meterAnalyzerActiveFiles: accessed via meterAnalyzerActiveFileNames() (parsed list)
     * - meterConfigs: lazily loaded via custom getMeterConfigs()/getLogMeterConfigs() method
     * - serviceMetaInfoFactory: final, accessed via serviceMetaInfoFactory() method
     */
    private static final Set<String> CUSTOM_ACCESSOR_FIELDS = Set.of(
        "AnalyzerModuleConfig.virtualPeers",
        "AnalyzerModuleConfig.meterAnalyzerActiveFiles",
        "LogAnalyzerModuleConfig.meterConfigs",
        "EnvoyMetricReceiverConfig.serviceMetaInfoFactory"
    );

    @Test
    void allNonFinalFieldsHaveSetter() throws Exception {
        Map<String, Class<?>> configClasses = discoverConfigClasses();
        assertTrue(configClasses.size() > 30,
            "Expected at least 30 config classes, found " + configClasses.size());

        List<String> errors = new ArrayList<>();

        for (var entry : configClasses.entrySet()) {
            Class<?> configClass = entry.getValue();
            Class<?> clazz = configClass;
            while (clazz != null && clazz != ModuleConfig.class && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }
                    if (!hasSetter(configClass, field)) {
                        errors.add(configClass.getName() + "." + field.getName() + " — missing setter");
                    }
                }
                clazz = clazz.getSuperclass();
            }
        }

        if (!errors.isEmpty()) {
            fail("Non-final config fields missing setter (required for YamlConfigLoaderUtils):\n  "
                + String.join("\n  ", errors));
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

    private Map<String, Class<?>> discoverConfigClasses() throws Exception {
        Map<String, Class<?>> configClasses = new LinkedHashMap<>();

        for (String providerClassName : PROVIDER_CLASSES) {
            Class<?> providerClass = Class.forName(providerClassName);
            ModuleProvider provider = (ModuleProvider) providerClass.getDeclaredConstructor().newInstance();
            ModuleProvider.ConfigCreator<?> creator = provider.newConfigCreator();
            if (creator == null) {
                continue;
            }
            Class<?> configType = creator.type();
            if (configType != null) {
                configClasses.putIfAbsent(configType.getName(), configType);
            }
        }

        for (String className : EXTRA_CONFIG_CLASSES) {
            Class<?> clazz = Class.forName(className);
            configClasses.putIfAbsent(clazz.getName(), clazz);
        }

        return configClasses;
    }

    private static boolean hasSetter(Class<?> configClass, Field field) {
        String setterName = "set" + capitalize(field.getName());
        try {
            configClass.getMethod(setterName, field.getType());
            return true;
        } catch (NoSuchMethodException e) {
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
}
