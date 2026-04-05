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

package org.apache.skywalking.oap.server.buildtools.common;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for accepted module + provider pairs in this distro.
 * Derived from GraalVMOAPServerStartUp.registerAllModules().
 *
 * <p>When a new module/provider is added to the distro, add its (moduleName, providerName)
 * pair here. Build-time tools (config-generator, precompiler) use this list to discover
 * provider config classes via SPI instead of hardcoding FQCNs.
 */
public final class AcceptedModules {

    public record ModuleProviderPair(String moduleName, String providerName) {}

    /**
     * All accepted (moduleName, providerName) pairs.
     * Modules with multiple accepted providers (e.g., cluster) have multiple entries.
     */
    public static final List<ModuleProviderPair> ACCEPTED = List.of(
        // Core
        new ModuleProviderPair("core", "default"),
        // Cluster
        new ModuleProviderPair("cluster", "standalone"),
        new ModuleProviderPair("cluster", "kubernetes"),
        // Storage
        new ModuleProviderPair("storage", "banyandb"),
        // Configuration
        new ModuleProviderPair("configuration", "none"),
        new ModuleProviderPair("configuration", "k8s-configmap"),
        // Telemetry
        new ModuleProviderPair("telemetry", "prometheus"),
        // Analyzers
        new ModuleProviderPair("agent-analyzer", "default"),
        new ModuleProviderPair("log-analyzer", "default"),
        new ModuleProviderPair("event-analyzer", "default"),
        new ModuleProviderPair("gen-ai-analyzer", "default"),
        // Receivers
        new ModuleProviderPair("receiver-sharing-server", "default"),
        new ModuleProviderPair("receiver-register", "default"),
        new ModuleProviderPair("receiver-trace", "default"),
        new ModuleProviderPair("receiver-jvm", "default"),
        new ModuleProviderPair("receiver-clr", "default"),
        new ModuleProviderPair("receiver-profile", "default"),
        new ModuleProviderPair("receiver-async-profiler", "default"),
        new ModuleProviderPair("receiver-pprof", "default"),
        new ModuleProviderPair("receiver-zabbix", "default"),
        new ModuleProviderPair("service-mesh", "default"),
        new ModuleProviderPair("envoy-metric", "default"),
        new ModuleProviderPair("receiver-meter", "default"),
        new ModuleProviderPair("receiver-otel", "default"),
        new ModuleProviderPair("receiver-zipkin", "default"),
        new ModuleProviderPair("receiver-browser", "default"),
        new ModuleProviderPair("receiver-log", "default"),
        new ModuleProviderPair("receiver-event", "default"),
        new ModuleProviderPair("receiver-ebpf", "default"),
        new ModuleProviderPair("receiver-telegraf", "default"),
        new ModuleProviderPair("aws-firehose", "default"),
        new ModuleProviderPair("configuration-discovery", "default"),
        // Fetchers
        new ModuleProviderPair("kafka-fetcher", "default"),
        new ModuleProviderPair("cilium-fetcher", "default"),
        // Query
        new ModuleProviderPair("query", "graphql"),
        new ModuleProviderPair("query-zipkin", "default"),
        new ModuleProviderPair("promql", "default"),
        new ModuleProviderPair("logql", "default"),
        new ModuleProviderPair("traceQL", "default"),
        new ModuleProviderPair("status-query", "default"),
        // Alarm
        new ModuleProviderPair("alarm", "default"),
        // Exporter
        new ModuleProviderPair("exporter", "default"),
        // Health Checker
        new ModuleProviderPair("health-checker", "default"),
        // AI Pipeline
        new ModuleProviderPair("ai-pipeline", "default")
    );

    /**
     * Returns accepted provider names grouped by module name.
     */
    public static Map<String, Set<String>> byModule() {
        Map<String, Set<String>> map = new HashMap<>();
        for (ModuleProviderPair pair : ACCEPTED) {
            map.computeIfAbsent(pair.moduleName(), k -> new LinkedHashSet<>()).add(pair.providerName());
        }
        return map;
    }

    /**
     * Check if a (moduleName, providerName) pair is accepted.
     */
    public static boolean isAccepted(String moduleName, String providerName) {
        return ACCEPTED.contains(new ModuleProviderPair(moduleName, providerName));
    }

    private AcceptedModules() {
    }
}
