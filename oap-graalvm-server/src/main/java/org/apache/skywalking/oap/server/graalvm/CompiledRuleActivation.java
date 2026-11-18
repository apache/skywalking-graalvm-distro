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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Every rule file compiled into this image is active. After the configuration is loaded, the settings that select rule
 * files, the original way to pick rules at boot, are set to all of them, and the rule directories to the compiled
 * catalogs, whatever was given: no rule is selected, added or dropped at runtime. A rule-selecting setting given anyway
 * (in {@code application.yml} under any provider, by environment variable or by system property) fails the boot rather
 * than being silently ignored.
 */
@Slf4j
final class CompiledRuleActivation {
    static final List<Setting> SETTINGS = List.of(
        new Setting("receiver-otel", "default", "enabledOtelMetricsRules", "SW_OTEL_RECEIVER_ENABLED_OTEL_METRICS_RULES"),
        new Setting("agent-analyzer", "default", "meterAnalyzerActiveFiles", "SW_METER_ANALYZER_ACTIVE_FILES"),
        new Setting("log-analyzer", "default", "malFiles", "SW_LOG_MAL_FILES"),
        new Setting("log-analyzer", "default", "lalFiles", "SW_LOG_LAL_FILES"),
        new Setting("trace-analyzer", "default", "talFiles", "SW_TRACE_TAL_FILES"),
        new Setting("trace-analyzer", "default", "nativeTalFiles", "SW_TRACE_NATIVE_TAL_FILES"),
        new Setting("envoy-metric", "default", "enabledEnvoyMetricsRules", "SW_ENVOY_METRIC_ENABLED_RULES"),
        new Setting("receiver-telegraf", "default", "activeFiles", "SW_RECEIVER_TELEGRAF_ACTIVE_FILES"),
        new Setting("receiver-zabbix", "default", "activeFiles", "SW_RECEIVER_ZABBIX_ACTIVE_FILES"));

    private CompiledRuleActivation() {
    }

    /**
     * Before {@code ApplicationConfigLoader} drops the providers not selected.
     */
    static void verify() throws IOException {
        final List<String[]> settings;
        try (Reader reader = ResourceUtils.read("application.yml")) {
            settings = settingsIn(reader);
        } catch (FileNotFoundException e) {
            return;
        }
        final List<String> found = find(settings, System.getenv(), System.getProperties());
        if (!found.isEmpty()) {
            throw new IllegalStateException(
                "Rules are selected at compile time: every rule compiled into this image is active, and these "
                    + "settings select rule files: " + found + ". Remove them; to change the rules, rebuild the image "
                    + "with a rules overlay (add / override / exclude in its rules-overlay.yaml).");
        }
    }

    /**
     * @return every {@code module.provider.setting} the document gives, read from its node tree: a mapping given twice
     * under one key is kept in full, though the configuration loader keeps only the last
     */
    static List<String[]> settingsIn(final Reader reader) {
        final List<String[]> settings = new ArrayList<>();
        for (final NodeTuple module : tuples(new Yaml().compose(reader))) {
            for (final NodeTuple provider : tuples(module.getValueNode())) {
                for (final NodeTuple setting : tuples(provider.getValueNode())) {
                    settings.add(new String[] {
                        scalar(module.getKeyNode()), scalar(provider.getKeyNode()), scalar(setting.getKeyNode())});
                }
            }
        }
        return settings;
    }

    /**
     * @param yamlSettings every {@code {module, provider, setting}} the application.yml gives
     */
    static List<String> find(final List<String[]> yamlSettings, final Map<String, String> env,
                             final Properties systemProperties) {
        final List<String> found = new ArrayList<>();
        for (final Setting setting : SETTINGS) {
            for (final String[] given : yamlSettings) {
                // By its parts: a provider name may hold a dot.
                if (setting.module.equals(given[0]) && setting.field.equals(given[2])) {
                    found.add("application.yml " + String.join(".", given));
                }
            }
            if (env.containsKey(setting.env)) {
                found.add("environment variable " + setting.env);
            }
            // Upstream resolves a ${SW_...} placeholder from the system properties first.
            if (systemProperties.containsKey(setting.env)) {
                found.add("system property " + setting.env);
            }
            // module.provider.setting overrides the provider's setting, whichever provider it names.
            for (final String property : new TreeSet<>(systemProperties.stringPropertyNames())) {
                if (setting.matches(property)) {
                    found.add("system property " + property);
                }
            }
        }
        return found;
    }

    /**
     * Sets every rule-selecting setting of the loaded providers to all the rule files compiled for it, and what else
     * the analyzers load rules by to what the image compiled.
     */
    static void apply(final ApplicationConfiguration configuration, final Map<String, String> activation) {
        for (final Setting setting : SETTINGS) {
            final String key = setting.module + "." + setting.provider + "." + setting.field;
            final String files = activation.get(key);
            if (files == null) {
                throw new IllegalStateException("The rule registry has no activation for " + key);
            }
            final Properties provider = provider(configuration, setting.module);
            if (provider != null) {
                provider.put(setting.field, files);
            }
        }
        // The catalogs the rules were compiled from: the log analyzer would load nothing from another one.
        final Properties logAnalyzer = provider(configuration, "log-analyzer");
        if (logAnalyzer != null) {
            logAnalyzer.put("lalPath", "lal");
            logAnalyzer.put("malPath", "log-mal-rules");
            // Its cache of the rules it loads from malFiles: a value given for it would replace them.
            logAnalyzer.remove("meterConfigs");
        }
        final Properties traceAnalyzer = provider(configuration, "trace-analyzer");
        if (traceAnalyzer != null) {
            traceAnalyzer.put("talPath", "tal");
        }
        log.info("Every compiled rule file is active: {}", activation);
    }

    static void apply(final ApplicationConfiguration configuration) {
        apply(configuration, CompiledRules.activation());
    }

    private static Properties provider(final ApplicationConfiguration configuration, final String module) {
        return configuration.has(module) && configuration.getModuleConfiguration(module).has("default")
            ? configuration.getModuleConfiguration(module).getProviderConfiguration("default") : null;
    }

    /** A mapping's entries, with those a {@code <<} merge key brings in. */
    private static List<NodeTuple> tuples(final Node node) {
        final List<NodeTuple> tuples = new ArrayList<>();
        if (!(node instanceof MappingNode)) {
            return tuples;
        }
        for (final NodeTuple tuple : ((MappingNode) node).getValue()) {
            // A merge key is the plain <<, which YAML tags as a merge; a quoted '<<' is an ordinary key.
            if (!Tag.MERGE.equals(tuple.getKeyNode().getTag())) {
                tuples.add(tuple);
            } else if (tuple.getValueNode() instanceof SequenceNode) {
                ((SequenceNode) tuple.getValueNode()).getValue().forEach(merged -> tuples.addAll(tuples(merged)));
            } else {
                tuples.addAll(tuples(tuple.getValueNode()));
            }
        }
        return tuples;
    }

    private static String scalar(final Node node) {
        return node instanceof ScalarNode ? ((ScalarNode) node).getValue() : "";
    }

    @RequiredArgsConstructor
    static final class Setting {
        final String module;
        final String provider;
        final String field;
        final String env;

        /** Whether {@code module.provider.setting} names this setting, under any provider. */
        boolean matches(final String key) {
            final String[] parts = key.split("\\.", -1);
            return parts.length == 3 && parts[0].equals(module) && !parts[1].isEmpty() && parts[2].equals(field);
        }
    }
}
