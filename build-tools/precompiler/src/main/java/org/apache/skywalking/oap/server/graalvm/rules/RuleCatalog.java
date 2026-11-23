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

package org.apache.skywalking.oap.server.graalvm.rules;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The rule files upstream's loaders read from the server-starter resources, which the image compiles: each rule
 * directory, with the engine that compiles it, whether its loader reads the directory and below or the directory itself
 * only, and the {@code application.yml} settings that select its files (every compiled file is active, so the registry
 * fills them and the boot refuses them); and the rule files outside a directory. In the order the build compiles them.
 */
@Getter
public enum RuleCatalog {
    OAL("oal", Engine.OAL, false),
    METER_ANALYZER_CONFIG("meter-analyzer-config", Engine.MAL, true,
        new Setting("agent-analyzer", "default", "meterAnalyzerActiveFiles", "SW_METER_ANALYZER_ACTIVE_FILES")),
    OTEL_RULES("otel-rules", Engine.MAL, true,
        new Setting("receiver-otel", "default", "enabledOtelMetricsRules", "SW_OTEL_RECEIVER_ENABLED_OTEL_METRICS_RULES")),
    LOG_MAL_RULES("log-mal-rules", Engine.MAL, true,
        new Setting("log-analyzer", "default", "malFiles", "SW_LOG_MAL_FILES")),
    ENVOY_METRICS_RULES("envoy-metrics-rules", Engine.MAL, true,
        new Setting("envoy-metric", "default", "enabledEnvoyMetricsRules", "SW_ENVOY_METRIC_ENABLED_RULES")),
    TELEGRAF_RULES("telegraf-rules", Engine.MAL, true,
        new Setting("receiver-telegraf", "default", "activeFiles", "SW_RECEIVER_TELEGRAF_ACTIVE_FILES")),
    // Compiled by MAL, loaded by the Zabbix receiver's own ZabbixConfigs
    ZABBIX_RULES("zabbix-rules", Engine.MAL, false,
        new Setting("receiver-zabbix", "default", "activeFiles", "SW_RECEIVER_ZABBIX_ACTIVE_FILES")),
    LAL("lal", Engine.LAL, false,
        new Setting("log-analyzer", "default", "lalFiles", "SW_LOG_LAL_FILES")),
    TAL("tal", Engine.TAL, false,
        new Setting("trace-analyzer", "default", "talFiles", "SW_TRACE_TAL_FILES"),
        new Setting("trace-analyzer", "default", "nativeTalFiles", "SW_TRACE_NATIVE_TAL_FILES"));

    public static final String HIERARCHY_DEFINITION = "hierarchy-definition.yml";
    public static final String LAYER_EXTENSIONS = "layer-extensions.yml";
    public static final List<String> RULE_FILES = List.of(HIERARCHY_DEFINITION, LAYER_EXTENSIONS);

    private final String directory;
    private final Engine engine;
    private final boolean recursive;
    private final List<Setting> settings;

    RuleCatalog(final String directory, final Engine engine, final boolean recursive, final Setting... settings) {
        this.directory = directory;
        this.engine = engine;
        this.recursive = recursive;
        this.settings = List.of(settings);
    }

    public static List<String> directories() {
        return Arrays.stream(values()).map(RuleCatalog::getDirectory).collect(Collectors.toList());
    }

    /**
     * @return the directories upstream's MAL {@code Rules} loader reads
     */
    public static List<RuleCatalog> malRules() {
        return Arrays.stream(values()).filter(c -> c.engine == Engine.MAL && c != ZABBIX_RULES)
                     .collect(Collectors.toList());
    }

    /**
     * @return the directories whose upstream loader ({@code Rules}, {@code LALConfigs}, {@code TALConfigs}) passes the
     *     files it reads through {@code RuleSetMerger}, which records them for the runtime-rule catalog
     */
    public static List<RuleCatalog> mergedByLoader() {
        return Arrays.stream(values()).filter(c -> c != OAL && c != ZABBIX_RULES).collect(Collectors.toList());
    }

    /**
     * @return every setting that selects rule files
     */
    public static List<Setting> allSettings() {
        return Arrays.stream(values()).flatMap(c -> c.settings.stream()).collect(Collectors.toList());
    }

    /**
     * @return whether upstream's loaders find the directory on the class path, as the server-starter resources hold
     *     it: a rule directory (one a rules overlay emptied too), or the class path root ({@code ""})
     */
    public static boolean exists(final String directory) {
        return directory != null && (directory.isEmpty() || directories().contains(directory));
    }

    public static Optional<RuleCatalog> ofDirectory(final String directory) {
        return Arrays.stream(values()).filter(c -> c.directory.equals(directory)).findFirst();
    }

    /**
     * @param path a file's path in the directory, {@code /}-separated
     * @return whether it is a rule file of the directory, as the rules stager stages them: a YAML file ({@code .oal} for
     *     OAL), not hidden, in the directory itself or, where the loader walks it, below
     */
    public boolean isRuleFile(final String path) {
        final String[] parts = path.split("/");
        if (!recursive && parts.length > 1 || Arrays.stream(parts).anyMatch(part -> part.startsWith("."))) {
            return false;
        }
        return engine == Engine.OAL ? path.endsWith(".oal") : path.endsWith(".yaml") || path.endsWith(".yml");
    }

    /**
     * The only setting of the catalog; TAL's two are {@link #talFiles()} and {@link #nativeTalFiles()}.
     */
    public Setting setting() {
        if (settings.size() != 1) {
            throw new IllegalStateException(directory + " has " + settings.size() + " settings");
        }
        return settings.get(0);
    }

    /**
     * @return the TAL files of the OTLP span inputs
     */
    public static Setting talFiles() {
        return TAL.settings.get(0);
    }

    /**
     * @return the TAL files of the {@code sw-native} input, which run in the listed order
     */
    public static Setting nativeTalFiles() {
        return TAL.settings.get(1);
    }

    public enum Engine {
        OAL, MAL, LAL, TAL
    }

    /**
     * A setting of {@code application.yml}, {@code module.provider.field}, and the environment variable upstream's
     * {@code application.yml} gives it.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Setting {
        private final String module;
        private final String provider;
        private final String field;
        private final String env;

        public String key() {
            return module + "." + provider + "." + field;
        }
    }
}
