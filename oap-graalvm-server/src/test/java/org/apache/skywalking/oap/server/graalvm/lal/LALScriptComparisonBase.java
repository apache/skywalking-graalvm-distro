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

package org.apache.skywalking.oap.server.graalvm.lal;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import groovy.lang.GString;
import groovy.lang.GroovyShell;
import groovy.transform.CompileStatic;
import groovy.util.DelegatingScript;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.dsl.Binding;
import org.apache.skywalking.oap.log.analyzer.dsl.LALPrecompiledExtension;
import org.apache.skywalking.oap.log.analyzer.dsl.spec.LALDelegatingScript;
import org.apache.skywalking.oap.log.analyzer.dsl.spec.filter.FilterSpec;
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfigs;
import org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleProvider;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.DatabaseSlowStatementBuilder;
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.SampledTraceBuilder;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.worker.RecordStreamProcessor;
import org.apache.skywalking.oap.server.core.config.ConfigService;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.powermock.reflect.Whitebox;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Base class for LAL script comparison tests.
 *
 * <p>Validates that pre-compiled LAL scripts (built at build time) behave
 * identically to freshly-compiled scripts. Uses the same dual-path comparison
 * pattern as {@code MALScriptComparisonBase}.
 *
 * <p>Path A: Fresh Groovy compilation with the same CompilerConfiguration
 * as the build-time LAL DSL.
 *
 * <p>Path B: Load pre-compiled class from manifest via SHA-256 hash lookup.
 */
abstract class LALScriptComparisonBase {
    private static volatile Map<String, String> MANIFEST;

    private final ModuleManager mockManager;
    private final LogAnalyzerModuleConfig config;

    LALScriptComparisonBase() {
        mockManager = createMockManager();
        config = new LogAnalyzerModuleConfig();
    }

    // ── ModuleManager mock setup (from upstream DSLTest) ──

    private static ModuleManager createMockManager() {
        final ModuleManager manager = mock(ModuleManager.class);
        Whitebox.setInternalState(manager, "isInPrepareStage", false);
        when(manager.find(anyString())).thenReturn(mock(ModuleProviderHolder.class));

        // LogAnalyzerModule
        final LogAnalyzerModuleProvider logProvider = mock(LogAnalyzerModuleProvider.class);
        when(logProvider.getMetricConverts()).thenReturn(Collections.emptyList());
        final ModuleProviderHolder logHolder = mock(ModuleProviderHolder.class);
        when(logHolder.provider()).thenReturn(logProvider);
        when(manager.find(LogAnalyzerModule.NAME)).thenReturn(logHolder);

        // CoreModule
        final ModuleServiceHolder coreServiceHolder = mock(ModuleServiceHolder.class);
        when(coreServiceHolder.getService(SourceReceiver.class))
            .thenReturn(mock(SourceReceiver.class));
        final ConfigService configService = mock(ConfigService.class);
        when(configService.getSearchableLogsTags()).thenReturn("");
        when(coreServiceHolder.getService(ConfigService.class))
            .thenReturn(configService);
        final NamingControl namingControl = mock(NamingControl.class);
        when(namingControl.formatServiceName(anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(coreServiceHolder.getService(NamingControl.class))
            .thenReturn(namingControl);
        final ModuleProviderHolder coreHolder = mock(ModuleProviderHolder.class);
        when(coreHolder.provider()).thenReturn(coreServiceHolder);
        when(manager.find(CoreModule.NAME)).thenReturn(coreHolder);

        return manager;
    }

    // ── Path A: Fresh Groovy compilation ──

    @SuppressWarnings("rawtypes")
    private DelegatingScript compileGroovy(final String dsl) {
        final CompilerConfiguration cc = new CompilerConfiguration();
        final ASTTransformationCustomizer customizer =
            new ASTTransformationCustomizer(
                singletonMap("extensions",
                    singletonList(LALPrecompiledExtension.class.getName())),
                CompileStatic.class);
        cc.addCompilationCustomizers(customizer);

        final SecureASTCustomizer secureAST = new SecureASTCustomizer();
        secureAST.setDisallowedStatements(
            ImmutableList.<Class<? extends Statement>>builder()
                .add(WhileStatement.class)
                .add(DoWhileStatement.class)
                .add(ForStatement.class)
                .build());
        secureAST.setAllowedReceiversClasses(
            ImmutableList.<Class>builder()
                .add(Object.class)
                .add(Map.class)
                .add(List.class)
                .add(Array.class)
                .add(GString.class)
                .add(String.class)
                .add(ProcessRegistry.class)
                .build());
        cc.addCompilationCustomizers(secureAST);
        cc.setScriptBaseClass(LALDelegatingScript.class.getName());

        final ImportCustomizer icz = new ImportCustomizer();
        icz.addImport("ProcessRegistry", ProcessRegistry.class.getName());
        cc.addCompilationCustomizers(icz);

        final GroovyShell sh = new GroovyShell(cc);
        return (DelegatingScript) sh.parse(dsl, "test_groovy");
    }

    // ── Path B: Pre-compiled class lookup ──

    private DelegatingScript loadPrecompiled(final String dsl) {
        final Map<String, String> manifest = loadManifest();
        final String hash = sha256(dsl);
        final String className = manifest.get(hash);
        if (className == null) {
            throw new AssertionError(
                "Pre-compiled LAL script not found for hash: " + hash
                    + ". Available: " + manifest.size() + " scripts. "
                    + "Manifest keys: " + manifest.keySet());
        }
        try {
            final Class<?> scriptClass = Class.forName(className);
            return (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            throw new AssertionError(
                "Failed to load pre-compiled LAL class: " + className, e);
        }
    }

    // ── Dual-path comparison ──

    /**
     * Runs both Groovy and pre-compiled paths with the given LogData,
     * then asserts identical Binding state.
     */
    protected void runAndCompare(final String dsl, final LogData logData) {
        runAndCompareInternal(dsl, logData, null);
    }

    /**
     * Variant with extraLog (for envoy-als rules that access parsed?.response etc).
     */
    protected void runAndCompare(final String dsl, final LogData logData,
                                 final Message extraLog) {
        runAndCompareInternal(dsl, logData, extraLog);
    }

    /**
     * Variant with pre-populated parsed Map (for envoy-als where parsed comes
     * from extraLog protobuf normally, but we use Maps for testing).
     */
    protected void runAndCompareWithParsedMap(final String dsl,
                                              final LogData logData,
                                              final Map<String, Object> parsedMap) {
        final DelegatingScript scriptA = compileGroovy(dsl);
        final DelegatingScript scriptB = loadPrecompiled(dsl);

        final EvalResult resultA = evaluateWithParsedMap(scriptA, logData, parsedMap);
        final EvalResult resultB = evaluateWithParsedMap(scriptB, logData, parsedMap);

        assertResultsMatch(resultA, resultB);
    }

    private void runAndCompareInternal(final String dsl,
                                       final LogData logData,
                                       final Message extraLog) {
        final DelegatingScript scriptA = compileGroovy(dsl);
        final DelegatingScript scriptB = loadPrecompiled(dsl);

        final EvalResult resultA = evaluate(scriptA, logData, extraLog);
        final EvalResult resultB = evaluate(scriptB, logData, extraLog);

        assertResultsMatch(resultA, resultB);
    }

    private EvalResult evaluate(final DelegatingScript script,
                                final LogData logData,
                                final Message extraLog) {
        try {
            final FilterSpec filterSpec = new FilterSpec(mockManager, config);
            Whitebox.setInternalState(filterSpec, "sinkListenerFactories",
                Collections.emptyList());
            script.setDelegate(filterSpec);

            final Binding binding = new Binding();
            binding.log(logData.toBuilder().build());
            if (extraLog != null) {
                binding.extraLog(extraLog);
            }
            final List<SampleFamily> metricsContainer = new ArrayList<>();
            binding.metricsContainer(metricsContainer);
            binding.logContainer(new AtomicReference<>());

            // Mock RecordStreamProcessor for sampledTrace
            mockRecordStreamProcessor();

            filterSpec.bind(binding);
            script.run();

            return new EvalResult(binding, metricsContainer, null);
        } catch (final Exception e) {
            return new EvalResult(null, null, e);
        }
    }

    private EvalResult evaluateWithParsedMap(final DelegatingScript script,
                                             final LogData logData,
                                             final Map<String, Object> parsedMap) {
        try {
            final FilterSpec filterSpec = new FilterSpec(mockManager, config);
            Whitebox.setInternalState(filterSpec, "sinkListenerFactories",
                Collections.emptyList());
            script.setDelegate(filterSpec);

            final Binding binding = new Binding();
            binding.log(logData.toBuilder().build());
            binding.parsed(parsedMap);
            final List<SampleFamily> metricsContainer = new ArrayList<>();
            binding.metricsContainer(metricsContainer);
            binding.logContainer(new AtomicReference<>());

            mockRecordStreamProcessor();

            filterSpec.bind(binding);
            script.run();

            return new EvalResult(binding, metricsContainer, null);
        } catch (final Exception e) {
            return new EvalResult(null, null, e);
        }
    }

    private static void mockRecordStreamProcessor() {
        try {
            final RecordStreamProcessor mockRSP = mock(RecordStreamProcessor.class);
            Whitebox.setInternalState(
                RecordStreamProcessor.class, "PROCESSOR", mockRSP);
        } catch (final Exception ignored) {
            // May already be mocked
        }
    }

    // ── Assertion helpers ──

    private static void assertResultsMatch(final EvalResult a,
                                           final EvalResult b) {
        if (a.error != null || b.error != null) {
            // Both should either succeed or fail with same exception type
            if (a.error != null && b.error != null) {
                assertEquals(a.error.getClass(), b.error.getClass(),
                    "Exception types differ: A=" + a.error.getMessage()
                        + ", B=" + b.error.getMessage());
            } else {
                final String msg = a.error != null
                    ? "Groovy path threw " + a.error + " but pre-compiled succeeded"
                    : "Pre-compiled path threw " + b.error + " but Groovy succeeded";
                throw new AssertionError(msg);
            }
            return;
        }

        // Compare abort/save flags
        assertEquals(a.binding.shouldAbort(), b.binding.shouldAbort(),
            "shouldAbort differs");
        assertEquals(a.binding.shouldSave(), b.binding.shouldSave(),
            "shouldSave differs");

        if (a.binding.shouldAbort()) {
            // If aborted, log state may be partially modified. Compare what we can.
            return;
        }

        // Compare LogData.Builder state
        final LogData.Builder logA = a.binding.log();
        final LogData.Builder logB = b.binding.log();
        assertEquals(logA.getService(), logB.getService(), "service differs");
        assertEquals(logA.getServiceInstance(), logB.getServiceInstance(),
            "serviceInstance differs");
        assertEquals(logA.getEndpoint(), logB.getEndpoint(), "endpoint differs");
        assertEquals(logA.getLayer(), logB.getLayer(), "layer differs");
        assertEquals(logA.getTimestamp(), logB.getTimestamp(), "timestamp differs");

        // Compare tags
        final List<KeyStringValuePair> tagsA = logA.getTags().getDataList();
        final List<KeyStringValuePair> tagsB = logB.getTags().getDataList();
        assertEquals(tagsA.size(), tagsB.size(),
            "tag count differs: A=" + tagsA + ", B=" + tagsB);
        for (int i = 0; i < tagsA.size(); i++) {
            assertEquals(tagsA.get(i).getKey(), tagsB.get(i).getKey(),
                "tag key at index " + i + " differs");
            assertEquals(tagsA.get(i).getValue(), tagsB.get(i).getValue(),
                "tag value at index " + i + " differs");
        }

        // Compare metrics container
        assertEquals(a.metrics.size(), b.metrics.size(),
            "metrics container size differs");

        // Compare databaseSlowStatement if set
        try {
            final DatabaseSlowStatementBuilder slowA = a.binding.databaseSlowStatement();
            final DatabaseSlowStatementBuilder slowB = b.binding.databaseSlowStatement();
            if (slowA != null && slowB != null) {
                assertEquals(slowA.getId(), slowB.getId(), "slowSql.id differs");
                assertEquals(slowA.getStatement(), slowB.getStatement(),
                    "slowSql.statement differs");
                assertEquals(slowA.getLatency(), slowB.getLatency(),
                    "slowSql.latency differs");
            } else {
                assertEquals(slowA, slowB, "slowSql builder presence differs");
            }
        } catch (final Exception ignored) {
            // databaseSlowStatement may not be set
        }

        // Compare sampledTrace if set
        try {
            final SampledTraceBuilder traceA = a.binding.sampledTraceBuilder();
            final SampledTraceBuilder traceB = b.binding.sampledTraceBuilder();
            if (traceA != null && traceB != null) {
                assertEquals(traceA.getUri(), traceB.getUri(),
                    "sampledTrace.uri differs");
                assertEquals(traceA.getLatency(), traceB.getLatency(),
                    "sampledTrace.latency differs");
                assertEquals(traceA.getProcessId(), traceB.getProcessId(),
                    "sampledTrace.processId differs");
                assertEquals(traceA.getDestProcessId(), traceB.getDestProcessId(),
                    "sampledTrace.destProcessId differs");
                assertEquals(traceA.getComponentId(), traceB.getComponentId(),
                    "sampledTrace.componentId differs");
            } else {
                assertEquals(traceA, traceB,
                    "sampledTrace builder presence differs");
            }
        } catch (final Exception ignored) {
            // sampledTraceBuilder may not be set
        }
    }

    // ── YAML loading ──

    /**
     * Load LAL rules from a YAML file under the lal/ resource directory.
     */
    protected static List<LALConfig> loadLALRules(final String yamlFileName)
            throws Exception {
        final List<String> fileNames = singletonList(
            yamlFileName.replace(".yaml", "").replace(".yml", ""));
        final List<LALConfigs> configs = LALConfigs.load("lal", fileNames);
        final List<LALConfig> rules = new ArrayList<>();
        for (final LALConfigs c : configs) {
            rules.addAll(c.getRules());
        }
        return rules;
    }

    // ── Manifest loading ──

    protected static Map<String, String> loadManifest() {
        if (MANIFEST != null) {
            return MANIFEST;
        }
        synchronized (LALScriptComparisonBase.class) {
            if (MANIFEST != null) {
                return MANIFEST;
            }
            final Map<String, String> map = new HashMap<>();
            try (InputStream is = LALScriptComparisonBase.class.getClassLoader()
                    .getResourceAsStream("META-INF/lal-scripts-by-hash.txt")) {
                if (is == null) {
                    throw new AssertionError(
                        "Manifest META-INF/lal-scripts-by-hash.txt not found");
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        final String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            map.put(parts[0], parts[1]);
                        }
                    }
                }
            } catch (final Exception e) {
                throw new AssertionError("Failed to load LAL manifest", e);
            }
            MANIFEST = map;
            return map;
        }
    }

    /**
     * Load the script name → class name manifest.
     */
    protected static Map<String, String> loadNameManifest() {
        final Map<String, String> map = new HashMap<>();
        try (InputStream is = LALScriptComparisonBase.class.getClassLoader()
                .getResourceAsStream("META-INF/lal-scripts.txt")) {
            if (is == null) {
                throw new AssertionError(
                    "Manifest META-INF/lal-scripts.txt not found");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    final String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        map.put(parts[0], parts[1]);
                    }
                }
            }
        } catch (final Exception e) {
            throw new AssertionError("Failed to load LAL name manifest", e);
        }
        return map;
    }

    // ── LogData builders ──

    /**
     * Build minimal LogData with text body and optional tags.
     */
    protected static LogData buildTextLogData(final String service,
                                              final String serviceInstance,
                                              final String textBody,
                                              final Map<String, String> tags) {
        final LogData.Builder builder = LogData.newBuilder()
            .setService(service)
            .setServiceInstance(serviceInstance)
            .setTimestamp(System.currentTimeMillis());

        if (textBody != null) {
            builder.setBody(
                org.apache.skywalking.apm.network.logging.v3.LogDataBody.newBuilder()
                    .setText(
                        org.apache.skywalking.apm.network.logging.v3.TextLog.newBuilder()
                            .setText(textBody)
                            .build())
                    .build());
        }

        if (tags != null) {
            final org.apache.skywalking.apm.network.logging.v3.LogTags.Builder
                tagsBuilder = org.apache.skywalking.apm.network.logging.v3
                    .LogTags.newBuilder();
            tags.forEach((k, v) -> tagsBuilder.addData(
                KeyStringValuePair.newBuilder().setKey(k).setValue(v).build()));
            builder.setTags(tagsBuilder);
        }

        return builder.build();
    }

    /**
     * Build LogData with JSON body.
     */
    protected static LogData buildJsonLogData(final String service,
                                              final String serviceInstance,
                                              final String jsonBody,
                                              final Map<String, String> tags) {
        final LogData.Builder builder = LogData.newBuilder()
            .setService(service)
            .setServiceInstance(serviceInstance)
            .setTimestamp(System.currentTimeMillis());

        if (jsonBody != null) {
            builder.setBody(
                org.apache.skywalking.apm.network.logging.v3.LogDataBody.newBuilder()
                    .setJson(
                        org.apache.skywalking.apm.network.logging.v3.JSONLog.newBuilder()
                            .setJson(jsonBody)
                            .build())
                    .build());
        }

        if (tags != null) {
            final org.apache.skywalking.apm.network.logging.v3.LogTags.Builder
                tagsBuilder = org.apache.skywalking.apm.network.logging.v3
                    .LogTags.newBuilder();
            tags.forEach((k, v) -> tagsBuilder.addData(
                KeyStringValuePair.newBuilder().setKey(k).setValue(v).build()));
            builder.setTags(tagsBuilder);
        }

        return builder.build();
    }

    // ── Utility ──

    static String sha256(final String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(
                input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static final class EvalResult {
        final Binding binding;
        final List<SampleFamily> metrics;
        final Exception error;

        EvalResult(final Binding binding,
                   final List<SampleFamily> metrics,
                   final Exception error) {
            this.binding = binding;
            this.metrics = metrics;
            this.error = error;
        }
    }
}
