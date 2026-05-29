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
import org.yaml.snakeyaml.Yaml;

import java.util.Set;
import org.apache.skywalking.oap.analyzer.genai.config.GenAIConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.query.graphql.GraphQLQueryConfig;
import org.apache.skywalking.oap.query.logql.LogQLConfig;
import org.apache.skywalking.oap.query.promql.PromQLConfig;
import org.apache.skywalking.oap.query.traceql.TraceQLConfig;
import org.apache.skywalking.oap.query.zipkin.ZipkinQueryConfig;
import org.apache.skywalking.oap.server.admin.inspect.InspectModuleConfig;
import org.apache.skywalking.oap.server.admin.server.module.AdminServerModuleConfig;
import org.apache.skywalking.oap.server.admin.status.StatusModuleConfig;
import org.apache.skywalking.oap.server.admin.uimanagement.UIManagementModuleConfig;
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
        if (dest instanceof CoreModuleConfig) {
            copyToCoreModuleConfig((CoreModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ClusterModuleKubernetesConfig) {
            copyToClusterModuleKubernetesConfig((ClusterModuleKubernetesConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof SharingServerConfig) {
            copyToSharingServerConfig((SharingServerConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AnalyzerModuleConfig) {
            copyToAnalyzerModuleConfig((AnalyzerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof GenAIConfig) {
            copyToGenAIConfig((GenAIConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZipkinReceiverConfig) {
            copyToZipkinReceiverConfig((ZipkinReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZipkinQueryConfig) {
            copyToZipkinQueryConfig((ZipkinQueryConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof EnvoyMetricReceiverConfig) {
            copyToEnvoyMetricReceiverConfig((EnvoyMetricReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof LogAnalyzerModuleConfig) {
            copyToLogAnalyzerModuleConfig((LogAnalyzerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof OtelMetricReceiverConfig) {
            copyToOtelMetricReceiverConfig((OtelMetricReceiverConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BrowserServiceModuleConfig) {
            copyToBrowserServiceModuleConfig((BrowserServiceModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ConfigurationDiscoveryModuleConfig) {
            copyToConfigurationDiscoveryModuleConfig((ConfigurationDiscoveryModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ZabbixModuleConfig) {
            copyToZabbixModuleConfig((ZabbixModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof EBPFReceiverModuleConfig) {
            copyToEBPFReceiverModuleConfig((EBPFReceiverModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AsyncProfilerModuleConfig) {
            copyToAsyncProfilerModuleConfig((AsyncProfilerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof PprofModuleConfig) {
            copyToPprofModuleConfig((PprofModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof TelegrafModuleConfig) {
            copyToTelegrafModuleConfig((TelegrafModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AWSFirehoseReceiverModuleConfig) {
            copyToAWSFirehoseReceiverModuleConfig((AWSFirehoseReceiverModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof KafkaFetcherConfig) {
            copyToKafkaFetcherConfig((KafkaFetcherConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof CiliumFetcherConfig) {
            copyToCiliumFetcherConfig((CiliumFetcherConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig) {
            copyToBanyanDBStorageConfig((BanyanDBStorageConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof GraphQLQueryConfig) {
            copyToGraphQLQueryConfig((GraphQLQueryConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof HealthCheckerConfig) {
            copyToHealthCheckerConfig((HealthCheckerConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof AIPipelineConfig) {
            copyToAIPipelineConfig((AIPipelineConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof PromQLConfig) {
            copyToPromQLConfig((PromQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof LogQLConfig) {
            copyToLogQLConfig((LogQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof TraceQLConfig) {
            copyToTraceQLConfig((TraceQLConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof PrometheusConfig) {
            copyToPrometheusConfig((PrometheusConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof ExporterSetting) {
            copyToExporterSetting((ExporterSetting) dest, src, moduleName, providerName);
        } else if (dest instanceof ConfigmapConfigurationSettings) {
            copyToConfigmapConfigurationSettings((ConfigmapConfigurationSettings) dest, src, moduleName, providerName);
        } else if (dest instanceof AdminServerModuleConfig) {
            copyToAdminServerModuleConfig((AdminServerModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof StatusModuleConfig) {
            copyToStatusModuleConfig((StatusModuleConfig) dest, src, moduleName, providerName);
        } else if (dest instanceof InspectModuleConfig) {
            // InspectModuleConfig has no configurable fields; nothing to copy.
        } else if (dest instanceof UIManagementModuleConfig) {
            // UIManagementModuleConfig has no configurable fields; nothing to copy.
        } else if (dest instanceof GenAIConfig.Model) {
            copyToModel((GenAIConfig.Model) dest, src, moduleName, providerName);
        } else if (dest instanceof GenAIConfig.Provider) {
            copyToProvider((GenAIConfig.Provider) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Global) {
            copyToGlobal((BanyanDBStorageConfig.Global) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsNormal) {
            copyToRecordsNormal((BanyanDBStorageConfig.RecordsNormal) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Trace) {
            copyToTrace((BanyanDBStorageConfig.Trace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.ZipkinTrace) {
            copyToZipkinTrace((BanyanDBStorageConfig.ZipkinTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsTrace) {
            copyToRecordsTrace((BanyanDBStorageConfig.RecordsTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsZipkinTrace) {
            copyToRecordsZipkinTrace((BanyanDBStorageConfig.RecordsZipkinTrace) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsLog) {
            copyToRecordsLog((BanyanDBStorageConfig.RecordsLog) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.RecordsBrowserErrorLog) {
            copyToRecordsBrowserErrorLog((BanyanDBStorageConfig.RecordsBrowserErrorLog) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsMin) {
            copyToMetricsMin((BanyanDBStorageConfig.MetricsMin) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsHour) {
            copyToMetricsHour((BanyanDBStorageConfig.MetricsHour) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.MetricsDay) {
            copyToMetricsDay((BanyanDBStorageConfig.MetricsDay) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Metadata) {
            copyToMetadata((BanyanDBStorageConfig.Metadata) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Property) {
            copyToProperty((BanyanDBStorageConfig.Property) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.TopN) {
            copyToTopN((BanyanDBStorageConfig.TopN) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.GroupResource) {
            copyToGroupResource((BanyanDBStorageConfig.GroupResource) dest, src, moduleName, providerName);
        } else if (dest instanceof BanyanDBStorageConfig.Stage) {
            copyToStage((BanyanDBStorageConfig.Stage) dest, src, moduleName, providerName);
        } else {
            throw new IllegalArgumentException("Unknown config type: "
                + dest.getClass().getName()
                + " in " + providerName + " provider of " + moduleName + " module."
                + " Add it to ConfigInitializerGenerator and regenerate.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToCoreModuleConfig(
            final CoreModuleConfig cfg, final Properties src,
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "role":
                    cfg.setRole((String) value);
                    break;
                case "namespace":
                    cfg.setNamespace((String) value);
                    break;
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restMaxThreads":
                    cfg.setRestMaxThreads(((Number) value).intValue());
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost((String) value);
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(((Number) value).intValue());
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled((boolean) value);
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath((String) value);
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath((String) value);
                    break;
                case "gRPCSslTrustedCAPath":
                    cfg.setGRPCSslTrustedCAPath((String) value);
                    break;
                case "maxConcurrentCallsPerConnection":
                    cfg.setMaxConcurrentCallsPerConnection(((Number) value).intValue());
                    break;
                case "maxMessageSize":
                    cfg.setMaxMessageSize(((Number) value).intValue());
                    break;
                case "topNReportPeriod":
                    cfg.setTopNReportPeriod(((Number) value).intValue());
                    break;
                case "l1FlushPeriod":
                    cfg.setL1FlushPeriod(((Number) value).longValue());
                    break;
                case "storageSessionTimeout":
                    cfg.setStorageSessionTimeout(((Number) value).longValue());
                    break;
                case "downsampling":
                    cfg.getDownsampling().clear();
                    cfg.getDownsampling().addAll((List) value);
                    break;
                case "persistentPeriod":
                    cfg.setPersistentPeriod(((Number) value).intValue());
                    break;
                case "enableDataKeeperExecutor":
                    cfg.setEnableDataKeeperExecutor((boolean) value);
                    break;
                case "dataKeeperExecutePeriod":
                    cfg.setDataKeeperExecutePeriod(((Number) value).intValue());
                    break;
                case "metricsDataTTL":
                    cfg.setMetricsDataTTL(((Number) value).intValue());
                    break;
                case "recordDataTTL":
                    cfg.setRecordDataTTL(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(((Number) value).intValue());
                    break;
                case "remoteTimeout":
                    cfg.setRemoteTimeout(((Number) value).intValue());
                    break;
                case "maxSizeOfNetworkAddressAlias":
                    cfg.setMaxSizeOfNetworkAddressAlias(((Number) value).longValue());
                    break;
                case "maxSizeOfProfileTask":
                    cfg.setMaxSizeOfProfileTask(((Number) value).longValue());
                    break;
                case "maxSizeOfPprofTask":
                    cfg.setMaxSizeOfPprofTask(((Number) value).longValue());
                    break;
                case "maxPageSizeOfQueryProfileSnapshot":
                    cfg.setMaxPageSizeOfQueryProfileSnapshot(((Number) value).intValue());
                    break;
                case "maxSizeOfAnalyzeProfileSnapshot":
                    cfg.setMaxSizeOfAnalyzeProfileSnapshot(((Number) value).intValue());
                    break;
                case "maxDurationOfQueryEBPFProfilingData":
                    cfg.setMaxDurationOfQueryEBPFProfilingData(((Number) value).intValue());
                    break;
                case "maxThreadCountOfQueryEBPFProfilingData":
                    cfg.setMaxThreadCountOfQueryEBPFProfilingData(((Number) value).intValue());
                    break;
                case "activeExtraModelColumns":
                    cfg.setActiveExtraModelColumns((boolean) value);
                    break;
                case "serviceNameMaxLength":
                    cfg.setServiceNameMaxLength(((Number) value).intValue());
                    break;
                case "instanceNameMaxLength":
                    cfg.setInstanceNameMaxLength(((Number) value).intValue());
                    break;
                case "endpointNameMaxLength":
                    cfg.setEndpointNameMaxLength(((Number) value).intValue());
                    break;
                case "searchableTracesTags":
                    cfg.setSearchableTracesTags((String) value);
                    break;
                case "searchableTracesTagsWatcher":
                    cfg.setSearchableTracesTagsWatcher((SearchableTracesTagsWatcher) value);
                    break;
                case "searchableLogsTags":
                    cfg.setSearchableLogsTags((String) value);
                    break;
                case "searchableAlarmTags":
                    cfg.setSearchableAlarmTags((String) value);
                    break;
                case "autocompleteTagKeysQueryMaxSize":
                    cfg.setAutocompleteTagKeysQueryMaxSize(((Number) value).intValue());
                    break;
                case "autocompleteTagValuesQueryMaxSize":
                    cfg.setAutocompleteTagValuesQueryMaxSize(((Number) value).intValue());
                    break;
                case "prepareThreads":
                    cfg.setPrepareThreads(((Number) value).intValue());
                    break;
                case "enableEndpointNameGroupingByOpenapi":
                    cfg.setEnableEndpointNameGroupingByOpenapi((boolean) value);
                    break;
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(((Number) value).intValue());
                    break;
                case "syncPeriodHttpUriRecognitionPattern":
                    cfg.setSyncPeriodHttpUriRecognitionPattern(((Number) value).intValue());
                    break;
                case "trainingPeriodHttpUriRecognitionPattern":
                    cfg.setTrainingPeriodHttpUriRecognitionPattern(((Number) value).intValue());
                    break;
                case "maxHttpUrisNumberPerService":
                    cfg.setMaxHttpUrisNumberPerService(((Number) value).intValue());
                    break;
                case "serviceCacheRefreshInterval":
                    cfg.setServiceCacheRefreshInterval(((Number) value).intValue());
                    break;
                case "enableHierarchy":
                    cfg.setEnableHierarchy((boolean) value);
                    break;
                case "maxHeapMemoryUsagePercent":
                    cfg.setMaxHeapMemoryUsagePercent(((Number) value).longValue());
                    break;
                case "maxDirectMemoryUsage":
                    cfg.setMaxDirectMemoryUsage(((Number) value).longValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "namespace":
                    cfg.setNamespace((String) value);
                    break;
                case "labelSelector":
                    cfg.setLabelSelector((String) value);
                    break;
                case "uidEnvName":
                    cfg.setUidEnvName((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost((String) value);
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(((Number) value).intValue());
                    break;
                case "maxConcurrentCallsPerConnection":
                    cfg.setMaxConcurrentCallsPerConnection(((Number) value).intValue());
                    break;
                case "maxMessageSize":
                    cfg.setMaxMessageSize(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(((Number) value).intValue());
                    break;
                case "authentication":
                    cfg.setAuthentication((String) value);
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled((boolean) value);
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath((String) value);
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath((String) value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath((String) value);
                    break;
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "traceSamplingPolicySettingsFile":
                    cfg.setTraceSamplingPolicySettingsFile((String) value);
                    break;
                case "noUpstreamRealAddressAgents":
                    cfg.setNoUpstreamRealAddressAgents((String) value);
                    break;
                case "slowDBAccessThreshold":
                    cfg.setSlowDBAccessThreshold((String) value);
                    break;
                case "dbLatencyThresholdsAndWatcher":
                    cfg.setDbLatencyThresholdsAndWatcher((DBLatencyThresholdsAndWatcher) value);
                    break;
                case "slowCacheWriteThreshold":
                    cfg.setSlowCacheWriteThreshold((String) value);
                    break;
                case "cacheWriteLatencyThresholdsAndWatcher":
                    cfg.setCacheWriteLatencyThresholdsAndWatcher((CacheWriteLatencyThresholdsAndWatcher) value);
                    break;
                case "slowCacheReadThreshold":
                    cfg.setSlowCacheReadThreshold((String) value);
                    break;
                case "cacheReadLatencyThresholdsAndWatcher":
                    cfg.setCacheReadLatencyThresholdsAndWatcher((CacheReadLatencyThresholdsAndWatcher) value);
                    break;
                case "uninstrumentedGatewaysConfig":
                    cfg.setUninstrumentedGatewaysConfig((UninstrumentedGatewaysConfig) value);
                    break;
                case "traceSamplingPolicyWatcher":
                    cfg.setTraceSamplingPolicyWatcher((TraceSamplingPolicyWatcher) value);
                    break;
                case "traceAnalysis":
                    cfg.setTraceAnalysis((boolean) value);
                    break;
                case "maxSlowSQLLength":
                    cfg.setMaxSlowSQLLength(((Number) value).intValue());
                    break;
                case "configPath":
                    log.warn("Cannot set final field 'configPath' in {} provider of {} module", providerName, moduleName);
                    break;
                case "meterAnalyzerActiveFiles":
                    cfg.setMeterAnalyzerActiveFiles((String) value);
                    break;
                case "forceSampleErrorSegment":
                    cfg.setForceSampleErrorSegment((boolean) value);
                    break;
                case "segmentStatusAnalysisStrategy":
                    cfg.setSegmentStatusAnalysisStrategy((String) value);
                    break;
                case "virtualPeers":
                    cfg.setVirtualPeers((List) value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyToGenAIConfig(
            final GenAIConfig cfg, final Properties src,
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "providers":
                    cfg.setProviders((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "enableHttpCollector":
                    cfg.setEnableHttpCollector((boolean) value);
                    break;
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "searchableTracesTags":
                    cfg.setSearchableTracesTags((String) value);
                    break;
                case "sampleRate":
                    cfg.setSampleRate(((Number) value).intValue());
                    break;
                case "maxSpansPerSecond":
                    cfg.setMaxSpansPerSecond(((Number) value).intValue());
                    break;
                case "enableKafkaCollector":
                    cfg.setEnableKafkaCollector((boolean) value);
                    break;
                case "kafkaBootstrapServers":
                    cfg.setKafkaBootstrapServers((String) value);
                    break;
                case "kafkaGroupId":
                    cfg.setKafkaGroupId((String) value);
                    break;
                case "kafkaTopic":
                    cfg.setKafkaTopic((String) value);
                    break;
                case "kafkaConsumerConfig":
                    cfg.setKafkaConsumerConfig((String) value);
                    break;
                case "kafkaConsumers":
                    cfg.setKafkaConsumers(((Number) value).intValue());
                    break;
                case "kafkaHandlerThreadPoolSize":
                    cfg.setKafkaHandlerThreadPoolSize(((Number) value).intValue());
                    break;
                case "kafkaHandlerThreadPoolQueueSize":
                    cfg.setKafkaHandlerThreadPoolQueueSize(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "lookback":
                    cfg.setLookback(((Number) value).longValue());
                    break;
                case "namesMaxAge":
                    cfg.setNamesMaxAge(((Number) value).intValue());
                    break;
                case "uiQueryLimit":
                    cfg.setUiQueryLimit(((Number) value).intValue());
                    break;
                case "uiEnvironment":
                    cfg.setUiEnvironment((String) value);
                    break;
                case "uiDefaultLookback":
                    cfg.setUiDefaultLookback(((Number) value).longValue());
                    break;
                case "uiSearchEnabled":
                    cfg.setUiSearchEnabled((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "acceptMetricsService":
                    cfg.setAcceptMetricsService((boolean) value);
                    break;
                case "alsHTTPAnalysis":
                    cfg.setAlsHTTPAnalysis((String) value);
                    break;
                case "alsTCPAnalysis":
                    cfg.setAlsTCPAnalysis((String) value);
                    break;
                case "k8sServiceNameRule":
                    cfg.setK8sServiceNameRule((String) value);
                    break;
                case "istioServiceNameRule":
                    cfg.setIstioServiceNameRule((String) value);
                    break;
                case "istioServiceEntryIgnoredNamespaces":
                    cfg.setIstioServiceEntryIgnoredNamespaces((String) value);
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost((String) value);
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(((Number) value).intValue());
                    break;
                case "maxConcurrentCallsPerConnection":
                    cfg.setMaxConcurrentCallsPerConnection(((Number) value).intValue());
                    break;
                case "maxMessageSize":
                    cfg.setMaxMessageSize(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(((Number) value).intValue());
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled((boolean) value);
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath((String) value);
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath((String) value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath((String) value);
                    break;
                case "enabledEnvoyMetricsRules":
                    cfg.setEnabledEnvoyMetricsRules((String) value);
                    break;
                case "serviceMetaInfoFactory":
                    log.warn("Cannot set final field 'serviceMetaInfoFactory' in {} provider of {} module", providerName, moduleName);
                    break;
                case "clusterManagerMetricsAdapter":
                    log.warn("Cannot set final field 'clusterManagerMetricsAdapter' in {} provider of {} module", providerName, moduleName);
                    break;
                case "listenerMetricsAdapter":
                    log.warn("Cannot set final field 'listenerMetricsAdapter' in {} provider of {} module", providerName, moduleName);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "lalPath":
                    cfg.setLalPath((String) value);
                    break;
                case "malPath":
                    cfg.setMalPath((String) value);
                    break;
                case "lalFiles":
                    cfg.setLalFiles((String) value);
                    break;
                case "malFiles":
                    cfg.setMalFiles((String) value);
                    break;
                case "meterConfigs":
                    cfg.setMeterConfigs((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "enabledHandlers":
                    cfg.setEnabledHandlers((String) value);
                    break;
                case "enabledOtelMetricsRules":
                    cfg.setEnabledOtelMetricsRules((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "sampleRate":
                    cfg.setSampleRate(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "disableMessageDigest":
                    cfg.setDisableMessageDigest((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "port":
                    cfg.setPort(((Number) value).intValue());
                    break;
                case "host":
                    cfg.setHost((String) value);
                    break;
                case "activeFiles":
                    cfg.setActiveFiles((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "continuousPolicyCacheTimeout":
                    cfg.setContinuousPolicyCacheTimeout(((Number) value).intValue());
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost((String) value);
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(((Number) value).intValue());
                    break;
                case "maxConcurrentCallsPerConnection":
                    cfg.setMaxConcurrentCallsPerConnection(((Number) value).intValue());
                    break;
                case "maxMessageSize":
                    cfg.setMaxMessageSize(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(((Number) value).intValue());
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled((boolean) value);
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath((String) value);
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath((String) value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "jfrMaxSize":
                    cfg.setJfrMaxSize(((Number) value).intValue());
                    break;
                case "memoryParserEnabled":
                    cfg.setMemoryParserEnabled((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "pprofMaxSize":
                    cfg.setPprofMaxSize(((Number) value).intValue());
                    break;
                case "memoryParserEnabled":
                    cfg.setMemoryParserEnabled((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "activeFiles":
                    cfg.setActiveFiles((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "host":
                    cfg.setHost((String) value);
                    break;
                case "port":
                    cfg.setPort(((Number) value).intValue());
                    break;
                case "contextPath":
                    cfg.setContextPath((String) value);
                    break;
                case "idleTimeOut":
                    cfg.setIdleTimeOut(((Number) value).longValue());
                    break;
                case "acceptQueueSize":
                    cfg.setAcceptQueueSize(((Number) value).intValue());
                    break;
                case "maxRequestHeaderSize":
                    cfg.setMaxRequestHeaderSize(((Number) value).intValue());
                    break;
                case "firehoseAccessKey":
                    cfg.setFirehoseAccessKey((String) value);
                    break;
                case "enableTLS":
                    cfg.setEnableTLS((boolean) value);
                    break;
                case "tlsKeyPath":
                    cfg.setTlsKeyPath((String) value);
                    break;
                case "tlsCertChainPath":
                    cfg.setTlsCertChainPath((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "kafkaConsumerConfig":
                    cfg.setKafkaConsumerConfig((Properties) value);
                    break;
                case "bootstrapServers":
                    cfg.setBootstrapServers((String) value);
                    break;
                case "groupId":
                    cfg.setGroupId((String) value);
                    break;
                case "partitions":
                    cfg.setPartitions(((Number) value).intValue());
                    break;
                case "replicationFactor":
                    cfg.setReplicationFactor(((Number) value).intValue());
                    break;
                case "enableNativeProtoLog":
                    cfg.setEnableNativeProtoLog((boolean) value);
                    break;
                case "enableNativeJsonLog":
                    cfg.setEnableNativeJsonLog((boolean) value);
                    break;
                case "configPath":
                    cfg.setConfigPath((String) value);
                    break;
                case "topicNameOfMetrics":
                    cfg.setTopicNameOfMetrics((String) value);
                    break;
                case "topicNameOfProfiling":
                    cfg.setTopicNameOfProfiling((String) value);
                    break;
                case "topicNameOfTracingSegments":
                    cfg.setTopicNameOfTracingSegments((String) value);
                    break;
                case "topicNameOfManagements":
                    cfg.setTopicNameOfManagements((String) value);
                    break;
                case "topicNameOfMeters":
                    cfg.setTopicNameOfMeters((String) value);
                    break;
                case "topicNameOfLogs":
                    cfg.setTopicNameOfLogs((String) value);
                    break;
                case "topicNameOfJsonLogs":
                    cfg.setTopicNameOfJsonLogs((String) value);
                    break;
                case "kafkaHandlerThreadPoolSize":
                    cfg.setKafkaHandlerThreadPoolSize(((Number) value).intValue());
                    break;
                case "kafkaHandlerThreadPoolQueueSize":
                    cfg.setKafkaHandlerThreadPoolQueueSize(((Number) value).intValue());
                    break;
                case "namespace":
                    cfg.setNamespace((String) value);
                    break;
                case "mm2SourceAlias":
                    cfg.setMm2SourceAlias((String) value);
                    break;
                case "mm2SourceSeparator":
                    cfg.setMm2SourceSeparator((String) value);
                    break;
                case "consumers":
                    cfg.setConsumers(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "peerHost":
                    cfg.setPeerHost((String) value);
                    break;
                case "peerPort":
                    cfg.setPeerPort(((Number) value).intValue());
                    break;
                case "fetchFailureRetrySecond":
                    cfg.setFetchFailureRetrySecond(((Number) value).intValue());
                    break;
                case "sslConnection":
                    cfg.setSslConnection((boolean) value);
                    break;
                case "sslPrivateKeyFile":
                    cfg.setSslPrivateKeyFile((String) value);
                    break;
                case "sslCertChainFile":
                    cfg.setSslCertChainFile((String) value);
                    break;
                case "sslCaFile":
                    cfg.setSslCaFile((String) value);
                    break;
                case "convertClientAsServerTraffic":
                    cfg.setConvertClientAsServerTraffic((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "global":
                    cfg.setGlobal((BanyanDBStorageConfig.Global) value);
                    break;
                case "recordsNormal":
                    cfg.setRecordsNormal((BanyanDBStorageConfig.RecordsNormal) value);
                    break;
                case "trace":
                    cfg.setTrace((BanyanDBStorageConfig.Trace) value);
                    break;
                case "zipkinTrace":
                    cfg.setZipkinTrace((BanyanDBStorageConfig.ZipkinTrace) value);
                    break;
                case "recordsTrace":
                    cfg.setRecordsTrace((BanyanDBStorageConfig.RecordsTrace) value);
                    break;
                case "recordsZipkinTrace":
                    cfg.setRecordsZipkinTrace((BanyanDBStorageConfig.RecordsZipkinTrace) value);
                    break;
                case "recordsLog":
                    cfg.setRecordsLog((BanyanDBStorageConfig.RecordsLog) value);
                    break;
                case "recordsBrowserErrorLog":
                    cfg.setRecordsBrowserErrorLog((BanyanDBStorageConfig.RecordsBrowserErrorLog) value);
                    break;
                case "metricsMin":
                    cfg.setMetricsMin((BanyanDBStorageConfig.MetricsMin) value);
                    break;
                case "metricsHour":
                    cfg.setMetricsHour((BanyanDBStorageConfig.MetricsHour) value);
                    break;
                case "metricsDay":
                    cfg.setMetricsDay((BanyanDBStorageConfig.MetricsDay) value);
                    break;
                case "metadata":
                    cfg.setMetadata((BanyanDBStorageConfig.Metadata) value);
                    break;
                case "property":
                    cfg.setProperty((BanyanDBStorageConfig.Property) value);
                    break;
                case "topNConfigs":
                    cfg.setTopNConfigs((Map) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "enableLogTestTool":
                    cfg.setEnableLogTestTool((boolean) value);
                    break;
                case "maxQueryComplexity":
                    cfg.setMaxQueryComplexity(((Number) value).intValue());
                    break;
                case "enableOnDemandPodLog":
                    cfg.setEnableOnDemandPodLog((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "checkIntervalSeconds":
                    cfg.setCheckIntervalSeconds(((Number) value).longValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "uriRecognitionServerAddr":
                    cfg.setUriRecognitionServerAddr((String) value);
                    break;
                case "uriRecognitionServerPort":
                    cfg.setUriRecognitionServerPort(((Number) value).intValue());
                    break;
                case "baselineServerAddr":
                    cfg.setBaselineServerAddr((String) value);
                    break;
                case "baselineServerPort":
                    cfg.setBaselineServerPort(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "buildInfoVersion":
                    cfg.setBuildInfoVersion((String) value);
                    break;
                case "buildInfoRevision":
                    cfg.setBuildInfoRevision((String) value);
                    break;
                case "buildInfoBranch":
                    cfg.setBuildInfoBranch((String) value);
                    break;
                case "buildInfoBuildUser":
                    cfg.setBuildInfoBuildUser((String) value);
                    break;
                case "buildInfoBuildDate":
                    cfg.setBuildInfoBuildDate((String) value);
                    break;
                case "buildInfoGoVersion":
                    cfg.setBuildInfoGoVersion((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "restContextPath":
                    cfg.setRestContextPath((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "restHost":
                    cfg.setRestHost((String) value);
                    break;
                case "restPort":
                    cfg.setRestPort(((Number) value).intValue());
                    break;
                case "enableDatasourceZipkin":
                    cfg.setEnableDatasourceZipkin((boolean) value);
                    break;
                case "enableDatasourceSkywalking":
                    cfg.setEnableDatasourceSkywalking((boolean) value);
                    break;
                case "restContextPathZipkin":
                    cfg.setRestContextPathZipkin((String) value);
                    break;
                case "restContextPathSkywalking":
                    cfg.setRestContextPathSkywalking((String) value);
                    break;
                case "restIdleTimeOut":
                    cfg.setRestIdleTimeOut(((Number) value).longValue());
                    break;
                case "restAcceptQueueSize":
                    cfg.setRestAcceptQueueSize(((Number) value).intValue());
                    break;
                case "lookback":
                    cfg.setLookback(((Number) value).longValue());
                    break;
                case "zipkinTracesListResultTags":
                    cfg.setZipkinTracesListResultTags((String) value);
                    break;
                case "skywalkingTracesListResultTags":
                    cfg.setSkywalkingTracesListResultTags((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "host":
                    cfg.setHost((String) value);
                    break;
                case "port":
                    cfg.setPort(((Number) value).intValue());
                    break;
                case "sslEnabled":
                    cfg.setSslEnabled((boolean) value);
                    break;
                case "sslKeyPath":
                    cfg.setSslKeyPath((String) value);
                    break;
                case "sslCertChainPath":
                    cfg.setSslCertChainPath((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "enableGRPCMetrics":
                    cfg.setEnableGRPCMetrics((boolean) value);
                    break;
                case "gRPCTargetHost":
                    cfg.setGRPCTargetHost((String) value);
                    break;
                case "gRPCTargetPort":
                    cfg.setGRPCTargetPort(((Number) value).intValue());
                    break;
                case "bufferSize":
                    cfg.setBufferSize(((Number) value).intValue());
                    break;
                case "enableKafkaTrace":
                    cfg.setEnableKafkaTrace((boolean) value);
                    break;
                case "enableKafkaLog":
                    cfg.setEnableKafkaLog((boolean) value);
                    break;
                case "kafkaBootstrapServers":
                    cfg.setKafkaBootstrapServers((String) value);
                    break;
                case "kafkaProducerConfig":
                    cfg.setKafkaProducerConfig((String) value);
                    break;
                case "kafkaTopicTrace":
                    cfg.setKafkaTopicTrace((String) value);
                    break;
                case "kafkaTopicLog":
                    cfg.setKafkaTopicLog((String) value);
                    break;
                case "exportErrorStatusTraceOnly":
                    cfg.setExportErrorStatusTraceOnly((boolean) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "namespace":
                    cfg.setNamespace((String) value);
                    break;
                case "labelSelector":
                    cfg.setLabelSelector((String) value);
                    break;
                case "period":
                    cfg.setPeriod((Integer) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "host":
                    cfg.setHost((String) value);
                    break;
                case "port":
                    cfg.setPort(((Number) value).intValue());
                    break;
                case "contextPath":
                    cfg.setContextPath((String) value);
                    break;
                case "idleTimeOut":
                    cfg.setIdleTimeOut(((Number) value).intValue());
                    break;
                case "acceptQueueSize":
                    cfg.setAcceptQueueSize(((Number) value).intValue());
                    break;
                case "httpMaxRequestHeaderSize":
                    cfg.setHttpMaxRequestHeaderSize(((Number) value).intValue());
                    break;
                case "gRPCHost":
                    cfg.setGRPCHost((String) value);
                    break;
                case "gRPCPort":
                    cfg.setGRPCPort(((Number) value).intValue());
                    break;
                case "gRPCMaxConcurrentCallsPerConnection":
                    cfg.setGRPCMaxConcurrentCallsPerConnection(((Number) value).intValue());
                    break;
                case "gRPCMaxMessageSize":
                    cfg.setGRPCMaxMessageSize(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolSize":
                    cfg.setGRPCThreadPoolSize(((Number) value).intValue());
                    break;
                case "gRPCThreadPoolQueueSize":
                    cfg.setGRPCThreadPoolQueueSize(((Number) value).intValue());
                    break;
                case "gRPCSslEnabled":
                    cfg.setGRPCSslEnabled((boolean) value);
                    break;
                case "gRPCSslKeyPath":
                    cfg.setGRPCSslKeyPath((String) value);
                    break;
                case "gRPCSslCertChainPath":
                    cfg.setGRPCSslCertChainPath((String) value);
                    break;
                case "gRPCSslTrustedCAsPath":
                    cfg.setGRPCSslTrustedCAsPath((String) value);
                    break;
                case "internalCommunicationTimeout":
                    cfg.setInternalCommunicationTimeout(((Number) value).intValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "keywords4MaskingSecretsOfConfig":
                    cfg.setKeywords4MaskingSecretsOfConfig((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "name":
                    cfg.setName((String) value);
                    break;
                case "aliases":
                    cfg.setAliases((List) value);
                    break;
                case "inputEstimatedCostPerM":
                    cfg.setInputEstimatedCostPerM(((Number) value).doubleValue());
                    break;
                case "outputEstimatedCostPerM":
                    cfg.setOutputEstimatedCostPerM(((Number) value).doubleValue());
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "provider":
                    cfg.setProvider((String) value);
                    break;
                case "baseUrl":
                    cfg.setBaseUrl((String) value);
                    break;
                case "prefixMatch":
                    cfg.setPrefixMatch((List) value);
                    break;
                case "models":
                    cfg.setModels((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "targets":
                    cfg.setTargets((String) value);
                    break;
                case "maxBulkSize":
                    cfg.setMaxBulkSize(((Number) value).intValue());
                    break;
                case "flushInterval":
                    cfg.setFlushInterval(((Number) value).intValue());
                    break;
                case "flushTimeout":
                    cfg.setFlushTimeout(((Number) value).intValue());
                    break;
                case "concurrentWriteThreads":
                    cfg.setConcurrentWriteThreads(((Number) value).intValue());
                    break;
                case "profileTaskQueryMaxSize":
                    cfg.setProfileTaskQueryMaxSize(((Number) value).intValue());
                    break;
                case "user":
                    cfg.setUser((String) value);
                    break;
                case "password":
                    cfg.setPassword((String) value);
                    break;
                case "sslTrustCAPath":
                    cfg.setSslTrustCAPath((String) value);
                    break;
                case "asyncProfilerTaskQueryMaxSize":
                    cfg.setAsyncProfilerTaskQueryMaxSize(((Number) value).intValue());
                    break;
                case "pprofTaskQueryMaxSize":
                    cfg.setPprofTaskQueryMaxSize(((Number) value).intValue());
                    break;
                case "resultWindowMaxSize":
                    cfg.setResultWindowMaxSize(((Number) value).intValue());
                    break;
                case "metadataQueryMaxSize":
                    cfg.setMetadataQueryMaxSize(((Number) value).intValue());
                    break;
                case "segmentQueryMaxSize":
                    cfg.setSegmentQueryMaxSize(((Number) value).intValue());
                    break;
                case "profileDataQueryBatchSize":
                    cfg.setProfileDataQueryBatchSize(((Number) value).intValue());
                    break;
                case "cleanupUnusedTopNRules":
                    cfg.setCleanupUnusedTopNRules((boolean) value);
                    break;
                case "namespace":
                    cfg.setNamespace((String) value);
                    break;
                case "compatibleServerApiVersions":
                    cfg.setCompatibleServerApiVersions((String) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "name":
                    cfg.setName((String) value);
                    break;
                case "lruSizeMinute":
                    cfg.setLruSizeMinute(((Number) value).intValue());
                    break;
                case "lruSizeHourDay":
                    cfg.setLruSizeHourDay(((Number) value).intValue());
                    break;
                case "countersNumber":
                    cfg.setCountersNumber(((Number) value).intValue());
                    break;
                case "groupByTagNames":
                    cfg.setGroupByTagNames((List) value);
                    break;
                case "sort":
                    cfg.setSort((BanyanDBStorageConfig.TopN.Sort) value);
                    break;
                case "excludes":
                    cfg.setExcludes((Set) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "enableWarmStage":
                    cfg.setEnableWarmStage((boolean) value);
                    break;
                case "enableColdStage":
                    cfg.setEnableColdStage((boolean) value);
                    break;
                case "defaultQueryStages":
                    cfg.setDefaultQueryStages((List) value);
                    break;
                case "additionalLifecycleStages":
                    cfg.setAdditionalLifecycleStages((List) value);
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
            final String moduleName, final String providerName) {
        final Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            final String key = (String) propertyNames.nextElement();
            final Object value = src.get(key);
            log.debug("{}.{} config: {} = {}", moduleName, providerName, key, value);
            switch (key) {
                case "name":
                    cfg.setName((BanyanDBStorageConfig.StageName) value);
                    break;
                case "nodeSelector":
                    cfg.setNodeSelector((String) value);
                    break;
                case "shardNum":
                    cfg.setShardNum(((Number) value).intValue());
                    break;
                case "segmentInterval":
                    cfg.setSegmentInterval(((Number) value).intValue());
                    break;
                case "ttl":
                    cfg.setTtl(((Number) value).intValue());
                    break;
                case "replicas":
                    cfg.setReplicas(((Number) value).intValue());
                    break;
                case "close":
                    cfg.setClose((boolean) value);
                    break;
                default:
                    log.warn("{} setting is not supported in {} provider of {} module",
                        key, providerName, moduleName);
                    break;
            }
        }
    }

}
