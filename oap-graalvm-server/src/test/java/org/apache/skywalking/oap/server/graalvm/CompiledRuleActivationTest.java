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
import java.lang.reflect.Field;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.graalvm.CompiledRuleActivation.Setting;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.SwNativeTALSourceTypeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRuleActivationTest {
    private static final Path DISTRO = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path STAGED = Path.of(System.getProperty("rules.staging.dir",
        DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString()));

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
            "system property SW_METER_ANALYZER_ACTIVE_FILES",
            "application.yml receiver-otel.default.enabledOtelMetricsRules",
            "system property envoy-metric.other.enabledEnvoyMetricsRules",
            "application.yml receiver-telegraf.default.activeFiles",
            "application.yml receiver-zabbix.default.activeFiles",
            "environment variable SW_RECEIVER_ZABBIX_ACTIVE_FILES",
            "application.yml log-analyzer.legacy.v1.lalFiles",
            "system property trace-analyzer.default.talFiles"),
            CompiledRuleActivation.find(yaml, Map.of("SW_RECEIVER_ZABBIX_ACTIVE_FILES", "", "SW_CORE_REST_PORT", "1"),
                properties));
    }

    @Test
    void aSettingThatLocatesRulesIsRefusedWhereverItIsSet() {
        final List<String[]> yaml = CompiledRuleActivation.settingsIn(new StringReader(String.join("\n",
            "log-analyzer:",
            "  default:",
            "    lalPath: custom-lal",
            "  other:",
            "    meterConfigs: []",
            "trace-analyzer:",
            "  default:",
            "    talPath: custom-tal")));
        final Properties properties = new Properties();
        properties.setProperty("log-analyzer.default.malPath", "custom-mal");
        assertEquals(List.of(
            "application.yml log-analyzer.default.lalPath",
            "system property log-analyzer.default.malPath",
            "application.yml log-analyzer.other.meterConfigs",
            "application.yml trace-analyzer.default.talPath"),
            CompiledRuleActivation.find(yaml, Map.of(), properties));
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
        for (final RuleCatalog catalog : RuleCatalog.values()) {
            if (catalog.getSettings().size() == 1) {
                final String setting = catalog.setting().key();
                assertEquals(stems(catalog.getDirectory()), names(activation.get(setting)), setting);
            }
        }
        final Set<String> tal = names(activation.get(RuleCatalog.talFiles().key()));
        final Set<String> nativeTal = names(activation.get(RuleCatalog.nativeTalFiles().key()));
        assertTrue(tal.stream().noneMatch(nativeTal::contains), "a TAL file is listed for both inputs");
        tal.addAll(nativeTal);
        assertEquals(stems(RuleCatalog.TAL.getDirectory()), tal);
    }

    /**
     * Each carried TAL file is in the list upstream reads it from: by its rules' input ({@code sw-native} rules in
     * {@code nativeTalFiles}), and a file with no rule to classify it in the list upstream's application.yml puts it in
     * (a bundled native file overridden without rules stays native), else in {@code talFiles}.
     */
    @Test
    void eachTalFileIsInTheListUpstreamReadsItFrom() throws Exception {
        final Map<String, String> activation = CompiledRules.activation();
        final List<String> upstreamNative = upstreamNativeTalFiles();
        final SwNativeTALSourceTypeProvider nativeInput = new SwNativeTALSourceTypeProvider();
        final Set<String> expectedTal = new TreeSet<>();
        final Set<String> expectedNative = new TreeSet<>();
        final Path dir = STAGED.resolve(RuleCatalog.TAL.getDirectory());
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : files.filter(f -> CarriedRuleFiles.isRuleFileName(f.getFileName().toString()))
                                            .collect(Collectors.toList())) {
                    // Bound as upstream's loader binds the file
                    final TALConfigs configs = CarriedRuleFiles.talConfigs(Files.readString(file));
                    if (!CarriedRuleFiles.isCarriedTalFile(configs)) {
                        continue;
                    }
                    final String name = file.getFileName().toString();
                    final String stem = name.substring(0, name.lastIndexOf('.'));
                    final List<TALConfig> rules = configs == null || configs.getRules() == null
                        ? List.of() : configs.getRules();
                    final boolean isNative = rules.isEmpty() ? upstreamNative.contains(stem)
                        : rules.stream().anyMatch(rule -> rule.inputTypes().contains(nativeInput.alias())
                            || rule.inputTypes().contains(nativeInput.inputType().getName()));
                    (isNative ? expectedNative : expectedTal).add(stem);
                }
            }
        }
        assertEquals(expectedTal, names(activation.get(RuleCatalog.talFiles().key())), "talFiles");
        assertEquals(expectedNative, names(activation.get(RuleCatalog.nativeTalFiles().key())), "nativeTalFiles");
    }

    /**
     * Without the agent analyzer, the trace analyzer loads the {@code talFiles} alone: no native file is read, a bundled
     * native file overridden without rules neither, so none of its layers registers, as upstream.
     */
    @Test
    void withoutTheAgentAnalyzerNoNativeFileIsRead() throws Exception {
        final Map<String, String> activation = CompiledRules.activation();
        final Field content = StaticRuleRegistry.class.getDeclaredField("staticContent");
        content.setAccessible(true);
        ((Map<?, ?>) content.get(StaticRuleRegistry.active())).clear();
        final List<String> talFiles = new ArrayList<>(names(activation.get(RuleCatalog.talFiles().key())));
        TALConfigs.load(RuleCatalog.TAL.getDirectory(), talFiles);
        final Set<String> read = StaticRuleRegistry.active().findByCatalog(RuleCatalog.TAL.getDirectory()).stream()
            .map(StaticRuleRegistry.NamedRule::getName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(talFiles), read);
        assertTrue(read.stream().noneMatch(names(activation.get(RuleCatalog.nativeTalFiles().key()))::contains));
    }

    /**
     * @return the native TAL files upstream's application.yml lists by default
     */
    private static List<String> upstreamNativeTalFiles() throws IOException {
        final Map<String, Object> upstream = yaml(
            DISTRO.resolve("skywalking/oap-server/server-starter/src/main/resources/application.yml"));
        final String value = String.valueOf(((Map<?, ?>) ((Map<?, ?>) upstream.get("trace-analyzer")).get("default"))
            .get("nativeTalFiles"));
        final TraceAnalyzerModuleConfig config = new TraceAnalyzerModuleConfig();
        config.setNativeTalFiles(value.substring(value.indexOf(':') + 1, value.length() - 1));
        return config.nativeTalFiles();
    }

    @Test
    void aTalFileIsCarriedAsTheBuildCarriesIt(@TempDir final Path dir) throws IOException {
        final Path layersOnly = Files.writeString(dir.resolve("layers.yaml"),
            "layerDefinitions:\n  - name: SOME_LAYER\n    ordinal: 1500\n    normal: true\n");
        final Path empty = Files.writeString(dir.resolve("empty.yaml"), "metricPrefix: none\n");
        final Path comments = Files.writeString(dir.resolve("comments.yaml"), "# turned off\n");
        final Path unshipped = Files.writeString(dir.resolve("unshipped.yaml"),
            "requiredModules: [receiver-runtime-rule]\nrules: []\n");
        // A file yielding no rule is read, and so active, as upstream's loader reads and records it
        assertTrue(loaded(RuleCatalog.TAL.getDirectory(), layersOnly));
        assertTrue(loaded(RuleCatalog.TAL.getDirectory(), empty));
        assertTrue(loaded(RuleCatalog.TAL.getDirectory(), comments));
        assertFalse(loaded(RuleCatalog.TAL.getDirectory(), unshipped), "a module the image does not ship");
        assertTrue(loaded(RuleCatalog.LAL.getDirectory(), layersOnly));
        assertTrue(loaded(RuleCatalog.LAL.getDirectory(), comments));
    }

    @Test
    void theLoadedProvidersGetEveryCompiledRuleFile() {
        final ApplicationConfiguration configuration = new ApplicationConfiguration();
        final Properties given = new Properties();
        given.put("lalFiles", "default");
        configuration.addModule("log-analyzer").addProviderConfiguration("default", given);
        final Properties trace = new Properties();
        configuration.addModule("trace-analyzer").addProviderConfiguration("default", trace);
        configuration.addModule("receiver-otel").addProviderConfiguration("other", new Properties());
        final Map<String, String> activation = CompiledRuleActivation.SETTINGS.stream().collect(Collectors.toMap(
            s -> s.module + "." + s.provider + "." + s.field, s -> s.field + "-files", (a, b) -> a));

        CompiledRuleActivation.apply(configuration, activation);

        final Properties logAnalyzer = configuration.getModuleConfiguration("log-analyzer")
                                                    .getProviderConfiguration("default");
        assertEquals("malFiles-files", logAnalyzer.get("malFiles"));
        assertEquals("lalFiles-files", logAnalyzer.get("lalFiles"), "the compiled files replace any given");
        assertEquals("talFiles-files", trace.get("talFiles"));
        assertEquals(Set.of("malFiles", "lalFiles"), logAnalyzer.keySet(), "the rule locations keep their defaults");
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
     * Whether the file is active: every staged file is, one yielding no rule too (its loader reads it), but a TAL file
     * {@link CarriedRuleFiles#isCarriedTalFile} leaves out.
     */
    private static boolean loaded(final String directory, final Path file) {
        if (!RuleCatalog.TAL.getDirectory().equals(directory)) {
            return true;
        }
        try {
            return CarriedRuleFiles.isCarriedTalFile(CarriedRuleFiles.talConfigs(Files.readString(file)));
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
                       .filter(p -> CarriedRuleFiles.isRuleFileName(p.getFileName().toString()))
                       .filter(p -> loaded(directory, p))
                       .map(stem).collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
