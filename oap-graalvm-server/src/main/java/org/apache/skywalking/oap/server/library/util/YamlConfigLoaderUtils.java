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

package org.apache.skywalking.oap.server.library.util;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.ConfigFieldValues;
import org.yaml.snakeyaml.Yaml;

import java.util.Set;
import org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.query.graphql.GraphQLQueryConfig;
import org.apache.skywalking.oap.query.logql.LogQLConfig;
import org.apache.skywalking.oap.query.promql.PromQLConfig;
import org.apache.skywalking.oap.query.server.QueryServerConfig;
import org.apache.skywalking.oap.query.traceql.TraceQLConfig;
import org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig;
import org.apache.skywalking.oap.server.admin.dsl.debugging.module.DSLDebuggingModuleConfig;
import org.apache.skywalking.oap.server.admin.inspect.InspectModuleConfig;
import org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig;
import org.apache.skywalking.oap.server.admin.status.StatusModuleConfig;
import org.apache.skywalking.oap.server.admin.uimanagement.UIManagementModuleConfig;
import org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig;
import org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig;
import org.apache.skywalking.oap.server.ai.evaluation.level.EvaluationLevelConfig;
import org.apache.skywalking.oap.server.ai.pipeline.AIPipelineConfig;
import org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig;
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig;
import org.apache.skywalking.oap.server.analyzer.provider.trace.CacheReadLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.CacheWriteLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.DBLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.TraceSamplingPolicyWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.UninstrumentedGatewaysConfig;
import org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesConfig;
import org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationSettings;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig;
import org.apache.skywalking.oap.server.core.config.SearchableTracesTagsWatcher;
import org.apache.skywalking.oap.server.exporter.provider.ExporterSetting;
import org.apache.skywalking.oap.server.fetcher.cilium.CiliumFetcherConfig;
import org.apache.skywalking.oap.server.health.checker.provider.HealthCheckerConfig;
import org.apache.skywalking.oap.server.receiver.asyncprofiler.module.AsyncProfilerModuleConfig;
import org.apache.skywalking.oap.server.receiver.aws.firehose.AWSFirehoseReceiverModuleConfig;
import org.apache.skywalking.oap.server.receiver.browser.provider.BrowserServiceModuleConfig;
import org.apache.skywalking.oap.server.receiver.configuration.discovery.ConfigurationDiscoveryModuleConfig;
import org.apache.skywalking.oap.server.receiver.ebpf.provider.EBPFReceiverModuleConfig;
import org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverConfig;
import org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig;
import org.apache.skywalking.oap.server.receiver.pprof.module.PprofModuleConfig;
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig;
import org.apache.skywalking.oap.server.receiver.telegraf.provider.TelegrafModuleConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixModuleConfig;
import org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig;
import org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;

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

    public static void copyProperties(final Object dest,
                                      final Properties src,
                                      final String moduleName,
                                      final String providerName) throws IllegalAccessException {
        if (dest == null) {
            return;
        }
        if (dest instanceof GenAIConfig) {
            copyToGenAIConfig((GenAIConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof LogAnalyzerModuleConfig) {
            copyToLogAnalyzerModuleConfig((LogAnalyzerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof GraphQLQueryConfig) {
            copyToGraphQLQueryConfig((GraphQLQueryConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof LogQLConfig) {
            copyToLogQLConfig((LogQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof PromQLConfig) {
            copyToPromQLConfig((PromQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof QueryServerConfig) {
            copyToQueryServerConfig((QueryServerConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof TraceQLConfig) {
            copyToTraceQLConfig((TraceQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZipkinQueryConfig) {
            copyToZipkinQueryConfig((ZipkinQueryConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof DSLDebuggingModuleConfig) {
            copyToDSLDebuggingModuleConfig((DSLDebuggingModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof InspectModuleConfig) {
            // InspectModuleConfig has no configurable fields.
            final Enumeration<?> propertyNames = src.propertyNames();
            while (propertyNames.hasMoreElements()) {
                log.warn("{} setting is not supported in {} provider of {} module",
                    propertyNames.nextElement(), providerName, moduleName);
            }
        } else if (dest instanceof AdminServerModuleConfig) {
            copyToAdminServerModuleConfig((AdminServerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof StatusModuleConfig) {
            copyToStatusModuleConfig((StatusModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof UIManagementModuleConfig) {
            // UIManagementModuleConfig has no configurable fields.
            final Enumeration<?> propertyNames = src.propertyNames();
            while (propertyNames.hasMoreElements()) {
                log.warn("{} setting is not supported in {} provider of {} module",
                    propertyNames.nextElement(), providerName, moduleName);
            }
        } else if (dest instanceof AIAgentConversationConfig) {
            copyToAIAgentConversationConfig((AIAgentConversationConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AIEvaluationConfig) {
            copyToAIEvaluationConfig((AIEvaluationConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AIPipelineConfig) {
            copyToAIPipelineConfig((AIPipelineConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof KafkaFetcherConfig) {
            copyToKafkaFetcherConfig((KafkaFetcherConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AnalyzerModuleConfig) {
            copyToAnalyzerModuleConfig((AnalyzerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ClusterModuleKubernetesConfig) {
            copyToClusterModuleKubernetesConfig((ClusterModuleKubernetesConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ConfigmapConfigurationSettings) {
            copyToConfigmapConfigurationSettings((ConfigmapConfigurationSettings) dest, src, moduleName, providerName);
        } else if (dest instanceof CoreModuleConfig) {
            copyToCoreModuleConfig((CoreModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AlarmModuleConfig) {
            copyToAlarmModuleConfig((AlarmModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ExporterSetting) {
            copyToExporterSetting((ExporterSetting) dest, src, moduleName, providerName);
        } else if (dest instanceof CiliumFetcherConfig) {
            copyToCiliumFetcherConfig((CiliumFetcherConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof HealthCheckerConfig) {
            copyToHealthCheckerConfig((HealthCheckerConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AsyncProfilerModuleConfig) {
            copyToAsyncProfilerModuleConfig((AsyncProfilerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AWSFirehoseReceiverModuleConfig) {
            copyToAWSFirehoseReceiverModuleConfig((AWSFirehoseReceiverModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BrowserServiceModuleConfig) {
            copyToBrowserServiceModuleConfig((BrowserServiceModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ConfigurationDiscoveryModuleConfig) {
            copyToConfigurationDiscoveryModuleConfig((ConfigurationDiscoveryModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof EBPFReceiverModuleConfig) {
            copyToEBPFReceiverModuleConfig((EBPFReceiverModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof EnvoyMetricReceiverConfig) {
            copyToEnvoyMetricReceiverConfig((EnvoyMetricReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof OtelMetricReceiverConfig) {
            copyToOtelMetricReceiverConfig((OtelMetricReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof PprofModuleConfig) {
            copyToPprofModuleConfig((PprofModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof SharingServerConfig) {
            copyToSharingServerConfig((SharingServerConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof TelegrafModuleConfig) {
            copyToTelegrafModuleConfig((TelegrafModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZabbixModuleConfig) {
            copyToZabbixModuleConfig((ZabbixModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZipkinReceiverConfig) {
            copyToZipkinReceiverConfig((ZipkinReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig) {
            copyToBanyanDBStorageConfig((BanyanDBStorageConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Metadata) {
            copyToMetadata((BanyanDBStorageConfig.Metadata) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsDay) {
            copyToMetricsDay((BanyanDBStorageConfig.MetricsDay) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsHour) {
            copyToMetricsHour((BanyanDBStorageConfig.MetricsHour) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsMin) {
            copyToMetricsMin((BanyanDBStorageConfig.MetricsMin) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.OtlpTrace) {
            copyToOtlpTrace((BanyanDBStorageConfig.OtlpTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Property) {
            copyToProperty((BanyanDBStorageConfig.Property) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsAIAgent) {
            copyToRecordsAIAgent((BanyanDBStorageConfig.RecordsAIAgent) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsBrowserErrorLog) {
            copyToRecordsBrowserErrorLog((BanyanDBStorageConfig.RecordsBrowserErrorLog) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsLog) {
            copyToRecordsLog((BanyanDBStorageConfig.RecordsLog) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsNormal) {
            copyToRecordsNormal((BanyanDBStorageConfig.RecordsNormal) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsTrace) {
            copyToRecordsTrace((BanyanDBStorageConfig.RecordsTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsZipkinTrace) {
            copyToRecordsZipkinTrace((BanyanDBStorageConfig.RecordsZipkinTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Trace) {
            copyToTrace((BanyanDBStorageConfig.Trace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.ZipkinTrace) {
            copyToZipkinTrace((BanyanDBStorageConfig.ZipkinTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof PrometheusConfig) {
            copyToPrometheusConfig((PrometheusConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof TraceAnalyzerModuleConfig) {
            copyToTraceAnalyzerModuleConfig((TraceAnalyzerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof GenAIConfig.Model) {
            copyToModel((GenAIConfig.Model) dest, src, moduleName, providerName);
        } else if (dest instanceof GenAIConfig.Provider) {
            copyToProvider((GenAIConfig.Provider) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Global) {
            copyToGlobal((BanyanDBStorageConfig.Global) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.GroupResource) {
            copyToGroupResource((BanyanDBStorageConfig.GroupResource) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.SamplerPluginConfig) {
            copyToSamplerPluginConfig((BanyanDBStorageConfig.SamplerPluginConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Stage) {
            copyToStage((BanyanDBStorageConfig.Stage) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.TopN) {
            copyToTopN((BanyanDBStorageConfig.TopN) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.TracePipeline) {
            copyToTracePipeline((BanyanDBStorageConfig.TracePipeline) dest, src, moduleName, providerName);
        } else {
            throw new IllegalArgumentException("Unknown config type: "
                + dest.getClass().getName()
                + " in " + providerName + " provider of " + moduleName + " module."
                + " Add its provider to AcceptedModules and regenerate this class.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToGenAIConfig(
            final GenAIConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "providers":
                    cfg.setProviders(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig.providers"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToLogAnalyzerModuleConfig(
            final LogAnalyzerModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "lalFiles":
                    cfg.setLalFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig.lalFiles"));
                    break;
                case "lalPath":
                    cfg.setLalPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig.lalPath"));
                    break;
                case "malFiles":
                    cfg.setMalFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig.malFiles"));
                    break;
                case "malPath":
                    cfg.setMalPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig.malPath"));
                    break;
                case "meterConfigs":
                    ConfigFieldValues.setByField(cfg, "meterConfigs", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToGraphQLQueryConfig(
            final GraphQLQueryConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "enableLogTestTool":
                    cfg.setEnableLogTestTool(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.graphql.GraphQLQueryConfig.enableLogTestTool"));
                    break;
                case "enableOnDemandPodLog":
                    cfg.setEnableOnDemandPodLog(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.graphql.GraphQLQueryConfig.enableOnDemandPodLog"));
                    break;
                case "maxQueryComplexity":
                    cfg.setMaxQueryComplexity(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.graphql.GraphQLQueryConfig.maxQueryComplexity"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToLogQLConfig(
            final LogQLConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.logql.LogQLConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.logql.LogQLConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.logql.LogQLConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.logql.LogQLConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.logql.LogQLConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.logql.LogQLConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.logql.LogQLConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.logql.LogQLConfig.restSSLKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToPromQLConfig(
            final PromQLConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "buildInfoBranch":
                    cfg.setBuildInfoBranch(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoBranch"));
                    break;
                case "buildInfoBuildDate":
                    cfg.setBuildInfoBuildDate(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoBuildDate"));
                    break;
                case "buildInfoBuildUser":
                    cfg.setBuildInfoBuildUser(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoBuildUser"));
                    break;
                case "buildInfoGoVersion":
                    cfg.setBuildInfoGoVersion(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoGoVersion"));
                    break;
                case "buildInfoRevision":
                    cfg.setBuildInfoRevision(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoRevision"));
                    break;
                case "buildInfoVersion":
                    cfg.setBuildInfoVersion(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.buildInfoVersion"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.promql.PromQLConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.promql.PromQLConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.promql.PromQLConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.promql.PromQLConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.promql.PromQLConfig.restSSLKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToQueryServerConfig(
            final QueryServerConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.server.QueryServerConfig.httpMaxRequestHeaderSize"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.server.QueryServerConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.server.QueryServerConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.server.QueryServerConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.server.QueryServerConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.server.QueryServerConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.server.QueryServerConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.server.QueryServerConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.server.QueryServerConfig.restSSLKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTraceQLConfig(
            final TraceQLConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "OTLP_TRACES_LIST_RESULT_TAGS":
                    ConfigFieldValues.setByField(cfg, "OTLP_TRACES_LIST_RESULT_TAGS", value);
                    break;
                case "SKYWALKING_TRACES_LIST_RESULT_TAGS":
                    ConfigFieldValues.setByField(cfg, "SKYWALKING_TRACES_LIST_RESULT_TAGS", value);
                    break;
                case "ZIPKIN_TRACES_LIST_RESULT_TAGS":
                    ConfigFieldValues.setByField(cfg, "ZIPKIN_TRACES_LIST_RESULT_TAGS", value);
                    break;
                case "enableDatasourceOTLP":
                    cfg.setEnableDatasourceOTLP(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.enableDatasourceOTLP"));
                    break;
                case "enableDatasourceSkywalking":
                    cfg.setEnableDatasourceSkywalking(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.enableDatasourceSkywalking"));
                    break;
                case "enableDatasourceZipkin":
                    cfg.setEnableDatasourceZipkin(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.enableDatasourceZipkin"));
                    break;
                case "lookback":
                    cfg.setLookback(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.lookback"));
                    break;
                case "otlpTracesListResultTags":
                    cfg.setOtlpTracesListResultTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.otlpTracesListResultTags"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restAcceptQueueSize"));
                    break;
                case "restContextPathOTLP":
                    cfg.setRestContextPathOTLP(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restContextPathOTLP"));
                    break;
                case "restContextPathSkywalking":
                    cfg.setRestContextPathSkywalking(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restContextPathSkywalking"));
                    break;
                case "restContextPathZipkin":
                    cfg.setRestContextPathZipkin(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restContextPathZipkin"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.restSSLKeyPath"));
                    break;
                case "skywalkingTracesListResultTags":
                    cfg.setSkywalkingTracesListResultTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.skywalkingTracesListResultTags"));
                    break;
                case "zipkinTracesListResultTags":
                    cfg.setZipkinTracesListResultTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.traceql.TraceQLConfig.zipkinTracesListResultTags"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToZipkinQueryConfig(
            final ZipkinQueryConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "lookback":
                    cfg.setLookback(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.lookback"));
                    break;
                case "namesMaxAge":
                    cfg.setNamesMaxAge(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.namesMaxAge"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.restSSLKeyPath"));
                    break;
                case "uiDefaultLookback":
                    cfg.setUiDefaultLookback(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.uiDefaultLookback"));
                    break;
                case "uiEnvironment":
                    cfg.setUiEnvironment(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.uiEnvironment"));
                    break;
                case "uiQueryLimit":
                    cfg.setUiQueryLimit(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.uiQueryLimit"));
                    break;
                case "uiSearchEnabled":
                    cfg.setUiSearchEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig.uiSearchEnabled"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToDSLDebuggingModuleConfig(
            final DSLDebuggingModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "injectionEnabled":
                    cfg.setInjectionEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.admin.dsl.debugging.module.DSLDebuggingModuleConfig.injectionEnabled"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAdminServerModuleConfig(
            final AdminServerModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "acceptQueueSize":
                    cfg.setAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.acceptQueueSize"));
                    break;
                case "contextPath":
                    cfg.setContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.contextPath"));
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCHost"));
                    break;
                case "gRPCMaxConcurrentCallsPerConnection":
                    cfg.setGRPCMaxConcurrentCallsPerConnection(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCMaxConcurrentCallsPerConnection"));
                    break;
                case "gRPCMaxMessageSize":
                    cfg.setGRPCMaxMessageSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCMaxMessageSize"));
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCPort"));
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCSslCertChainPath"));
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCSslEnabled"));
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCSslKeyPath"));
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCSslTrustedCAsPath"));
                    break;
                case "gRPCThreadPoolQueueSize":
                    cfg.setGRPCThreadPoolQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCThreadPoolQueueSize"));
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.gRPCThreadPoolSize"));
                    break;
                case "host":
                    cfg.setHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.host"));
                    break;
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.httpMaxRequestHeaderSize"));
                    break;
                case "idleTimeOut":
                    cfg.setIdleTimeOut(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.idleTimeOut"));
                    break;
                case "internalCommunicationTimeout":
                    cfg.setInternalCommunicationTimeout(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.internalCommunicationTimeout"));
                    break;
                case "port":
                    cfg.setPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.port"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig.restSSLKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToStatusModuleConfig(
            final StatusModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "keywords4MaskingSecretsOfConfig":
                    cfg.setKeywords4MaskingSecretsOfConfig(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.admin.status.StatusModuleConfig.keywords4MaskingSecretsOfConfig"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAIAgentConversationConfig(
            final AIAgentConversationConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "conversationListMaxLimit":
                    cfg.setConversationListMaxLimit(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.conversationListMaxLimit"));
                    break;
                case "hide":
                    cfg.setHide(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.hide"));
                    break;
                case "maxFileBytes":
                    cfg.setMaxFileBytes(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.maxFileBytes"));
                    break;
                case "maxResponseBytes":
                    cfg.setMaxResponseBytes(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.maxResponseBytes"));
                    break;
                case "readWindow":
                    cfg.setReadWindow(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.readWindow"));
                    break;
                case "viewRequestTimeout":
                    cfg.setViewRequestTimeout(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.agent.conversation.AIAgentConversationConfig.viewRequestTimeout"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAIEvaluationConfig(
            final AIEvaluationConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "bufferSize":
                    cfg.setBufferSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.bufferSize"));
                    break;
                case "consumerThreads":
                    cfg.setConsumerThreads(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.consumerThreads"));
                    break;
                case "judge":
                    cfg.setJudge(ConfigFieldValues.referenceValue(value, Properties.class, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.judge"));
                    break;
                case "level":
                    cfg.setLevel(ConfigFieldValues.referenceValue(value, EvaluationLevelConfig.class, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.level"));
                    break;
                case "maxContentLength":
                    cfg.setMaxContentLength(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.maxContentLength"));
                    break;
                case "sampleRate":
                    cfg.setSampleRate(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.sampleRate"));
                    break;
                case "systemPrompt":
                    cfg.setSystemPrompt(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.systemPrompt"));
                    break;
                case "tasks":
                    cfg.setTasks(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationConfig.tasks"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAIPipelineConfig(
            final AIPipelineConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "baselineServerAddr":
                    cfg.setBaselineServerAddr(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.ai.pipeline.AIPipelineConfig.baselineServerAddr"));
                    break;
                case "baselineServerPort":
                    cfg.setBaselineServerPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.pipeline.AIPipelineConfig.baselineServerPort"));
                    break;
                case "uriRecognitionServerAddr":
                    cfg.setUriRecognitionServerAddr(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.ai.pipeline.AIPipelineConfig.uriRecognitionServerAddr"));
                    break;
                case "uriRecognitionServerPort":
                    cfg.setUriRecognitionServerPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.ai.pipeline.AIPipelineConfig.uriRecognitionServerPort"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToKafkaFetcherConfig(
            final KafkaFetcherConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "bootstrapServers":
                    cfg.setBootstrapServers(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.bootstrapServers"));
                    break;
                case "configPath":
                    cfg.setConfigPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.configPath"));
                    break;
                case "consumers":
                    cfg.setConsumers(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.consumers"));
                    break;
                case "enableNativeJsonLog":
                    cfg.setEnableNativeJsonLog(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.enableNativeJsonLog"));
                    break;
                case "enableNativeProtoLog":
                    cfg.setEnableNativeProtoLog(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.enableNativeProtoLog"));
                    break;
                case "groupId":
                    cfg.setGroupId(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.groupId"));
                    break;
                case "kafkaConsumerConfig":
                    cfg.setKafkaConsumerConfig(ConfigFieldValues.referenceValue(value, Properties.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.kafkaConsumerConfig"));
                    break;
                case "kafkaHandlerThreadPoolQueueSize":
                    cfg.setKafkaHandlerThreadPoolQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.kafkaHandlerThreadPoolQueueSize"));
                    break;
                case "kafkaHandlerThreadPoolSize":
                    cfg.setKafkaHandlerThreadPoolSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.kafkaHandlerThreadPoolSize"));
                    break;
                case "mm2SourceAlias":
                    cfg.setMm2SourceAlias(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.mm2SourceAlias"));
                    break;
                case "mm2SourceSeparator":
                    cfg.setMm2SourceSeparator(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.mm2SourceSeparator"));
                    break;
                case "namespace":
                    cfg.setNamespace(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.namespace"));
                    break;
                case "partitions":
                    cfg.setPartitions(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.partitions"));
                    break;
                case "replicationFactor":
                    cfg.setReplicationFactor(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.replicationFactor"));
                    break;
                case "topicNameOfJsonLogs":
                    cfg.setTopicNameOfJsonLogs(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfJsonLogs"));
                    break;
                case "topicNameOfLogs":
                    cfg.setTopicNameOfLogs(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfLogs"));
                    break;
                case "topicNameOfManagements":
                    cfg.setTopicNameOfManagements(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfManagements"));
                    break;
                case "topicNameOfMeters":
                    cfg.setTopicNameOfMeters(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfMeters"));
                    break;
                case "topicNameOfMetrics":
                    cfg.setTopicNameOfMetrics(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfMetrics"));
                    break;
                case "topicNameOfProfiling":
                    cfg.setTopicNameOfProfiling(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfProfiling"));
                    break;
                case "topicNameOfTracingSegments":
                    cfg.setTopicNameOfTracingSegments(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherConfig.topicNameOfTracingSegments"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAnalyzerModuleConfig(
            final AnalyzerModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "cacheReadLatencyThresholdsAndWatcher":
                    cfg.setCacheReadLatencyThresholdsAndWatcher(ConfigFieldValues.referenceValue(value, CacheReadLatencyThresholdsAndWatcher.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.cacheReadLatencyThresholdsAndWatcher"));
                    break;
                case "cacheWriteLatencyThresholdsAndWatcher":
                    cfg.setCacheWriteLatencyThresholdsAndWatcher(ConfigFieldValues.referenceValue(value, CacheWriteLatencyThresholdsAndWatcher.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.cacheWriteLatencyThresholdsAndWatcher"));
                    break;
                case "configPath":
                    ConfigFieldValues.setByField(cfg, "configPath", value);
                    break;
                case "dbLatencyThresholdsAndWatcher":
                    cfg.setDbLatencyThresholdsAndWatcher(ConfigFieldValues.referenceValue(value, DBLatencyThresholdsAndWatcher.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.dbLatencyThresholdsAndWatcher"));
                    break;
                case "forceSampleErrorSegment":
                    cfg.setForceSampleErrorSegment(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.forceSampleErrorSegment"));
                    break;
                case "log":
                    ConfigFieldValues.setByField(cfg, "log", value);
                    break;
                case "maxSlowSQLLength":
                    cfg.setMaxSlowSQLLength(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.maxSlowSQLLength"));
                    break;
                case "meterAnalyzerActiveFiles":
                    cfg.setMeterAnalyzerActiveFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.meterAnalyzerActiveFiles"));
                    break;
                case "noUpstreamRealAddressAgents":
                    ConfigFieldValues.setByField(cfg, "noUpstreamRealAddressAgents", value);
                    break;
                case "segmentStatusAnalysisStrategy":
                    cfg.setSegmentStatusAnalysisStrategy(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.segmentStatusAnalysisStrategy"));
                    break;
                case "slowCacheReadThreshold":
                    cfg.setSlowCacheReadThreshold(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.slowCacheReadThreshold"));
                    break;
                case "slowCacheWriteThreshold":
                    cfg.setSlowCacheWriteThreshold(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.slowCacheWriteThreshold"));
                    break;
                case "slowDBAccessThreshold":
                    cfg.setSlowDBAccessThreshold(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.slowDBAccessThreshold"));
                    break;
                case "traceAnalysis":
                    cfg.setTraceAnalysis(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.traceAnalysis"));
                    break;
                case "traceSamplingPolicySettingsFile":
                    cfg.setTraceSamplingPolicySettingsFile(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.traceSamplingPolicySettingsFile"));
                    break;
                case "traceSamplingPolicyWatcher":
                    cfg.setTraceSamplingPolicyWatcher(ConfigFieldValues.referenceValue(value, TraceSamplingPolicyWatcher.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.traceSamplingPolicyWatcher"));
                    break;
                case "uninstrumentedGatewaysConfig":
                    cfg.setUninstrumentedGatewaysConfig(ConfigFieldValues.referenceValue(value, UninstrumentedGatewaysConfig.class, "org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig.uninstrumentedGatewaysConfig"));
                    break;
                case "virtualPeers":
                    ConfigFieldValues.setByField(cfg, "virtualPeers", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToClusterModuleKubernetesConfig(
            final ClusterModuleKubernetesConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "labelSelector":
                    cfg.setLabelSelector(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesConfig.labelSelector"));
                    break;
                case "namespace":
                    cfg.setNamespace(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesConfig.namespace"));
                    break;
                case "uidEnvName":
                    cfg.setUidEnvName(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesConfig.uidEnvName"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToConfigmapConfigurationSettings(
            final ConfigmapConfigurationSettings cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "labelSelector":
                    cfg.setLabelSelector(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationSettings.labelSelector"));
                    break;
                case "namespace":
                    cfg.setNamespace(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationSettings.namespace"));
                    break;
                case "period":
                    cfg.setPeriod(ConfigFieldValues.referenceValue(value, Integer.class, "org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationSettings.period"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToCoreModuleConfig(
            final CoreModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "DEFAULT_SEARCHABLE_TAG_KEYS":
                    ConfigFieldValues.setByField(cfg, "DEFAULT_SEARCHABLE_TAG_KEYS", value);
                    break;
                case "activeExtraModelColumns":
                    ConfigFieldValues.setByField(cfg, "activeExtraModelColumns", value);
                    break;
                case "autocompleteTagKeysQueryMaxSize":
                    cfg.setAutocompleteTagKeysQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.autocompleteTagKeysQueryMaxSize"));
                    break;
                case "autocompleteTagValuesQueryMaxSize":
                    cfg.setAutocompleteTagValuesQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.autocompleteTagValuesQueryMaxSize"));
                    break;
                case "dataKeeperExecutePeriod":
                    ConfigFieldValues.setByField(cfg, "dataKeeperExecutePeriod", value);
                    break;
                case "downsampling":
                    ConfigFieldValues.setByField(cfg, "downsampling", value);
                    break;
                case "enableDataKeeperExecutor":
                    ConfigFieldValues.setByField(cfg, "enableDataKeeperExecutor", value);
                    break;
                case "enableEndpointNameGroupingByOpenapi":
                    cfg.setEnableEndpointNameGroupingByOpenapi(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.enableEndpointNameGroupingByOpenapi"));
                    break;
                case "enableHierarchy":
                    cfg.setEnableHierarchy(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.enableHierarchy"));
                    break;
                case "endpointNameMaxLength":
                    ConfigFieldValues.setByField(cfg, "endpointNameMaxLength", value);
                    break;
                case "gRPCHost":
                    ConfigFieldValues.setByField(cfg, "gRPCHost", value);
                    break;
                case "gRPCPort":
                    ConfigFieldValues.setByField(cfg, "gRPCPort", value);
                    break;
                case "gRPCSslCertChainPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslCertChainPath", value);
                    break;
                case "gRPCSslEnabled":
                    ConfigFieldValues.setByField(cfg, "gRPCSslEnabled", value);
                    break;
                case "gRPCSslKeyPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslKeyPath", value);
                    break;
                case "gRPCSslTrustedCAPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslTrustedCAPath", value);
                    break;
                case "gRPCThreadPoolSize":
                    ConfigFieldValues.setByField(cfg, "gRPCThreadPoolSize", value);
                    break;
                case "httpMaxRequestHeaderSize":
                    ConfigFieldValues.setByField(cfg, "httpMaxRequestHeaderSize", value);
                    break;
                case "instanceNameMaxLength":
                    ConfigFieldValues.setByField(cfg, "instanceNameMaxLength", value);
                    break;
                case "k8sMetadataCacheTime":
                    ConfigFieldValues.setByField(cfg, "k8sMetadataCacheTime", value);
                    break;
                case "k8sMetadataNotFoundRetryPeriod":
                    ConfigFieldValues.setByField(cfg, "k8sMetadataNotFoundRetryPeriod", value);
                    break;
                case "k8sMetadataReadQueueSize":
                    ConfigFieldValues.setByField(cfg, "k8sMetadataReadQueueSize", value);
                    break;
                case "k8sMetadataRefreshPeriod":
                    ConfigFieldValues.setByField(cfg, "k8sMetadataRefreshPeriod", value);
                    break;
                case "l1FlushPeriod":
                    ConfigFieldValues.setByField(cfg, "l1FlushPeriod", value);
                    break;
                case "malMetadataReadTimeout":
                    ConfigFieldValues.setByField(cfg, "malMetadataReadTimeout", value);
                    break;
                case "maxConcurrentCallsPerConnection":
                    ConfigFieldValues.setByField(cfg, "maxConcurrentCallsPerConnection", value);
                    break;
                case "maxDirectMemoryUsage":
                    ConfigFieldValues.setByField(cfg, "maxDirectMemoryUsage", value);
                    break;
                case "maxDurationOfQueryEBPFProfilingData":
                    ConfigFieldValues.setByField(cfg, "maxDurationOfQueryEBPFProfilingData", value);
                    break;
                case "maxHeapMemoryUsagePercent":
                    ConfigFieldValues.setByField(cfg, "maxHeapMemoryUsagePercent", value);
                    break;
                case "maxHttpUrisNumberPerService":
                    ConfigFieldValues.setByField(cfg, "maxHttpUrisNumberPerService", value);
                    break;
                case "maxMessageSize":
                    ConfigFieldValues.setByField(cfg, "maxMessageSize", value);
                    break;
                case "maxPageSizeOfQueryProfileSnapshot":
                    ConfigFieldValues.setByField(cfg, "maxPageSizeOfQueryProfileSnapshot", value);
                    break;
                case "maxSizeOfAnalyzeProfileSnapshot":
                    ConfigFieldValues.setByField(cfg, "maxSizeOfAnalyzeProfileSnapshot", value);
                    break;
                case "maxSizeOfNetworkAddressAlias":
                    ConfigFieldValues.setByField(cfg, "maxSizeOfNetworkAddressAlias", value);
                    break;
                case "maxSizeOfPprofTask":
                    ConfigFieldValues.setByField(cfg, "maxSizeOfPprofTask", value);
                    break;
                case "maxSizeOfProfileTask":
                    ConfigFieldValues.setByField(cfg, "maxSizeOfProfileTask", value);
                    break;
                case "maxThreadCountOfQueryEBPFProfilingData":
                    ConfigFieldValues.setByField(cfg, "maxThreadCountOfQueryEBPFProfilingData", value);
                    break;
                case "metricsDataTTL":
                    ConfigFieldValues.setByField(cfg, "metricsDataTTL", value);
                    break;
                case "namespace":
                    ConfigFieldValues.setByField(cfg, "namespace", value);
                    break;
                case "persistentPeriod":
                    cfg.setPersistentPeriod(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.persistentPeriod"));
                    break;
                case "prepareThreads":
                    cfg.setPrepareThreads(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.prepareThreads"));
                    break;
                case "recordDataTTL":
                    ConfigFieldValues.setByField(cfg, "recordDataTTL", value);
                    break;
                case "remoteTimeout":
                    ConfigFieldValues.setByField(cfg, "remoteTimeout", value);
                    break;
                case "restAcceptQueueSize":
                    ConfigFieldValues.setByField(cfg, "restAcceptQueueSize", value);
                    break;
                case "restContextPath":
                    ConfigFieldValues.setByField(cfg, "restContextPath", value);
                    break;
                case "restHost":
                    ConfigFieldValues.setByField(cfg, "restHost", value);
                    break;
                case "restIdleTimeOut":
                    ConfigFieldValues.setByField(cfg, "restIdleTimeOut", value);
                    break;
                case "restMaxThreads":
                    ConfigFieldValues.setByField(cfg, "restMaxThreads", value);
                    break;
                case "restPort":
                    ConfigFieldValues.setByField(cfg, "restPort", value);
                    break;
                case "restSSLCertChainPath":
                    ConfigFieldValues.setByField(cfg, "restSSLCertChainPath", value);
                    break;
                case "restSSLEnabled":
                    ConfigFieldValues.setByField(cfg, "restSSLEnabled", value);
                    break;
                case "restSSLKeyPath":
                    ConfigFieldValues.setByField(cfg, "restSSLKeyPath", value);
                    break;
                case "role":
                    ConfigFieldValues.setByField(cfg, "role", value);
                    break;
                case "searchableAlarmTags":
                    cfg.setSearchableAlarmTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.core.CoreModuleConfig.searchableAlarmTags"));
                    break;
                case "searchableLogsTags":
                    cfg.setSearchableLogsTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.core.CoreModuleConfig.searchableLogsTags"));
                    break;
                case "searchableTracesTags":
                    cfg.setSearchableTracesTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.core.CoreModuleConfig.searchableTracesTags"));
                    break;
                case "searchableTracesTagsWatcher":
                    cfg.setSearchableTracesTagsWatcher(ConfigFieldValues.referenceValue(value, SearchableTracesTagsWatcher.class, "org.apache.skywalking.oap.server.core.CoreModuleConfig.searchableTracesTagsWatcher"));
                    break;
                case "serviceCacheRefreshInterval":
                    cfg.setServiceCacheRefreshInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.core.CoreModuleConfig.serviceCacheRefreshInterval"));
                    break;
                case "serviceNameMaxLength":
                    ConfigFieldValues.setByField(cfg, "serviceNameMaxLength", value);
                    break;
                case "storageSessionTimeout":
                    ConfigFieldValues.setByField(cfg, "storageSessionTimeout", value);
                    break;
                case "syncPeriodHttpUriRecognitionPattern":
                    ConfigFieldValues.setByField(cfg, "syncPeriodHttpUriRecognitionPattern", value);
                    break;
                case "topNReportPeriod":
                    ConfigFieldValues.setByField(cfg, "topNReportPeriod", value);
                    break;
                case "trainingPeriodHttpUriRecognitionPattern":
                    ConfigFieldValues.setByField(cfg, "trainingPeriodHttpUriRecognitionPattern", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAlarmModuleConfig(
            final AlarmModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "DEFAULT_GRPC_TIMEOUT_SECONDS":
                    ConfigFieldValues.setByField(cfg, "DEFAULT_GRPC_TIMEOUT_SECONDS", value);
                    break;
                case "DEFAULT_HTTP_TIMEOUT_SECONDS":
                    ConfigFieldValues.setByField(cfg, "DEFAULT_HTTP_TIMEOUT_SECONDS", value);
                    break;
                case "grpcTimeout":
                    cfg.setGrpcTimeout(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig.grpcTimeout"));
                    break;
                case "httpTimeout":
                    cfg.setHttpTimeout(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig.httpTimeout"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToExporterSetting(
            final ExporterSetting cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "bufferSize":
                    cfg.setBufferSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.bufferSize"));
                    break;
                case "enableGRPCMetrics":
                    cfg.setEnableGRPCMetrics(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.enableGRPCMetrics"));
                    break;
                case "enableKafkaLog":
                    cfg.setEnableKafkaLog(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.enableKafkaLog"));
                    break;
                case "enableKafkaTrace":
                    cfg.setEnableKafkaTrace(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.enableKafkaTrace"));
                    break;
                case "exportErrorStatusTraceOnly":
                    cfg.setExportErrorStatusTraceOnly(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.exportErrorStatusTraceOnly"));
                    break;
                case "gRPCTargetHost":
                    cfg.setGRPCTargetHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.gRPCTargetHost"));
                    break;
                case "gRPCTargetPort":
                    cfg.setGRPCTargetPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.gRPCTargetPort"));
                    break;
                case "kafkaBootstrapServers":
                    cfg.setKafkaBootstrapServers(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.kafkaBootstrapServers"));
                    break;
                case "kafkaProducerConfig":
                    cfg.setKafkaProducerConfig(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.kafkaProducerConfig"));
                    break;
                case "kafkaTopicLog":
                    cfg.setKafkaTopicLog(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.kafkaTopicLog"));
                    break;
                case "kafkaTopicTrace":
                    cfg.setKafkaTopicTrace(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.exporter.provider.ExporterSetting.kafkaTopicTrace"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToCiliumFetcherConfig(
            final CiliumFetcherConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "convertClientAsServerTraffic":
                    ConfigFieldValues.setByField(cfg, "convertClientAsServerTraffic", value);
                    break;
                case "fetchFailureRetrySecond":
                    ConfigFieldValues.setByField(cfg, "fetchFailureRetrySecond", value);
                    break;
                case "peerHost":
                    ConfigFieldValues.setByField(cfg, "peerHost", value);
                    break;
                case "peerPort":
                    ConfigFieldValues.setByField(cfg, "peerPort", value);
                    break;
                case "sslCaFile":
                    ConfigFieldValues.setByField(cfg, "sslCaFile", value);
                    break;
                case "sslCertChainFile":
                    ConfigFieldValues.setByField(cfg, "sslCertChainFile", value);
                    break;
                case "sslConnection":
                    ConfigFieldValues.setByField(cfg, "sslConnection", value);
                    break;
                case "sslPrivateKeyFile":
                    ConfigFieldValues.setByField(cfg, "sslPrivateKeyFile", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToHealthCheckerConfig(
            final HealthCheckerConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "checkIntervalSeconds":
                    ConfigFieldValues.setByField(cfg, "checkIntervalSeconds", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAsyncProfilerModuleConfig(
            final AsyncProfilerModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "jfrMaxSize":
                    cfg.setJfrMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.asyncprofiler.module.AsyncProfilerModuleConfig.jfrMaxSize"));
                    break;
                case "memoryParserEnabled":
                    cfg.setMemoryParserEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.asyncprofiler.module.AsyncProfilerModuleConfig.memoryParserEnabled"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToAWSFirehoseReceiverModuleConfig(
            final AWSFirehoseReceiverModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "acceptQueueSize":
                    ConfigFieldValues.setByField(cfg, "acceptQueueSize", value);
                    break;
                case "contextPath":
                    ConfigFieldValues.setByField(cfg, "contextPath", value);
                    break;
                case "enableTLS":
                    ConfigFieldValues.setByField(cfg, "enableTLS", value);
                    break;
                case "firehoseAccessKey":
                    ConfigFieldValues.setByField(cfg, "firehoseAccessKey", value);
                    break;
                case "host":
                    ConfigFieldValues.setByField(cfg, "host", value);
                    break;
                case "idleTimeOut":
                    ConfigFieldValues.setByField(cfg, "idleTimeOut", value);
                    break;
                case "maxRequestHeaderSize":
                    ConfigFieldValues.setByField(cfg, "maxRequestHeaderSize", value);
                    break;
                case "port":
                    ConfigFieldValues.setByField(cfg, "port", value);
                    break;
                case "tlsCertChainPath":
                    ConfigFieldValues.setByField(cfg, "tlsCertChainPath", value);
                    break;
                case "tlsKeyPath":
                    ConfigFieldValues.setByField(cfg, "tlsKeyPath", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToBrowserServiceModuleConfig(
            final BrowserServiceModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "sampleRate":
                    cfg.setSampleRate(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.browser.provider.BrowserServiceModuleConfig.sampleRate"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToConfigurationDiscoveryModuleConfig(
            final ConfigurationDiscoveryModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "disableMessageDigest":
                    cfg.setDisableMessageDigest(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.configuration.discovery.ConfigurationDiscoveryModuleConfig.disableMessageDigest"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToEBPFReceiverModuleConfig(
            final EBPFReceiverModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "continuousPolicyCacheTimeout":
                    cfg.setContinuousPolicyCacheTimeout(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.ebpf.provider.EBPFReceiverModuleConfig.continuousPolicyCacheTimeout"));
                    break;
                case "gRPCHost":
                    ConfigFieldValues.setByField(cfg, "gRPCHost", value);
                    break;
                case "gRPCPort":
                    ConfigFieldValues.setByField(cfg, "gRPCPort", value);
                    break;
                case "gRPCSslCertChainPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslCertChainPath", value);
                    break;
                case "gRPCSslEnabled":
                    ConfigFieldValues.setByField(cfg, "gRPCSslEnabled", value);
                    break;
                case "gRPCSslKeyPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslKeyPath", value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslTrustedCAsPath", value);
                    break;
                case "gRPCThreadPoolSize":
                    ConfigFieldValues.setByField(cfg, "gRPCThreadPoolSize", value);
                    break;
                case "maxConcurrentCallsPerConnection":
                    ConfigFieldValues.setByField(cfg, "maxConcurrentCallsPerConnection", value);
                    break;
                case "maxMessageSize":
                    ConfigFieldValues.setByField(cfg, "maxMessageSize", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToEnvoyMetricReceiverConfig(
            final EnvoyMetricReceiverConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "acceptMetricsService":
                    ConfigFieldValues.setByField(cfg, "acceptMetricsService", value);
                    break;
                case "alsHTTPAnalysis":
                    ConfigFieldValues.setByField(cfg, "alsHTTPAnalysis", value);
                    break;
                case "alsTCPAnalysis":
                    ConfigFieldValues.setByField(cfg, "alsTCPAnalysis", value);
                    break;
                case "clusterManagerMetricsAdapter":
                    ConfigFieldValues.setByField(cfg, "clusterManagerMetricsAdapter", value);
                    break;
                case "enabledEnvoyMetricsRules":
                    ConfigFieldValues.setByField(cfg, "enabledEnvoyMetricsRules", value);
                    break;
                case "gRPCHost":
                    ConfigFieldValues.setByField(cfg, "gRPCHost", value);
                    break;
                case "gRPCPort":
                    ConfigFieldValues.setByField(cfg, "gRPCPort", value);
                    break;
                case "gRPCSslCertChainPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslCertChainPath", value);
                    break;
                case "gRPCSslEnabled":
                    ConfigFieldValues.setByField(cfg, "gRPCSslEnabled", value);
                    break;
                case "gRPCSslKeyPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslKeyPath", value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    ConfigFieldValues.setByField(cfg, "gRPCSslTrustedCAsPath", value);
                    break;
                case "gRPCThreadPoolSize":
                    ConfigFieldValues.setByField(cfg, "gRPCThreadPoolSize", value);
                    break;
                case "istioServiceEntryIgnoredNamespaces":
                    ConfigFieldValues.setByField(cfg, "istioServiceEntryIgnoredNamespaces", value);
                    break;
                case "istioServiceNameRule":
                    ConfigFieldValues.setByField(cfg, "istioServiceNameRule", value);
                    break;
                case "k8sServiceNameRule":
                    ConfigFieldValues.setByField(cfg, "k8sServiceNameRule", value);
                    break;
                case "listenerMetricsAdapter":
                    ConfigFieldValues.setByField(cfg, "listenerMetricsAdapter", value);
                    break;
                case "maxConcurrentCallsPerConnection":
                    ConfigFieldValues.setByField(cfg, "maxConcurrentCallsPerConnection", value);
                    break;
                case "maxMessageSize":
                    ConfigFieldValues.setByField(cfg, "maxMessageSize", value);
                    break;
                case "serviceMetaInfoFactory":
                    ConfigFieldValues.setByField(cfg, "serviceMetaInfoFactory", value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToOtelMetricReceiverConfig(
            final OtelMetricReceiverConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "OTLP_TRACE_STORAGE_OTLP":
                    ConfigFieldValues.setByField(cfg, "OTLP_TRACE_STORAGE_OTLP", value);
                    break;
                case "OTLP_TRACE_STORAGE_ZIPKIN":
                    ConfigFieldValues.setByField(cfg, "OTLP_TRACE_STORAGE_ZIPKIN", value);
                    break;
                case "enabledHandlers":
                    cfg.setEnabledHandlers(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.enabledHandlers"));
                    break;
                case "enabledOtelMetricsRules":
                    ConfigFieldValues.setByField(cfg, "enabledOtelMetricsRules", value);
                    break;
                case "log":
                    ConfigFieldValues.setByField(cfg, "log", value);
                    break;
                case "otlpLogSearchableTags":
                    cfg.setOtlpLogSearchableTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpLogSearchableTags"));
                    break;
                case "otlpServiceGroupKeys":
                    cfg.setOtlpServiceGroupKeys(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpServiceGroupKeys"));
                    break;
                case "otlpTraceMaxSpansPerSecond":
                    cfg.setOtlpTraceMaxSpansPerSecond(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpTraceMaxSpansPerSecond"));
                    break;
                case "otlpTraceSampleRate":
                    cfg.setOtlpTraceSampleRate(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpTraceSampleRate"));
                    break;
                case "otlpTraceSearchableTags":
                    cfg.setOtlpTraceSearchableTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpTraceSearchableTags"));
                    break;
                case "otlpTraceStorage":
                    cfg.setOtlpTraceStorage(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverConfig.otlpTraceStorage"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToPprofModuleConfig(
            final PprofModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "memoryParserEnabled":
                    cfg.setMemoryParserEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.pprof.module.PprofModuleConfig.memoryParserEnabled"));
                    break;
                case "pprofMaxSize":
                    cfg.setPprofMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.pprof.module.PprofModuleConfig.pprofMaxSize"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToSharingServerConfig(
            final SharingServerConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "authentication":
                    cfg.setAuthentication(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.authentication"));
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCHost"));
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCPort"));
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCSslCertChainPath"));
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCSslEnabled"));
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCSslKeyPath"));
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCSslTrustedCAsPath"));
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.gRPCThreadPoolSize"));
                    break;
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.httpMaxRequestHeaderSize"));
                    break;
                case "maxConcurrentCallsPerConnection":
                    cfg.setMaxConcurrentCallsPerConnection(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.maxConcurrentCallsPerConnection"));
                    break;
                case "maxMessageSize":
                    cfg.setMaxMessageSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.maxMessageSize"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerConfig.restSSLKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTelegrafModuleConfig(
            final TelegrafModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "CONFIG_PATH":
                    ConfigFieldValues.setByField(cfg, "CONFIG_PATH", value);
                    break;
                case "activeFiles":
                    cfg.setActiveFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.telegraf.provider.TelegrafModuleConfig.activeFiles"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToZabbixModuleConfig(
            final ZabbixModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "CONFIG_PATH":
                    ConfigFieldValues.setByField(cfg, "CONFIG_PATH", value);
                    break;
                case "activeFiles":
                    cfg.setActiveFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixModuleConfig.activeFiles"));
                    break;
                case "host":
                    cfg.setHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixModuleConfig.host"));
                    break;
                case "port":
                    cfg.setPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixModuleConfig.port"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToZipkinReceiverConfig(
            final ZipkinReceiverConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "DEFAULT_SEARCHABLE_TAG_KEYS":
                    ConfigFieldValues.setByField(cfg, "DEFAULT_SEARCHABLE_TAG_KEYS", value);
                    break;
                case "enableHttpCollector":
                    cfg.setEnableHttpCollector(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.enableHttpCollector"));
                    break;
                case "enableKafkaCollector":
                    cfg.setEnableKafkaCollector(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.enableKafkaCollector"));
                    break;
                case "kafkaBootstrapServers":
                    cfg.setKafkaBootstrapServers(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaBootstrapServers"));
                    break;
                case "kafkaConsumerConfig":
                    cfg.setKafkaConsumerConfig(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaConsumerConfig"));
                    break;
                case "kafkaConsumers":
                    cfg.setKafkaConsumers(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaConsumers"));
                    break;
                case "kafkaGroupId":
                    cfg.setKafkaGroupId(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaGroupId"));
                    break;
                case "kafkaHandlerThreadPoolQueueSize":
                    cfg.setKafkaHandlerThreadPoolQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaHandlerThreadPoolQueueSize"));
                    break;
                case "kafkaHandlerThreadPoolSize":
                    cfg.setKafkaHandlerThreadPoolSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaHandlerThreadPoolSize"));
                    break;
                case "kafkaTopic":
                    cfg.setKafkaTopic(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.kafkaTopic"));
                    break;
                case "maxSpansPerSecond":
                    cfg.setMaxSpansPerSecond(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.maxSpansPerSecond"));
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restAcceptQueueSize"));
                    break;
                case "restContextPath":
                    cfg.setRestContextPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restContextPath"));
                    break;
                case "restHost":
                    cfg.setRestHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restHost"));
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(ConfigFieldValues.longValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restIdleTimeOut"));
                    break;
                case "restPort":
                    cfg.setRestPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restPort"));
                    break;
                case "restSSLCertChainPath":
                    cfg.setRestSSLCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restSSLCertChainPath"));
                    break;
                case "restSSLEnabled":
                    cfg.setRestSSLEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restSSLEnabled"));
                    break;
                case "restSSLKeyPath":
                    cfg.setRestSSLKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.restSSLKeyPath"));
                    break;
                case "sampleRate":
                    cfg.setSampleRate(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.sampleRate"));
                    break;
                case "searchableTracesTags":
                    cfg.setSearchableTracesTags(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig.searchableTracesTags"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToBanyanDBStorageConfig(
            final BanyanDBStorageConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "global":
                    cfg.setGlobal(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.Global.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.global"));
                    break;
                case "metadata":
                    cfg.setMetadata(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.Metadata.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.metadata"));
                    break;
                case "metricsDay":
                    cfg.setMetricsDay(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.MetricsDay.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.metricsDay"));
                    break;
                case "metricsHour":
                    cfg.setMetricsHour(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.MetricsHour.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.metricsHour"));
                    break;
                case "metricsMin":
                    cfg.setMetricsMin(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.MetricsMin.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.metricsMin"));
                    break;
                case "otlpTrace":
                    cfg.setOtlpTrace(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.OtlpTrace.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.otlpTrace"));
                    break;
                case "property":
                    cfg.setProperty(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.Property.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.property"));
                    break;
                case "recordsAIAgent":
                    cfg.setRecordsAIAgent(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsAIAgent.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsAIAgent"));
                    break;
                case "recordsBrowserErrorLog":
                    cfg.setRecordsBrowserErrorLog(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsBrowserErrorLog.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsBrowserErrorLog"));
                    break;
                case "recordsLog":
                    cfg.setRecordsLog(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsLog.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsLog"));
                    break;
                case "recordsNormal":
                    cfg.setRecordsNormal(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsNormal.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsNormal"));
                    break;
                case "recordsTrace":
                    cfg.setRecordsTrace(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsTrace.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsTrace"));
                    break;
                case "recordsZipkinTrace":
                    cfg.setRecordsZipkinTrace(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.RecordsZipkinTrace.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.recordsZipkinTrace"));
                    break;
                case "topNConfigs":
                    cfg.setTopNConfigs(ConfigFieldValues.referenceValue(value, Map.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.topNConfigs"));
                    break;
                case "trace":
                    cfg.setTrace(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.Trace.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.trace"));
                    break;
                case "zipkinTrace":
                    cfg.setZipkinTrace(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.ZipkinTrace.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig.zipkinTrace"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToMetadata(
            final BanyanDBStorageConfig.Metadata cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToMetricsDay(
            final BanyanDBStorageConfig.MetricsDay cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToMetricsHour(
            final BanyanDBStorageConfig.MetricsHour cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToMetricsMin(
            final BanyanDBStorageConfig.MetricsMin cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToOtlpTrace(
            final BanyanDBStorageConfig.OtlpTrace cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToProperty(
            final BanyanDBStorageConfig.Property cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsAIAgent(
            final BanyanDBStorageConfig.RecordsAIAgent cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsBrowserErrorLog(
            final BanyanDBStorageConfig.RecordsBrowserErrorLog cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsLog(
            final BanyanDBStorageConfig.RecordsLog cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsNormal(
            final BanyanDBStorageConfig.RecordsNormal cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsTrace(
            final BanyanDBStorageConfig.RecordsTrace cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToRecordsZipkinTrace(
            final BanyanDBStorageConfig.RecordsZipkinTrace cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTrace(
            final BanyanDBStorageConfig.Trace cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToZipkinTrace(
            final BanyanDBStorageConfig.ZipkinTrace cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToPrometheusConfig(
            final PrometheusConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "host":
                    cfg.setHost(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig.host"));
                    break;
                case "port":
                    cfg.setPort(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig.port"));
                    break;
                case "sslCertChainPath":
                    cfg.setSslCertChainPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig.sslCertChainPath"));
                    break;
                case "sslEnabled":
                    cfg.setSslEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig.sslEnabled"));
                    break;
                case "sslKeyPath":
                    cfg.setSslKeyPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusConfig.sslKeyPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTraceAnalyzerModuleConfig(
            final TraceAnalyzerModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "dedupCapacity":
                    cfg.setDedupCapacity(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.dedupCapacity"));
                    break;
                case "dedupWindow":
                    cfg.setDedupWindow(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.dedupWindow"));
                    break;
                case "nativeTalFiles":
                    cfg.setNativeTalFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.nativeTalFiles"));
                    break;
                case "pairingSamplePercent":
                    cfg.setPairingSamplePercent(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.pairingSamplePercent"));
                    break;
                case "talFiles":
                    cfg.setTalFiles(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.talFiles"));
                    break;
                case "talPath":
                    cfg.setTalPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig.talPath"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToModel(
            final GenAIConfig.Model cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "aliases":
                    cfg.setAliases(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Model.aliases"));
                    break;
                case "inputEstimatedCostPerM":
                    cfg.setInputEstimatedCostPerM(ConfigFieldValues.doubleValue(value, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Model.inputEstimatedCostPerM"));
                    break;
                case "name":
                    cfg.setName(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Model.name"));
                    break;
                case "outputEstimatedCostPerM":
                    cfg.setOutputEstimatedCostPerM(ConfigFieldValues.doubleValue(value, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Model.outputEstimatedCostPerM"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToProvider(
            final GenAIConfig.Provider cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "baseUrl":
                    cfg.setBaseUrl(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Provider.baseUrl"));
                    break;
                case "models":
                    cfg.setModels(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Provider.models"));
                    break;
                case "prefixMatch":
                    cfg.setPrefixMatch(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Provider.prefixMatch"));
                    break;
                case "provider":
                    cfg.setProvider(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig$Provider.provider"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToGlobal(
            final BanyanDBStorageConfig.Global cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "asyncProfilerTaskQueryMaxSize":
                    cfg.setAsyncProfilerTaskQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.asyncProfilerTaskQueryMaxSize"));
                    break;
                case "cleanupUnusedTopNRules":
                    cfg.setCleanupUnusedTopNRules(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.cleanupUnusedTopNRules"));
                    break;
                case "compatibleServerApiVersions":
                    cfg.setCompatibleServerApiVersions(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.compatibleServerApiVersions"));
                    break;
                case "concurrentWriteThreads":
                    cfg.setConcurrentWriteThreads(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.concurrentWriteThreads"));
                    break;
                case "flushInterval":
                    cfg.setFlushInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.flushInterval"));
                    break;
                case "flushTimeout":
                    cfg.setFlushTimeout(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.flushTimeout"));
                    break;
                case "maxBulkSize":
                    cfg.setMaxBulkSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.maxBulkSize"));
                    break;
                case "metadataQueryMaxSize":
                    cfg.setMetadataQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.metadataQueryMaxSize"));
                    break;
                case "namespace":
                    cfg.setNamespace(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.namespace"));
                    break;
                case "password":
                    cfg.setPassword(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.password"));
                    break;
                case "pprofTaskQueryMaxSize":
                    cfg.setPprofTaskQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.pprofTaskQueryMaxSize"));
                    break;
                case "profileDataQueryBatchSize":
                    cfg.setProfileDataQueryBatchSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.profileDataQueryBatchSize"));
                    break;
                case "profileTaskQueryMaxSize":
                    cfg.setProfileTaskQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.profileTaskQueryMaxSize"));
                    break;
                case "resultWindowMaxSize":
                    cfg.setResultWindowMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.resultWindowMaxSize"));
                    break;
                case "secretsManagementFile":
                    cfg.setSecretsManagementFile(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.secretsManagementFile"));
                    break;
                case "segmentQueryMaxSize":
                    cfg.setSegmentQueryMaxSize(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.segmentQueryMaxSize"));
                    break;
                case "sslTrustCAPath":
                    cfg.setSslTrustCAPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.sslTrustCAPath"));
                    break;
                case "targets":
                    cfg.setTargets(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.targets"));
                    break;
                case "user":
                    cfg.setUser(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Global.user"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToGroupResource(
            final BanyanDBStorageConfig.GroupResource cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.additionalLifecycleStages"));
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.defaultQueryStages"));
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableColdStage"));
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.enableWarmStage"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.shardNum"));
                    break;
                case "tracePipeline":
                    cfg.setTracePipeline(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TracePipeline.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.tracePipeline"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$GroupResource.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToSamplerPluginConfig(
            final BanyanDBStorageConfig.SamplerPluginConfig cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "abiVersion":
                    cfg.setAbiVersion(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$SamplerPluginConfig.abiVersion"));
                    break;
                case "config":
                    cfg.setConfig(ConfigFieldValues.referenceValue(value, Map.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$SamplerPluginConfig.config"));
                    break;
                case "name":
                    cfg.setName(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$SamplerPluginConfig.name"));
                    break;
                case "path":
                    cfg.setPath(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$SamplerPluginConfig.path"));
                    break;
                case "symbol":
                    cfg.setSymbol(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$SamplerPluginConfig.symbol"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToStage(
            final BanyanDBStorageConfig.Stage cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "close":
                    cfg.setClose(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.close"));
                    break;
                case "name":
                    cfg.setName(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.StageName.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.name"));
                    break;
                case "nodeSelector":
                    cfg.setNodeSelector(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.nodeSelector"));
                    break;
                case "replicas":
                    cfg.setReplicas(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.replicas"));
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.segmentInterval"));
                    break;
                case "shardNum":
                    cfg.setShardNum(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.shardNum"));
                    break;
                case "ttl":
                    cfg.setTtl(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$Stage.ttl"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTopN(
            final BanyanDBStorageConfig.TopN cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "countersNumber":
                    cfg.setCountersNumber(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.countersNumber"));
                    break;
                case "excludes":
                    cfg.setExcludes(ConfigFieldValues.referenceValue(value, Set.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.excludes"));
                    break;
                case "groupByTagNames":
                    cfg.setGroupByTagNames(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.groupByTagNames"));
                    break;
                case "lruSizeHourDay":
                    cfg.setLruSizeHourDay(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.lruSizeHourDay"));
                    break;
                case "lruSizeMinute":
                    cfg.setLruSizeMinute(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.lruSizeMinute"));
                    break;
                case "name":
                    cfg.setName(ConfigFieldValues.referenceValue(value, String.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.name"));
                    break;
                case "sort":
                    cfg.setSort(ConfigFieldValues.referenceValue(value, BanyanDBStorageConfig.TopN.Sort.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TopN.sort"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToTracePipeline(
            final BanyanDBStorageConfig.TracePipeline cfg, final Properties src,
            final String moduleName, final String providerName) throws IllegalAccessException {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            switch (key) {
                case "enabled":
                    cfg.setEnabled(ConfigFieldValues.booleanValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TracePipeline.enabled"));
                    break;
                case "enabledEvents":
                    cfg.setEnabledEvents(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TracePipeline.enabledEvents"));
                    break;
                case "finalizeGraceSeconds":
                    cfg.setFinalizeGraceSeconds(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TracePipeline.finalizeGraceSeconds"));
                    break;
                case "mergeGraceSeconds":
                    cfg.setMergeGraceSeconds(ConfigFieldValues.intValue(value, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TracePipeline.mergeGraceSeconds"));
                    break;
                case "plugins":
                    cfg.setPlugins(ConfigFieldValues.referenceValue(value, List.class, "org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageConfig$TracePipeline.plugins"));
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

}
