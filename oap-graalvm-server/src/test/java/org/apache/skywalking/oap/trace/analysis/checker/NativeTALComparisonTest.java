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

package org.apache.skywalking.oap.trace.analysis.checker;

import com.google.gson.Gson;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.skywalking.oap.analyzer.genai.matcher.GenAIProviderPrefixMatcher;
import org.apache.skywalking.oap.analyzer.genai.module.GenAIAnalyzerModule;
import org.apache.skywalking.oap.analyzer.genai.service.GenAIMeterAnalyzer;
import org.apache.skywalking.oap.analyzer.genai.service.IGenAIMeterAnalyzerService;
import org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationModule;
import org.apache.skywalking.oap.server.ai.evaluation.AIEvaluationSamplingConfig;
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule;
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig;
import org.apache.skywalking.oap.server.analyzer.provider.trace.CacheReadLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.CacheWriteLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.DBLatencyThresholdsAndWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.NativeTraceAnalysisConfig;
import org.apache.skywalking.oap.server.analyzer.provider.trace.NativeTraceAnalysisConfigImpl;
import org.apache.skywalking.oap.server.analyzer.provider.trace.TraceSamplingPolicyWatcher;
import org.apache.skywalking.oap.server.analyzer.provider.trace.UninstrumentedGatewaysConfig;
import org.apache.skywalking.oap.server.configuration.api.ConfigChangeWatcher.ConfigChangeEvent;
import org.apache.skywalking.oap.server.configuration.api.ConfigChangeWatcher.EventType;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.manual.networkalias.NetworkAddressAlias;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.annotation.AnnotationScan;
import org.apache.skywalking.oap.server.core.cache.NetworkAddressAliasCache;
import org.apache.skywalking.oap.server.core.config.ConfigService;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.SearchableTracesTagsWatcher;
import org.apache.skywalking.oap.server.core.config.ServerClusterConfig;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.source.ISource;
import org.apache.skywalking.oap.server.core.source.NetworkAddressAliasSetup;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.graalvm.ConfigFieldValues;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalDSL;
import org.apache.skywalking.oap.trace.analyzer.v2.module.TraceAnalyzerModule;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.spec.RateLimitingSamplers;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.nativetrace.SwNativeRuleRunner;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.AliasRegistrar;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.SpanPairing;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.listener.SpanFilterListener;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.SwNativeTALSourceTypeProvider;
import org.apache.skywalking.oap.server.graalvm.CarriedRuleFiles;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Every native TAL rule file the distro enables ({@code nativeTalFiles}, in its order) over upstream's checker
 * fixtures, the SkyWalking segments built to cover every native rule, run by upstream's {@code SwNativeRuleRunner}
 * twice: with the pre-compiled classes the same-FQCN {@code TalDSL} loads through {@code SpanFilterListener.Factory},
 * the distro's boot path, and with a fresh {@code TALClassGenerator} compile of the submodule's YAML against the same
 * function registry, the JVM OAP's. Every record the rules send, alias rows included, must be identical, in a cold
 * pass and in a warm one whose alias cache holds the cold pass's rows.
 *
 * <p>Nothing here names a shipped rule: an operator's {@code nativeTalFiles} is covered as it is.
 */
class NativeTALComparisonTest {
    private static final Path DISTRO = Path.of(System.getProperty("user.dir")).getParent();
    /** The TAL files the precompiler compiled: upstream's with the rules overlay merged in. */
    private static final Path TAL_DIR = Path.of(System.getProperty("rules.staging.dir",
        DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString())).resolve("tal");
    private static final Gson GSON = new Gson();

    static Stream<Profile> profiles() {
        return Stream.of(
            new Profile("defaults", null, null, null),
            new Profile("gateways-clusters-low-thresholds",
                "gateways:\n  - name: proxy0\n    instances:\n      - host: gateway.local\n        port: 8080\n"
                    + "      - host: " + CheckerFixtures.BARE_GATEWAY + "\n      - host: "
                    + CheckerFixtures.LONG_GATEWAY.substring(0, CheckerFixtures.LONG_GATEWAY.lastIndexOf(':'))
                    + "\n        port: 8080\n",
                "clusters:\n  - name: kafka-prod\n    members:\n      - kafka:9092\n"
                    + "  - name: orders-db\n    members:\n      - mysql:3306\n"
                    + "  - name: cart-cache\n    members:\n      - redis:6379\n",
                "default:0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void precompiledNativeRulesSendWhatAFreshCompileSends(final Profile profile) throws Exception {
        final List<String> precompiled = new Side(profile, false).run();
        final List<String> fresh = new Side(profile, true).run();

        assertTrue(fresh.stream().anyMatch(line -> line.startsWith("source ")),
            "no native rule sent a record over the fixtures, so the comparison would prove nothing");
        assertTrue(fresh.stream().anyMatch(line -> line.startsWith("alias ")),
            "no native rule wrote a network alias over the fixtures, so the warm pass would prove nothing");
        assertEquals(fresh, precompiled);
    }

    static final class Profile {
        private final String name;
        private final String gateways;
        private final String serverClusters;
        private final String thresholds;

        Profile(final String name, final String gateways, final String serverClusters, final String thresholds) {
            this.name = name;
            this.gateways = gateways;
            this.serverClusters = serverClusters;
            this.thresholds = thresholds;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * One OAP's view, with every module the native rules can read loaded and configured from the distro's
     * {@code application.yml} defaults.
     */
    private static final class Side {
        private final List<String> out = new ArrayList<>();
        private final List<NetworkAddressAliasSetup> aliasSetups = new ArrayList<>();
        private final Map<String, NetworkAddressAlias> aliasRows = new HashMap<>();
        private final Map<String, Map<Class<?>, Object>> services = new HashMap<>();
        private final ModuleManager moduleManager = mock(ModuleManager.class);
        private final boolean fresh;

        private Side(final Profile profile, final boolean fresh) throws Exception {
            this.fresh = fresh;
            final ModuleProvider provider = mock(ModuleProvider.class);
            final NamingControl naming = new NamingControl(
                Integer.parseInt(setting("core", "serviceNameMaxLength")),
                Integer.parseInt(setting("core", "instanceNameMaxLength")),
                Integer.parseInt(setting("core", "endpointNameMaxLength")), new EndpointNameGrouping());
            final SourceReceiver sourceReceiver = mock(SourceReceiver.class);
            doAnswer(invocation -> {
                final ISource source = invocation.getArgument(0);
                if (source instanceof NetworkAddressAliasSetup) {
                    aliasSetups.add((NetworkAddressAliasSetup) source);
                    source.prepare();
                    out.add("alias " + GSON.toJson(source));
                } else {
                    out.add("source " + source.getClass().getName() + " " + GSON.toJson(source));
                }
                return null;
            }).when(sourceReceiver).receive(any());
            // The core's cache timer loads the alias rows from storage; here the warm pass's rows are loaded by hand.
            final NetworkAddressAliasCache aliasCache = mock(NetworkAddressAliasCache.class);
            Mockito.when(aliasCache.get(anyString())).thenAnswer(invocation -> aliasRows.get(invocation.getArgument(0)));
            final ServerClusterConfig clusters = new ServerClusterConfig(provider,
                Integer.parseInt(setting("core", "serviceNameMaxLength")));
            if (profile.serverClusters != null) {
                clusters.notify(new ConfigChangeEvent(profile.serverClusters, EventType.MODIFY));
            }
            final ConfigService configService = mock(ConfigService.class);
            Mockito.when(configService.getSearchableTracesTags())
                   .thenReturn(new SearchableTracesTagsWatcher(setting("core", "searchableTracesTags"), provider));

            final AnalyzerModuleConfig analyzer = new AnalyzerModuleConfig();
            analyzer.setTraceSamplingPolicySettingsFile(setting("agent-analyzer", "traceSamplingPolicySettingsFile"));
            analyzer.setSlowDBAccessThreshold(profile.thresholds != null
                ? profile.thresholds : setting("agent-analyzer", "slowDBAccessThreshold"));
            analyzer.setSlowCacheReadThreshold(profile.thresholds != null
                ? profile.thresholds : setting("agent-analyzer", "slowCacheReadThreshold"));
            analyzer.setSlowCacheWriteThreshold(profile.thresholds != null
                ? profile.thresholds : setting("agent-analyzer", "slowCacheWriteThreshold"));
            analyzer.setForceSampleErrorSegment(Boolean.parseBoolean(setting("agent-analyzer", "forceSampleErrorSegment")));
            analyzer.setSegmentStatusAnalysisStrategy(setting("agent-analyzer", "segmentStatusAnalysisStrategy"));
            // No setter upstream: written as the config loader writes it
            ConfigFieldValues.setByField(analyzer, "noUpstreamRealAddressAgents",
                setting("agent-analyzer", "noUpstreamRealAddressAgents"));
            analyzer.setDbLatencyThresholdsAndWatcher(
                new DBLatencyThresholdsAndWatcher(analyzer.getSlowDBAccessThreshold(), provider));
            analyzer.setCacheReadLatencyThresholdsAndWatcher(
                new CacheReadLatencyThresholdsAndWatcher(analyzer.getSlowCacheReadThreshold(), provider));
            analyzer.setCacheWriteLatencyThresholdsAndWatcher(
                new CacheWriteLatencyThresholdsAndWatcher(analyzer.getSlowCacheWriteThreshold(), provider));
            analyzer.setTraceSamplingPolicyWatcher(new TraceSamplingPolicyWatcher(analyzer, provider));
            final UninstrumentedGatewaysConfig gateways = new UninstrumentedGatewaysConfig(provider);
            if (profile.gateways != null) {
                gateways.notify(new ConfigChangeEvent(profile.gateways, EventType.MODIFY));
            }
            analyzer.setUninstrumentedGatewaysConfig(gateways);

            serve(CoreModule.NAME, NamingControl.class, naming);
            serve(CoreModule.NAME, SourceReceiver.class, sourceReceiver);
            serve(CoreModule.NAME, NetworkAddressAliasCache.class, aliasCache);
            serve(CoreModule.NAME, ServerClusterConfig.class, clusters);
            serve(CoreModule.NAME, ConfigService.class, configService);
            serve(CoreModule.NAME, MeterSystem.class, mock(MeterSystem.class));
            serve(AnalyzerModule.NAME, NativeTraceAnalysisConfig.class, new NativeTraceAnalysisConfigImpl(analyzer));
            serve(AnalyzerModule.NAME, UninstrumentedGatewaysConfig.class, gateways);
            final SpanPairing pairing = new SpanPairing();
            final AliasRegistrar registrar = new AliasRegistrar(sourceReceiver, naming);
            registrar.setGateways(gateways::isAddressConfiguredAsGateway);
            pairing.setAliasRegistrar(registrar);
            serve(TraceAnalyzerModule.NAME, SpanPairing.class, pairing);
            serve(TraceAnalyzerModule.NAME, RateLimitingSamplers.class, new RateLimitingSamplers());
            final GenAIMeterAnalyzer genAI = new GenAIMeterAnalyzer(GenAIProviderPrefixMatcher.build());
            genAI.setNamingControl(naming);
            serve(GenAIAnalyzerModule.NAME, IGenAIMeterAnalyzerService.class, genAI);
            // Every trace sampled, so the evaluation rules run their whole path.
            serve(AIEvaluationModule.NAME, AIEvaluationSamplingConfig.class, new AIEvaluationSamplingConfig(true, 1_000_000));
            for (final Map.Entry<String, Map<Class<?>, Object>> module : services.entrySet()) {
                final ModuleServiceHolder holder = mock(ModuleServiceHolder.class);
                Mockito.when(holder.getService(any())).thenAnswer(
                    invocation -> module.getValue().get((Class<?>) invocation.getArgument(0)));
                final ModuleProviderHolder providerHolder = mock(ModuleProviderHolder.class);
                Mockito.when(providerHolder.provider()).thenReturn(holder);
                Mockito.when(moduleManager.has(module.getKey())).thenReturn(true);
                Mockito.when(moduleManager.find(module.getKey())).thenReturn(providerHolder);
            }
        }

        private void serve(final String module, final Class<?> type, final Object service) {
            services.computeIfAbsent(module, k -> new HashMap<>()).put(type, service);
        }

        private List<String> run() throws Exception {
            final TraceAnalyzerModuleConfig config = new TraceAnalyzerModuleConfig();
            config.setTalPath("tal");
            config.setTalFiles("");
            // Every compiled native file is active, in its order: the image fills the setting from the registry.
            config.setNativeTalFiles(CompiledRules.activation().get("trace-analyzer.default.nativeTalFiles"));
            final SpanFilterListener.Factory factory = new SpanFilterListener.Factory(moduleManager, config);
            final List<TalDSL> rules = factory.loadRules(true);
            // A native file without rules (a bundled one overridden so) is read and yields nothing
            final long withRules = CompiledRules.talFiles().stream()
                .filter(file -> config.nativeTalFiles().contains(file.getFileName().substring(0,
                    file.getFileName().lastIndexOf('.'))) && file.getConfigs().getRules() != null)
                .count();
            assertEquals(withRules, TALConfigs.loadInOrder("tal", config.nativeTalFiles(), moduleManager).size(),
                "every listed native file with rules is loaded, its required modules being served");
            if (fresh) {
                swapInFreshCompiles(rules, config, factory);
            }
            final SwNativeRuleRunner runner = new SwNativeRuleRunner(moduleManager, new SwNativeTALSourceTypeProvider());
            runner.loadRules(rules);
            for (final String pass : List.of("cold", "warm")) {
                if ("warm".equals(pass)) {
                    for (final NetworkAddressAliasSetup setup : aliasSetups) {
                        final NetworkAddressAlias row = new NetworkAddressAlias();
                        row.setAddress(setup.getAddress());
                        row.setRepresentServiceId(setup.getRepresentServiceId());
                        row.setRepresentServiceInstanceId(setup.getRepresentServiceInstanceId());
                        aliasRows.put(setup.getAddress(), row);
                    }
                }
                out.add("=== " + pass + " ===");
                for (final CheckerFixtures.CheckerFixture fixture : CheckerFixtures.all()) {
                    out.add("fixture " + fixture.getName());
                    try {
                        runner.send(fixture.getSegment());
                    } catch (final RuntimeException e) {
                        // A malformed segment fails the rule as it fails on the JVM OAP; the receiver logs it.
                        out.add("failure " + e);
                    }
                }
            }
            return out;
        }

        /**
         * The JVM OAP's expressions in the factory's rules: each compiled from the submodule's YAML entry, as
         * {@code TalDSL.of} upstream compiles it, against the registry the factory bound. The hint differs from the
         * rule name so a fresh class never shadows a pre-compiled one.
         */
        private void swapInFreshCompiles(final List<TalDSL> rules, final TraceAnalyzerModuleConfig config,
                                         final SpanFilterListener.Factory factory) throws Exception {
            declareScopes();
            final Map<String, TALConfig> byName = new HashMap<>();
            final Map<String, TALConfig> entryByName = new HashMap<>();
            for (final TALConfigs file : TALConfigs.loadInOrder("tal", config.nativeTalFiles(), moduleManager)) {
                for (final TALConfig rule : file.getRules()) {
                    byName.put(rule.getName(), rule);
                    entryByName.put(rule.getName(), fileRule(rule.getSourceName(), rule.getName()));
                }
            }
            final Field expression = TalDSL.class.getDeclaredField("expression");
            expression.setAccessible(true);
            for (final TalDSL dsl : rules) {
                final TALConfig rule = byName.get(dsl.getRuleName());
                final String text = entryByName.get(dsl.getRuleName()).getDsl();
                final TALClassGenerator generator = new TALClassGenerator();
                generator.setInputType(dsl.getInputType());
                generator.setInputAlias(new SwNativeTALSourceTypeProvider().alias());
                generator.setFunctionRegistry(factory.getFunctionRegistry());
                generator.setClassNameHint("fresh_" + rule.getName());
                generator.setSourceRef(DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo()));
                generator.setMetricPrefix(rule.getMetricPrefix());
                generator.setContent(text);
                expression.set(dsl, generator.compile(text));
            }
        }
    }

    /**
     * @return the entry of the staged file, bound as upstream's loader binds it; native rule names are unique, a rule
     *     without one included
     */
    private static TALConfig fileRule(final String file, final String name) throws Exception {
        final TALConfigs configs = CarriedRuleFiles.talConfigs(Files.readString(TAL_DIR.resolve(file)));
        for (final TALConfig entry : configs.getRules()) {
            if (Objects.equals(name, entry.getName())) {
                return entry;
            }
        }
        throw new IllegalStateException("no rule " + name + " in " + file);
    }

    /**
     * The default the distro's {@code application.yml} gives a module's setting: {@code ${ENV:default}}.
     */
    @SuppressWarnings("unchecked")
    private static String setting(final String module, final String key) throws Exception {
        try (InputStream in = Files.newInputStream(
            DISTRO.resolve("oap-graalvm-server/src/main/resources/application.yml"))) {
            final Map<String, Object> yaml = new Yaml().load(in);
            final Map<String, Object> section = (Map<String, Object>) yaml.get(module);
            final Map<String, Object> provider = (Map<String, Object>) section.get("default");
            final String value = String.valueOf(provider.get(key));
            return value.startsWith("${") ? value.substring(value.indexOf(':') + 1, value.length() - 1) : value;
        }
    }

    private static synchronized void declareScopes() throws Exception {
        if (!DefaultScopeDefine.names().isEmpty()) {
            return;
        }
        final AnnotationScan scan = new AnnotationScan();
        scan.registerListener(new DefaultScopeDefine.Listener());
        scan.scan();
    }
}
