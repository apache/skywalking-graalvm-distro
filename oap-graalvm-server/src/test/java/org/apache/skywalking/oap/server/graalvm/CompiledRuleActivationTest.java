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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.graalvm.CompiledRuleActivation.Setting;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRuleActivationTest {
    private static final Path DISTRO = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path STAGED = Path.of(System.getProperty("rules.staging.dir",
        DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString()));
    /** The rule directory each setting selects files of. */
    private static final Map<String, String> DIRECTORIES = Map.of(
        "receiver-otel.default.enabledOtelMetricsRules", "otel-rules",
        "agent-analyzer.default.meterAnalyzerActiveFiles", "meter-analyzer-config",
        "log-analyzer.default.malFiles", "log-mal-rules",
        "log-analyzer.default.lalFiles", "lal",
        "envoy-metric.default.enabledEnvoyMetricsRules", "envoy-metrics-rules",
        "receiver-telegraf.default.activeFiles", "telegraf-rules",
        "receiver-zabbix.default.activeFiles", "zabbix-rules");

    @Test
    void theShippedApplicationYmlSelectsNoRuleFiles() throws IOException {
        final Path shipped = DISTRO.resolve("oap-graalvm-server/src/main/resources/application.yml");
        try (Reader reader = Files.newBufferedReader(shipped)) {
            assertEquals(List.of(), CompiledRuleActivation.find(
                CompiledRuleActivation.settingsIn(reader), Map.of(), new Properties()));
        }
    }

    @Test
    void aRuleSelectingSettingIsFoundWhereverItIsSet() {
        // receiver-telegraf gives its provider twice (the configuration loader keeps the second, empty, one), and
        // receiver-zabbix brings its setting in through a merge key (a quoted '<<' is an ordinary key, no merge).
        final List<String[]> yaml = CompiledRuleActivation.settingsIn(new StringReader(String.join("\n",
            "receiver-otel:",
            "  selector: default",
            "  default:",
            "    enabledOtelMetricsRules: vm",
            "log-analyzer:",
            "  selector: \"-\"",
            "  legacy.v1:",
            "    lalFiles: default",
            "receiver-telegraf:",
            "  default:",
            "    activeFiles: vm",
            "  default: {}",
            "receiver-zabbix:",
            "  default:",
            "    <<: {activeFiles: agent}",
            "  other:",
            "    '<<': {activeFiles: agent}",
            "core:",
            "  default:",
            "    restPort: 12800")));
        final Properties properties = new Properties();
        properties.setProperty("trace-analyzer.default.talFiles", "otlp-general");
        properties.setProperty("envoy-metric.other.enabledEnvoyMetricsRules", "envoy");
        properties.setProperty("SW_METER_ANALYZER_ACTIVE_FILES", "java-agent");
        properties.setProperty("core.default.restPort", "12800");
        // Not settings of a provider: no provider name, or more than module.provider.setting.
        properties.setProperty("trace-analyzer..nativeTalFiles", "x");
        properties.setProperty("trace-analyzer.default.nativeTalFiles.extra", "x");
        assertEquals(List.of(
            "application.yml receiver-otel.default.enabledOtelMetricsRules",
            "system property SW_METER_ANALYZER_ACTIVE_FILES",
            "application.yml log-analyzer.legacy.v1.lalFiles",
            "system property trace-analyzer.default.talFiles",
            "system property envoy-metric.other.enabledEnvoyMetricsRules",
            "application.yml receiver-telegraf.default.activeFiles",
            "application.yml receiver-zabbix.default.activeFiles",
            "environment variable SW_RECEIVER_ZABBIX_ACTIVE_FILES"),
            CompiledRuleActivation.find(yaml, Map.of("SW_RECEIVER_ZABBIX_ACTIVE_FILES", "", "SW_CORE_REST_PORT", "1"),
                properties));
    }

    @Test
    void theEnvironmentVariablesAreUpstreams() throws IOException {
        final Map<String, Object> upstream = yaml(
            DISTRO.resolve("skywalking/oap-server/server-starter/src/main/resources/application.yml"));
        final Pattern placeholder = Pattern.compile("^\\$\\{([A-Z0-9_]+):");
        for (final Setting setting : CompiledRuleActivation.SETTINGS) {
            final Object value = provider(upstream, setting).get(setting.field);
            final Matcher m = placeholder.matcher(String.valueOf(value));
            assertTrue(m.find(), setting.field + " has no placeholder upstream: " + value);
            assertEquals(m.group(1), setting.env, setting.module + "." + setting.field);
        }
    }

    @Test
    void everyCompiledRuleFileIsActive() throws IOException {
        final Map<String, String> activation = CompiledRules.activation();
        assertEquals(CompiledRuleActivation.SETTINGS.stream().map(s -> s.module + "." + s.provider + "." + s.field)
                                                    .collect(Collectors.toSet()), activation.keySet());
        for (final Map.Entry<String, String> setting : DIRECTORIES.entrySet()) {
            assertEquals(stems(setting.getValue()), names(activation.get(setting.getKey())), setting.getKey());
        }
        final Set<String> tal = names(activation.get("trace-analyzer.default.talFiles"));
        final Set<String> nativeTal = names(activation.get("trace-analyzer.default.nativeTalFiles"));
        assertTrue(tal.stream().noneMatch(nativeTal::contains), "a TAL file is listed for both inputs");
        tal.addAll(nativeTal);
        assertEquals(stems("tal"), tal);
    }

    @Test
    void theLoadedProvidersGetEveryCompiledRuleFile() {
        final ApplicationConfiguration configuration = new ApplicationConfiguration();
        final Properties given = new Properties();
        given.put("lalFiles", "default");
        given.put("malPath", "telegraf-rules");
        given.put("meterConfigs", List.of());
        configuration.addModule("log-analyzer").addProviderConfiguration("default", given);
        final Properties trace = new Properties();
        trace.put("talPath", "elsewhere");
        configuration.addModule("trace-analyzer").addProviderConfiguration("default", trace);
        configuration.addModule("receiver-otel").addProviderConfiguration("other", new Properties());
        final Map<String, String> activation = CompiledRuleActivation.SETTINGS.stream().collect(Collectors.toMap(
            s -> s.module + "." + s.provider + "." + s.field, s -> s.field + "-files", (a, b) -> a));

        CompiledRuleActivation.apply(configuration, activation);

        final Properties logAnalyzer = configuration.getModuleConfiguration("log-analyzer")
                                                    .getProviderConfiguration("default");
        assertEquals("malFiles-files", logAnalyzer.get("malFiles"));
        assertEquals("lalFiles-files", logAnalyzer.get("lalFiles"), "the compiled files replace any given");
        assertEquals("log-mal-rules", logAnalyzer.get("malPath"), "the rules are loaded from the compiled catalog");
        assertEquals("lal", logAnalyzer.get("lalPath"));
        assertFalse(logAnalyzer.containsKey("meterConfigs"), "the cached rules are loaded from malFiles");
        assertEquals("tal", trace.get("talPath"));
        assertEquals("talFiles-files", trace.get("talFiles"));
        assertTrue(configuration.getModuleConfiguration("receiver-otel").getProviderConfiguration("other").isEmpty());
        assertFalse(configuration.has("receiver-zabbix"));

        activation.remove("receiver-zabbix.default.activeFiles");
        assertThrows(IllegalStateException.class, () -> CompiledRuleActivation.apply(configuration, activation));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provider(final Map<String, Object> yaml, final Setting setting) {
        return (Map<String, Object>) ((Map<String, Object>) yaml.get(setting.module)).get(setting.provider);
    }

    private static Map<String, Object> yaml(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml().load(reader);
        }
    }

    /**
     * Whether the loader of the directory keeps the file: the LAL and TAL loaders skip one without a {@code rules}
     * list, the others an empty document.
     */
    private static boolean loaded(final String directory, final Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            final Object yaml = new Yaml().load(reader);
            if ("lal".equals(directory) || "tal".equals(directory)) {
                return yaml instanceof Map && ((Map<?, ?>) yaml).get("rules") instanceof List;
            }
            return yaml != null;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> names(final String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                     .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The rule files compiled from a directory that its loader keeps, by path from it and without extension. */
    private static Set<String> stems(final String directory) throws IOException {
        final Path dir = STAGED.resolve(directory);
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        final Function<Path, String> stem = p -> {
            final String rel = dir.relativize(p).toString().replace(File.separatorChar, '/');
            return rel.substring(0, rel.lastIndexOf('.'));
        };
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                       .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                       .filter(p -> loaded(directory, p))
                       .map(stem).collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
