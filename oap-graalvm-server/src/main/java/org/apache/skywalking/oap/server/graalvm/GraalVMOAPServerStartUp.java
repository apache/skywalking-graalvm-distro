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

import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.CoreModuleProvider;
import org.apache.skywalking.oap.server.core.RunningMode;
import org.apache.skywalking.oap.server.core.alarm.AlarmModule;
import org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleProvider;
import org.apache.skywalking.oap.server.core.cluster.ClusterModule;
import org.apache.skywalking.oap.server.core.exporter.ExporterModule;
import org.apache.skywalking.oap.server.core.query.QueryModule;
import org.apache.skywalking.oap.server.core.status.ServerStatusService;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.server.library.module.FixedModuleManager;
import org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable;
import org.apache.skywalking.oap.server.starter.config.ApplicationConfigLoader;

import static org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable.Row;

// Storage
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageProvider;
// Cluster
import org.apache.skywalking.oap.server.cluster.plugin.standalone.ClusterModuleStandaloneProvider;
import org.apache.skywalking.oap.server.cluster.plugin.kubernetes.ClusterModuleKubernetesProvider;
// Configuration
import org.apache.skywalking.oap.server.configuration.api.ConfigurationModule;
import org.apache.skywalking.oap.server.configuration.configmap.ConfigmapConfigurationProvider;
// Telemetry
import org.apache.skywalking.oap.server.telemetry.TelemetryModule;
import org.apache.skywalking.oap.server.telemetry.prometheus.PrometheusTelemetryProvider;
// Analyzers
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule;
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleProvider;
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule;
import org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleProvider;
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerModule;
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerModuleProvider;
// Receivers
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule;
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModuleProvider;
import org.apache.skywalking.oap.server.receiver.register.module.RegisterModule;
import org.apache.skywalking.oap.server.receiver.register.provider.RegisterModuleProvider;
import org.apache.skywalking.oap.server.receiver.trace.module.TraceModule;
import org.apache.skywalking.oap.server.receiver.trace.provider.TraceModuleProvider;
import org.apache.skywalking.oap.server.receiver.jvm.module.JVMModule;
import org.apache.skywalking.oap.server.receiver.jvm.provider.JVMModuleProvider;
import org.apache.skywalking.oap.server.receiver.clr.module.CLRModule;
import org.apache.skywalking.oap.server.receiver.clr.provider.CLRModuleProvider;
import org.apache.skywalking.oap.server.receiver.profile.module.ProfileModule;
import org.apache.skywalking.oap.server.receiver.profile.provider.ProfileModuleProvider;
import org.apache.skywalking.oap.server.receiver.asyncprofiler.module.AsyncProfilerModule;
import org.apache.skywalking.oap.server.receiver.asyncprofiler.provider.AsyncProfilerModuleProvider;
import org.apache.skywalking.oap.server.receiver.pprof.module.PprofModule;
import org.apache.skywalking.oap.server.receiver.pprof.provider.PprofModuleProvider;
import org.apache.skywalking.oap.server.receiver.zabbix.module.ZabbixReceiverModule;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixReceiverProvider;
import org.apache.skywalking.aop.server.receiver.mesh.MeshReceiverModule;
import org.apache.skywalking.aop.server.receiver.mesh.MeshReceiverProvider;
import org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverModule;
import org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverProvider;
import org.apache.skywalking.oap.server.receiver.meter.module.MeterReceiverModule;
import org.apache.skywalking.oap.server.receiver.meter.provider.MeterReceiverProvider;
import org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverModule;
import org.apache.skywalking.oap.server.receiver.otel.OtelMetricReceiverProvider;
import org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverModule;
import org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverProvider;
import org.apache.skywalking.oap.server.receiver.browser.module.BrowserModule;
import org.apache.skywalking.oap.server.receiver.browser.provider.BrowserModuleProvider;
import org.apache.skywalking.oap.server.receiver.log.module.LogModule;
import org.apache.skywalking.oap.server.receiver.log.provider.LogModuleProvider;
import org.apache.skywalking.oap.server.receiver.event.EventModule;
import org.apache.skywalking.oap.server.receiver.event.EventModuleProvider;
import org.apache.skywalking.oap.server.receiver.ebpf.module.EBPFReceiverModule;
import org.apache.skywalking.oap.server.receiver.ebpf.provider.EBPFReceiverProvider;
import org.apache.skywalking.oap.server.receiver.telegraf.module.TelegrafReceiverModule;
import org.apache.skywalking.oap.server.receiver.telegraf.provider.TelegrafReceiverProvider;
import org.apache.skywalking.oap.server.receiver.aws.firehose.AWSFirehoseReceiverModule;
import org.apache.skywalking.oap.server.receiver.aws.firehose.AWSFirehoseReceiverModuleProvider;
import org.apache.skywalking.oap.server.receiver.configuration.discovery.ConfigurationDiscoveryModule;
import org.apache.skywalking.oap.server.receiver.configuration.discovery.ConfigurationDiscoveryProvider;
// Fetchers
import org.apache.skywalking.oap.server.analyzer.agent.kafka.module.KafkaFetcherModule;
import org.apache.skywalking.oap.server.analyzer.agent.kafka.provider.KafkaFetcherProvider;
import org.apache.skywalking.oap.server.fetcher.cilium.CiliumFetcherModule;
import org.apache.skywalking.oap.server.fetcher.cilium.CiliumFetcherProvider;
// Query
import org.apache.skywalking.oap.query.graphql.GraphQLQueryProvider;
import org.apache.skywalking.oap.query.zipkin.ZipkinQueryModule;
import org.apache.skywalking.oap.query.zipkin.ZipkinQueryProvider;
import org.apache.skywalking.oap.query.promql.PromQLModule;
import org.apache.skywalking.oap.query.promql.PromQLProvider;
import org.apache.skywalking.oap.query.logql.LogQLModule;
import org.apache.skywalking.oap.query.logql.LogQLProvider;
import org.apache.skywalking.oap.query.debug.StatusQueryModule;
import org.apache.skywalking.oap.query.debug.StatusQueryProvider;
// Exporter
import org.apache.skywalking.oap.server.exporter.provider.ExporterProvider;
// Health Checker
import org.apache.skywalking.oap.server.health.checker.module.HealthCheckerModule;
import org.apache.skywalking.oap.server.health.checker.provider.HealthCheckerProvider;
// AI Pipeline
import org.apache.skywalking.oap.server.ai.pipeline.AIPipelineModule;
import org.apache.skywalking.oap.server.ai.pipeline.AIPipelineProvider;

/**
 * GraalVM-optimized OAP server startup with fixed module wiring.
 * No ServiceLoader/SPI discovery — all modules and providers are directly constructed.
 */
@Slf4j
public class GraalVMOAPServerStartUp {

    public static void main(String[] args) {
        start();
    }

    public static void start() {
        FixedModuleManager manager = new FixedModuleManager("SkyWalking GraalVM OAP");
        final TerminalFriendlyTable bootingParameters = manager.getBootingParameters();

        String mode = System.getProperty("mode");
        RunningMode.setMode(mode);

        ApplicationConfigLoader configLoader = new ApplicationConfigLoader(bootingParameters);

        bootingParameters.addRow(new Row("Running Mode", mode));
        bootingParameters.addRow(new Row("Distro", "GraalVM"));

        try {
            ApplicationConfiguration configuration = configLoader.load();

            // Register all modules with their fixed providers
            registerAllModules(manager, configuration);

            // Initialize (prepare -> start -> notifyAfterCompleted)
            manager.initFixed(configuration);

            manager.find(CoreModule.NAME)
                   .provider()
                   .getService(ServerStatusService.class)
                   .bootedNow(configLoader.getResolvedConfigurations(), System.currentTimeMillis());

            if (RunningMode.isInitMode()) {
                log.info("OAP starts up in init mode successfully, exit now...");
                System.exit(0);
            }
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            System.exit(1);
        } finally {
            log.info(bootingParameters.toString());
        }
    }

    private static void registerAllModules(FixedModuleManager manager, ApplicationConfiguration configuration) {
        // Core
        manager.register(new CoreModule(), new CoreModuleProvider());
        // Cluster: standalone or kubernetes, selected by config
        ApplicationConfiguration.ModuleConfiguration clusterConfig = configuration.getModuleConfiguration("cluster");
        if (clusterConfig != null && clusterConfig.has("kubernetes")) {
            manager.register(new ClusterModule(), new ClusterModuleKubernetesProvider());
        } else {
            manager.register(new ClusterModule(), new ClusterModuleStandaloneProvider());
        }
        // Storage: BanyanDB
        manager.register(new StorageModule(), new BanyanDBStorageProvider());
        // Configuration: Kubernetes ConfigMap
        manager.register(new ConfigurationModule(), new ConfigmapConfigurationProvider());
        // Telemetry: Prometheus
        manager.register(new TelemetryModule(), new PrometheusTelemetryProvider());

        // Analyzers
        manager.register(new AnalyzerModule(), new AnalyzerModuleProvider());
        manager.register(new LogAnalyzerModule(), new LogAnalyzerModuleProvider());
        manager.register(new EventAnalyzerModule(), new EventAnalyzerModuleProvider());

        // Receivers
        manager.register(new SharingServerModule(), new SharingServerModuleProvider());
        manager.register(new RegisterModule(), new RegisterModuleProvider());
        manager.register(new TraceModule(), new TraceModuleProvider());
        manager.register(new JVMModule(), new JVMModuleProvider());
        manager.register(new CLRModule(), new CLRModuleProvider());
        manager.register(new ProfileModule(), new ProfileModuleProvider());
        manager.register(new AsyncProfilerModule(), new AsyncProfilerModuleProvider());
        manager.register(new PprofModule(), new PprofModuleProvider());
        manager.register(new ZabbixReceiverModule(), new ZabbixReceiverProvider());
        manager.register(new MeshReceiverModule(), new MeshReceiverProvider());
        manager.register(new EnvoyMetricReceiverModule(), new EnvoyMetricReceiverProvider());
        manager.register(new MeterReceiverModule(), new MeterReceiverProvider());
        manager.register(new OtelMetricReceiverModule(), new OtelMetricReceiverProvider());
        manager.register(new ZipkinReceiverModule(), new ZipkinReceiverProvider());
        manager.register(new BrowserModule(), new BrowserModuleProvider());
        manager.register(new LogModule(), new LogModuleProvider());
        manager.register(new EventModule(), new EventModuleProvider());
        manager.register(new EBPFReceiverModule(), new EBPFReceiverProvider());
        manager.register(new TelegrafReceiverModule(), new TelegrafReceiverProvider());
        manager.register(new AWSFirehoseReceiverModule(), new AWSFirehoseReceiverModuleProvider());
        manager.register(new ConfigurationDiscoveryModule(), new ConfigurationDiscoveryProvider());

        // Fetchers
        manager.register(new KafkaFetcherModule(), new KafkaFetcherProvider());
        manager.register(new CiliumFetcherModule(), new CiliumFetcherProvider());

        // Query
        manager.register(new QueryModule(), new GraphQLQueryProvider());
        manager.register(new ZipkinQueryModule(), new ZipkinQueryProvider());
        manager.register(new PromQLModule(), new PromQLProvider());
        manager.register(new LogQLModule(), new LogQLProvider());
        manager.register(new StatusQueryModule(), new StatusQueryProvider());

        // Alarm
        manager.register(new AlarmModule(), new AlarmModuleProvider());

        // Exporter
        manager.register(new ExporterModule(), new ExporterProvider());

        // Health Checker
        manager.register(new HealthCheckerModule(), new HealthCheckerProvider());

        // AI Pipeline
        manager.register(new AIPipelineModule(), new AIPipelineProvider());
    }
}
