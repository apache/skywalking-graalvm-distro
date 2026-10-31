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

package org.apache.skywalking.oap.server.graalvm.tal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.config.IComponentLibraryCatalogService;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.DslYamlLineIndex;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.core.source.TALOutputBuilder;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricShapes;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalDSL;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.spec.RateLimitingSamplers;
import org.apache.skywalking.oap.trace.analyzer.v2.module.TraceAnalyzerModule;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.SpanPairing;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.TALSourceTypeProvider;
import org.mockito.Mockito;
import org.yaml.snakeyaml.Yaml;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * Loads every TAL rule file of the submodule the way the distro boots them and compiles them the way the JVM OAP
 * does, so a test can hold the two side by side. Nothing here names a shipped file or rule: an operator's edits to
 * {@code tal/} are covered as they are.
 */
final class TALTestSupport {
    static final Path TAL_DIR = Path.of(System.getProperty("user.dir")).getParent()
        .resolve("skywalking/oap-server/server-starter/src/main/resources/tal");

    private TALTestSupport() {
    }

    /**
     * @return the rule files under {@code tal/}, every non-hidden file as the precompiler takes them
     */
    static List<String> ruleFiles() throws Exception {
        try (Stream<Path> files = Files.list(TAL_DIR)) {
            return files.filter(Files::isRegularFile)
                        .map(f -> f.getFileName().toString())
                        .filter(name -> !name.startsWith("."))
                        .sorted()
                        .collect(Collectors.toList());
        }
    }

    /**
     * The file each rule {@link #precompiledRules} returned was loaded from. The stamped source name cannot be
     * reversed: upstream appends {@code .yaml} to a name ending in neither {@code .yaml} nor {@code .yml}, so
     * {@code custom.rules} and {@code custom.rules.yaml}, which talFiles selects apart, stamp alike.
     */
    private static final Map<TALConfig, String> FILE_OF = new IdentityHashMap<>();

    // Structural and null-preserving: an absent metricPrefix and the text "null" must not collide.
    private static final ObjectMapper SIGNATURE_MAPPER = new ObjectMapper();

    /**
     * @return the file the rule was loaded from
     */
    static String fileOf(final TALConfig rule) {
        final String file = FILE_OF.get(rule);
        if (file == null) {
            throw new IllegalStateException("rule " + rule.getName() + " was not loaded by precompiledRules()");
        }
        return file;
    }

    /**
     * @return every rule of every file, through the replacement {@code TALConfigs} (the precompiler's config-data
     * JSON), stamped as the runtime stamps them
     */
    static List<TALConfig> precompiledRules() throws Exception {
        // One bulk load, as SpanFilterListener.Factory loads them: the order decides which of two rules sharing a
        // layer and a name is installed. The file each loaded set came from is found by loading the files one at a
        // time; two files whose loaded content matches in every field are interchangeable here.
        final Map<JsonNode, String> fileBySignature = new HashMap<>();
        final List<String> stems = new ArrayList<>();
        for (final String file : ruleFiles()) {
            final String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
            stems.add(stem);
            for (final TALConfigs configs : TALConfigs.load("tal", List.of(stem))) {
                fileBySignature.put(signature(configs), file);
            }
        }
        final List<TALConfig> rules = new ArrayList<>();
        for (final TALConfigs configs : TALConfigs.load("tal", stems)) {
            final String file = fileBySignature.get(signature(configs));
            for (final TALConfig rule : configs.getRules()) {
                FILE_OF.put(rule, file);
                rules.add(rule);
            }
        }
        return rules;
    }

    private static JsonNode signature(final TALConfigs configs) {
        return SIGNATURE_MAPPER.valueToTree(configs);
    }

    /**
     * @return a rule file read straight from the submodule, so the fresh path does not trust the precompiler's JSON
     */
    static Map<String, Object> fileYaml(final String fileName) throws Exception {
        try (InputStream in = Files.newInputStream(TAL_DIR.resolve(fileName))) {
            return new Yaml().load(in);
        }
    }

    /**
     * @return the 1-based entry line of each rule of the file, as upstream's {@code TALConfigs.parse} computes it
     */
    static List<Integer> entryLines(final String fileName) throws Exception {
        final String text = Files.readString(TAL_DIR.resolve(fileName), StandardCharsets.UTF_8);
        final Object rules = fileYaml(fileName).get("rules");
        final DslYamlLineIndex index = DslYamlLineIndex.index(text, "rules");
        final List<Integer> lines = new ArrayList<>();
        for (int i = 0; rules instanceof List && i < ((List<?>) rules).size(); i++) {
            lines.add(index.rule(i).getEntryLine());
        }
        return lines;
    }

    /**
     * @return the raw YAML entry the rule was loaded from: same file, entry line, name and layer, so rules sharing a
     * name across layers, or a line in flow-style YAML, are told apart
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> yamlRule(final TALConfig rule) throws Exception {
        final String file = fileOf(rule);
        final List<Object> raw = (List<Object>) fileYaml(file).get("rules");
        final List<Integer> lines = entryLines(file);
        for (int i = 0; i < raw.size(); i++) {
            final Map<String, Object> entry = (Map<String, Object>) raw.get(i);
            if (lines.get(i) == rule.getLineNo() && rule.getName().equals(entry.get("name"))
                && Objects.equals(rule.getLayer(), entry.get("layer"))) {
                return entry;
            }
        }
        throw new IllegalStateException("no rule " + rule.getName() + " at line " + rule.getLineNo() + " of " + file);
    }

    /**
     * The upstream boot path minus the class loading: TALClassGenerator on the rule text, types and prefix of the
     * submodule's YAML, resolved as {@code SpanFilterListener.Factory} resolves them. The hint differs from the rule
     * name so the fresh class never shadows the pre-compiled one.
     */
    static TALClassGenerator freshGenerator(final TALConfig rule) throws Exception {
        final Map<String, Object> raw = yamlRule(rule);
        final TALClassGenerator generator = new TALClassGenerator();
        generator.setInputType(inputProvider(inputTypeOf((String) raw.get("inputType"))).inputType());
        generator.setOutputType(outputType((String) raw.get("outputType")));
        generator.setClassNameHint("fresh_" + rule.getName());
        generator.setSourceRef(DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo()));
        generator.setMetricPrefix((String) fileYaml(fileOf(rule)).get("metricPrefix"));
        generator.setContent((String) raw.get("dsl"));
        return generator;
    }

    static TalExpression freshCompile(final TALConfig rule, final MeterSystem meterSystem) throws Exception {
        final TALClassGenerator generator = freshGenerator(rule);
        final TalExpression expression = generator.compile((String) yamlRule(rule).get("dsl"));
        generator.createMetrics(meterSystem);
        return expression;
    }

    /**
     * The distro boot path: the same-FQCN {@code TalDSL.of}, which loads the pre-compiled class and creates the
     * metrics from the manifest.
     */
    static TalDSL loadPrecompiled(final TALConfig rule, final ModuleManager moduleManager) throws Exception {
        return TalDSL.of(moduleManager, new TraceAnalyzerModuleConfig(), rule.getDsl(),
            inputProvider(inputTypeOf(rule.getInputType())).inputType(), outputType(rule.getOutputType()), rule.getName(),
            rule.getMetricPrefix(), DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo()), new TALMetricShapes(),
            null, null);
    }

    /**
     * @param declared a rule's raw {@code inputType}
     * @return the one input kind it names, as {@code TALConfig#inputTypes} reads it: trimmed, the default when empty
     */
    static String inputTypeOf(final String declared) {
        final TALConfig config = new TALConfig();
        config.setInputType(declared);
        return config.inputTypes().get(0);
    }

    /**
     * @param declared an input kind, an alias or a class name, as {@link #inputTypeOf} returns it
     */
    static TALSourceTypeProvider inputProvider(final String declared) {
        for (final TALSourceTypeProvider provider : ServiceLoader.load(TALSourceTypeProvider.class)) {
            if (provider.alias().equals(declared) || provider.inputType().getName().equals(declared)) {
                return provider;
            }
        }
        throw new IllegalStateException("no TALSourceTypeProvider claims " + declared);
    }

    private static Class<?> outputType(final String declared) throws Exception {
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        for (final TALOutputBuilder builder : ServiceLoader.load(TALOutputBuilder.class)) {
            if (builder.name().equals(declared)) {
                return builder.getClass();
            }
        }
        return Class.forName(declared);
    }

    /**
     * A module manager whose core module serves the given meter system, the naming control and the source
     * receiver, and whose trace-analyzer module serves the pairing and samplers {@code TalDSL}'s FilterSpec reads.
     */
    static ModuleManager moduleManager(final MeterSystem[] meterSystem, final SourceReceiver sourceReceiver,
                                       final NamingControl namingControl) {
        final ModuleManager moduleManager = mock(ModuleManager.class);
        final IComponentLibraryCatalogService catalog = mock(IComponentLibraryCatalogService.class);
        Mockito.when(catalog.getComponentIdIgnoreCase(anyString())).thenAnswer(
            invocation -> "grpc".equalsIgnoreCase(invocation.getArgument(0)) ? 23
                : "http".equalsIgnoreCase(invocation.getArgument(0)) ? 49 : 0);
        final ModuleServiceHolder core = mock(ModuleServiceHolder.class);
        Mockito.when(core.getService(IComponentLibraryCatalogService.class)).thenReturn(catalog);
        Mockito.when(core.getService(MeterSystem.class)).thenAnswer(invocation -> meterSystem[0]);
        Mockito.when(core.getService(NamingControl.class)).thenReturn(namingControl);
        Mockito.when(core.getService(SourceReceiver.class)).thenReturn(sourceReceiver);
        final ModuleProviderHolder coreModule = mock(ModuleProviderHolder.class);
        Mockito.when(coreModule.provider()).thenReturn(core);
        Mockito.when(moduleManager.find(CoreModule.NAME)).thenReturn(coreModule);
        final ModuleServiceHolder traceAnalyzer = mock(ModuleServiceHolder.class);
        Mockito.when(traceAnalyzer.getService(SpanPairing.class)).thenReturn(new SpanPairing());
        Mockito.when(traceAnalyzer.getService(RateLimitingSamplers.class)).thenReturn(new RateLimitingSamplers());
        final ModuleProviderHolder traceAnalyzerModule = mock(ModuleProviderHolder.class);
        Mockito.when(traceAnalyzerModule.provider()).thenReturn(traceAnalyzer);
        Mockito.when(moduleManager.find(TraceAnalyzerModule.NAME)).thenReturn(traceAnalyzerModule);
        return moduleManager;
    }

    static NamingControl namingControl() {
        return new NamingControl(70, 70, 150, new EndpointNameGrouping());
    }
}
