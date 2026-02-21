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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.meter.analyzer.MetricConvert;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfigs;
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig;
import org.apache.skywalking.oap.meter.analyzer.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.prometheus.rule.Rules;
import org.yaml.snakeyaml.Yaml;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.aop.server.receiver.mesh.MeshOALDefine;
import org.apache.skywalking.oal.v2.OALEngineV2;
import org.apache.skywalking.oal.v2.generator.OALClassGeneratorV2;
import org.apache.skywalking.oap.server.core.analysis.Disable;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.ISourceDecorator;
import org.apache.skywalking.oap.server.core.analysis.MultipleDisable;
import org.apache.skywalking.oap.server.core.analysis.SourceDispatcher;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction;
import org.apache.skywalking.oap.server.core.annotation.AnnotationScan;
import org.apache.skywalking.oap.server.core.oal.rt.CoreOALDefine;
import org.apache.skywalking.oap.server.core.oal.rt.DisableOALDefine;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.source.ScopeDeclaration;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;
import org.apache.skywalking.oap.server.fetcher.cilium.CiliumOALDefine;
import org.apache.skywalking.oap.server.receiver.browser.provider.BrowserOALDefine;
import org.apache.skywalking.oap.server.receiver.clr.provider.CLROALDefine;
import org.apache.skywalking.oap.server.receiver.ebpf.provider.EBPFOALDefine;
import org.apache.skywalking.oap.server.receiver.envoy.TCPOALDefine;
import org.apache.skywalking.oap.server.receiver.jvm.provider.JVMOALDefine;

/**
 * Build-time pre-compilation tool that runs the OAL engine for all 9 OALDefine
 * configurations, exports generated .class files and manifest files, and scans
 * the classpath for hardcoded annotated classes and interface implementations
 * used at runtime.
 *
 * OAL script files are loaded from the skywalking submodule directly via
 * additionalClasspathElements in the exec-maven-plugin configuration.
 */
@Slf4j
public class Precompiler {

    private static final String METRICS_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.metrics.";
    private static final String BUILDER_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.";
    private static final String DISPATCHER_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.";

    static final OALDefine[] ALL_DEFINES = {
        DisableOALDefine.INSTANCE,
        CoreOALDefine.INSTANCE,
        JVMOALDefine.INSTANCE,
        CLROALDefine.INSTANCE,
        BrowserOALDefine.INSTANCE,
        MeshOALDefine.INSTANCE,
        EBPFOALDefine.INSTANCE,
        TCPOALDefine.INSTANCE,
        CiliumOALDefine.INSTANCE
    };

    public static void main(String[] args) throws Exception {
        final String outputDir = args.length > 0
            ? args[0]
            : "target/generated-classes";

        log.info("Precompiler: output -> {}", outputDir);

        // Validate all OAL scripts are available on classpath before running
        validateOALScripts();

        // Initialize DefaultScopeDefine — scan @ScopeDeclaration annotations on source
        // classes (Service, Endpoint, etc.) to populate the scope name → ID → columns
        // registry. The OAL enricher needs this to resolve source metadata.
        AnnotationScan scopeScan = new AnnotationScan();
        scopeScan.registerListener(new DefaultScopeDefine.Listener());
        scopeScan.scan();
        log.info("Initialized DefaultScopeDefine scope registry");

        // Set generated file path so debug output lands in proper package structure.
        // writeGeneratedFile() appends "/metrics/", "/metrics/builder/", "/dispatcher/"
        // which matches the actual Java package sub-paths.
        OALClassGeneratorV2.setGeneratedFilePath(
            outputDir + "/org/apache/skywalking/oap/server/core/source/oal/rt");

        // Skip prepareRTTempFolder() which uses WorkPath.getPath() — not available
        // in build tool context. Set the static guard to true so it becomes a no-op.
        Field rtFolderInitField = OALClassGeneratorV2.class.getDeclaredField(
            "IS_RT_TEMP_FOLDER_INIT_COMPLETED");
        rtFolderInitField.setAccessible(true);
        rtFolderInitField.set(null, true);

        // Run all 9 OAL defines
        for (OALDefine define : ALL_DEFINES) {
            log.info("Processing: {}", define.getConfigFile());
            OALEngineV2 engine = new OALEngineV2(define);
            engine.getClassGeneratorV2().setOpenEngineDebug(true);
            engine.setStorageBuilderFactory(new StorageBuilderFactory.Default());
            engine.start(Precompiler.class.getClassLoader());
        }

        // Scan generated .class files and build manifests
        List<String> metricsClasses = scanClassNames(outputDir, "metrics", METRICS_PACKAGE);
        List<String> dispatcherClasses = scanClassNames(outputDir, "dispatcher", DISPATCHER_PACKAGE);
        List<String> disabledSources = getDisabledSources();

        // Write manifest files
        Path metaInf = Path.of(outputDir, "META-INF");
        Files.createDirectories(metaInf);
        writeManifest(metaInf.resolve("oal-metrics-classes.txt"), metricsClasses);
        writeManifest(metaInf.resolve("oal-dispatcher-classes.txt"), dispatcherClasses);
        writeManifest(metaInf.resolve("oal-disabled-sources.txt"), disabledSources);

        log.info("Precompiler: {} metrics, {} dispatchers, {} disabled sources",
            metricsClasses.size(), dispatcherClasses.size(), disabledSources.size());

        // ---- Annotation & interface scanning for hardcoded classes ----
        Path annotationScanDir = metaInf.resolve("annotation-scan");
        Files.createDirectories(annotationScanDir);

        ImmutableSet<ClassPath.ClassInfo> allClasses = ClassPath
            .from(Precompiler.class.getClassLoader())
            .getTopLevelClassesRecursive("org.apache.skywalking");

        writeManifest(annotationScanDir.resolve("ScopeDeclaration.txt"),
            scanAnnotation(allClasses, ScopeDeclaration.class));
        writeManifest(annotationScanDir.resolve("Stream.txt"),
            scanAnnotation(allClasses, org.apache.skywalking.oap.server.core.analysis.Stream.class));
        writeManifest(annotationScanDir.resolve("Disable.txt"),
            scanAnnotation(allClasses, Disable.class));
        writeManifest(annotationScanDir.resolve("MultipleDisable.txt"),
            scanAnnotation(allClasses, MultipleDisable.class));
        writeManifest(annotationScanDir.resolve("SourceDispatcher.txt"),
            scanInterface(allClasses, SourceDispatcher.class));
        writeManifest(annotationScanDir.resolve("ISourceDecorator.txt"),
            scanInterface(allClasses, ISourceDecorator.class));

        // MeterFunction scan: extract functionName=FQCN pairs for MeterSystem manifest
        writeManifest(annotationScanDir.resolve("MeterFunction.txt"),
            scanMeterFunctions(allClasses));

        // ---- MAL pre-compilation ----
        compileMAL(outputDir, allClasses);

        // ---- LAL pre-compilation ----
        compileLAL(outputDir);

        // ---- GraalVM native-image metadata generation ----
        generateNativeImageConfig(outputDir);

        log.info("Precompiler: done");
    }

    /**
     * Pre-compile all MAL (Meter Analysis Language) rules.
     * Runs the full MAL pipeline at build time with intercepting same-FQCN classes
     * that capture Groovy bytecode and Javassist-generated meter classes to disk.
     */
    @SuppressWarnings("unchecked")
    private static void compileMAL(String outputDir,
                                   ImmutableSet<ClassPath.ClassInfo> allClasses) throws Exception {
        File groovyOutputDir = new File(outputDir);

        // Build function register from scanned @MeterFunction classes
        Map<String, Class<? extends AcceptableValue>> functionRegister = new HashMap<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isAnnotationPresent(MeterFunction.class)) {
                    MeterFunction mf = aClass.getAnnotation(MeterFunction.class);
                    if (AcceptableValue.class.isAssignableFrom(aClass)) {
                        functionRegister.put(
                            mf.functionName(), (Class<? extends AcceptableValue>) aClass);
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        log.info("MAL: built function register with {} entries", functionRegister.size());

        // Set up build-time interceptors
        DSL.setTargetDirectory(groovyOutputDir);
        FilterExpression.setTargetDirectory(groovyOutputDir);
        MeterSystem.setFunctionRegister(functionRegister);
        MeterSystem.setOutputDirectory(outputDir);

        MeterSystem meterSystem = new MeterSystem(null);
        int totalRules = 0;
        Map<String, List<Rule>> rulesByPath = new LinkedHashMap<>();

        // 1. Agent meter configs (meter-analyzer-config/*.yaml)
        List<Rule> meterAnalyzerRules = loadAndCompileRules("meter-analyzer-config", List.of("*"), meterSystem);
        totalRules += meterAnalyzerRules.size();
        rulesByPath.put("meter-analyzer-config", meterAnalyzerRules);

        // 2. OTel rules (otel-rules/*.yaml + otel-rules/**/*.yaml) — includes root-level files
        List<Rule> otelRules = loadAndCompileRules("otel-rules", List.of("*", "**/*"), meterSystem);
        totalRules += otelRules.size();
        rulesByPath.put("otel-rules", otelRules);

        // 3. Log MAL rules (log-mal-rules/*.yaml)
        List<Rule> logMalRules = loadAndCompileRules("log-mal-rules", List.of("*"), meterSystem);
        totalRules += logMalRules.size();
        rulesByPath.put("log-mal-rules", logMalRules);

        // 4. Envoy metrics rules (envoy-metrics-rules/*.yaml)
        List<Rule> envoyRules = loadAndCompileRules("envoy-metrics-rules", List.of("*"), meterSystem);
        totalRules += envoyRules.size();
        rulesByPath.put("envoy-metrics-rules", envoyRules);

        // 5. Telegraf rules (telegraf-rules/*.yaml)
        // Shares metricPrefix=meter_vm with otel-rules/vm.yaml — combination pattern:
        // multiple expressions from different sources aggregate into the same metrics.
        List<Rule> telegrafRules = loadAndCompileRules("telegraf-rules", List.of("*"), meterSystem);
        totalRules += telegrafRules.size();
        rulesByPath.put("telegraf-rules", telegrafRules);

        // 6. Zabbix rules (zabbix-rules/*.yaml)
        // Uses 'metrics' field instead of 'metricsRules', requires custom loading.
        List<Rule> zabbixRules = loadAndCompileZabbixRules("zabbix-rules", meterSystem);
        totalRules += zabbixRules.size();
        rulesByPath.put("zabbix-rules", zabbixRules);

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");
        writeManifest(metaInf.resolve("mal-meter-classes.txt"), meterSystem.getExportedClasses());

        List<String> scriptEntries = DSL.getScriptRegistry().entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .sorted()
            .collect(Collectors.toList());
        writeManifest(metaInf.resolve("mal-groovy-scripts.txt"), scriptEntries);

        List<String> expressionHashEntries = DSL.getExpressionHashes().entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .sorted()
            .collect(Collectors.toList());
        writeManifest(metaInf.resolve("mal-groovy-expression-hashes.txt"), expressionHashEntries);

        // Filter scripts use Properties format to handle special chars in filter literals
        Properties filterProps = new Properties();
        FilterExpression.getScriptRegistry().forEach(filterProps::setProperty);
        try (OutputStream os = Files.newOutputStream(metaInf.resolve("mal-filter-scripts.properties"))) {
            filterProps.store(os, null);
        }

        log.info("MAL pre-compilation: {} rules, {} meter classes, {} groovy scripts, {} filter scripts",
            totalRules,
            meterSystem.getExportedClasses().size(),
            DSL.getScriptRegistry().size(),
            FilterExpression.getScriptRegistry().size());

        // ---- MAL-to-Java transpilation ----
        transpileMAL(outputDir);

        // ---- Serialize config data as JSON for runtime loaders ----
        serializeMALConfigData(outputDir, rulesByPath);
    }

    /**
     * Load rules from a resource directory and compile them through the MAL pipeline.
     * Returns the loaded rules for config data serialization.
     */
    private static List<Rule> loadAndCompileRules(String path, List<String> enabledPatterns,
                                                  MeterSystem meterSystem) {
        List<Rule> loadedRules = Collections.emptyList();
        int count = 0;
        try {
            loadedRules = Rules.loadRules(path, enabledPatterns);
            for (Rule rule : loadedRules) {
                try {
                    new MetricConvert(rule, meterSystem);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to compile MAL rule: {} ({})",
                        rule.getMetricPrefix(), rule.getName(), e);
                }
            }
            log.info("MAL: compiled {} rules from {}", count, path);
        } catch (Exception e) {
            log.warn("Failed to load rules from {}", path, e);
        }
        return loadedRules;
    }

    /**
     * Load Zabbix rules which use 'metrics' field instead of 'metricsRules'.
     * Parses the YAML manually and maps into a Rule for MetricConvert.
     * Returns the loaded rules for config data serialization.
     */
    @SuppressWarnings("unchecked")
    private static List<Rule> loadAndCompileZabbixRules(String path,
                                                        MeterSystem meterSystem) {
        List<Rule> loadedRules = new ArrayList<>();
        int count = 0;
        try {
            File[] files = ResourceUtils.getPathFiles(path);
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
                    continue;
                }
                String resourcePath = path + "/" + name;
                try (InputStream is = Precompiler.class.getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        log.warn("Zabbix rule not found: {}", resourcePath);
                        continue;
                    }
                    Map<String, Object> yamlMap = new Yaml().load(is);
                    Rule rule = new Rule();
                    rule.setName(name.substring(0, name.lastIndexOf('.')));
                    rule.setMetricPrefix((String) yamlMap.get("metricPrefix"));
                    rule.setExpSuffix((String) yamlMap.get("expSuffix"));
                    rule.setExpPrefix((String) yamlMap.get("expPrefix"));
                    rule.setFilter((String) yamlMap.get("filter"));
                    rule.setInitExp((String) yamlMap.get("initExp"));

                    List<Map<String, String>> metrics =
                        (List<Map<String, String>>) yamlMap.get("metrics");
                    if (metrics != null) {
                        List<MetricsRule> metricsRules = new ArrayList<>();
                        for (Map<String, String> m : metrics) {
                            metricsRules.add(MetricsRule.builder()
                                .name(m.get("name"))
                                .exp(m.get("exp"))
                                .build());
                        }
                        rule.setMetricsRules(metricsRules);
                    }

                    new MetricConvert(rule, meterSystem);
                    loadedRules.add(rule);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to compile Zabbix rule: {}", resourcePath, e);
                }
            }
            log.info("MAL: compiled {} rules from {}", count, path);
        } catch (Exception e) {
            log.warn("Failed to load rules from {}", path, e);
        }
        return loadedRules;
    }

    /**
     * Transpile MAL expressions and filters from Groovy to pure Java classes.
     * Runs after MetricConvert pipeline — uses expression strings captured by DSL
     * and filter literals captured by FilterExpression.
     */
    private static void transpileMAL(String outputDir) throws Exception {
        MalToJavaTranspiler transpiler = new MalToJavaTranspiler();
        int exprCount = 0;
        int filterCount = 0;

        // Transpile expressions: scriptName → expression string
        for (Map.Entry<String, String> entry : DSL.getExpressionRegistry().entrySet()) {
            String scriptName = entry.getKey();
            String expression = entry.getValue();
            String className = "MalExpr_" + scriptName;
            try {
                String source = transpiler.transpileExpression(className, expression);
                transpiler.registerExpression(className, source);
                exprCount++;
            } catch (Exception e) {
                log.warn("Failed to transpile MAL expression: {} = {}", scriptName, expression, e);
            }
        }

        // Transpile filters: literal → Groovy class (we need literal for transpiler)
        int filterIdx = 0;
        for (String literal : FilterExpression.getScriptRegistry().keySet()) {
            String className = "MalFilter_" + filterIdx++;
            try {
                String source = transpiler.transpileFilter(className, literal);
                transpiler.registerFilter(className, literal, source);
                filterCount++;
            } catch (Exception e) {
                log.warn("Failed to transpile MAL filter: {}", literal, e);
            }
        }

        // Compile generated Java sources.
        // Transpiled code targets GraalVM APIs (Java functional interfaces in SampleFamily,
        // public ExpressionParsingContext.get()). Prepend meter-analyzer-for-graalvm classes
        // to javac classpath so they take precedence over upstream meter-analyzer.
        File javaSourceDir = new File(outputDir, "generated-mal-sources");
        File javaOutputDir = new File(outputDir);
        String classpath = resolveClasspath();
        File graalvmMeterClasses = findGraalvmMeterClasses(outputDir);
        if (graalvmMeterClasses != null) {
            classpath = graalvmMeterClasses.getAbsolutePath() + File.pathSeparator + classpath;
            log.info("MAL transpilation: prepended {} to javac classpath",
                graalvmMeterClasses.getAbsolutePath());
        } else {
            log.warn("MAL transpilation: meter-analyzer-for-graalvm classes not found; "
                + "transpiled sources may fail to compile");
        }
        transpiler.compileAll(javaSourceDir, javaOutputDir, classpath);

        // Write transpiled manifests (alongside existing Groovy manifests)
        transpiler.writeExpressionManifest(new File(outputDir));
        transpiler.writeFilterManifest(new File(outputDir));

        log.info("MAL transpilation: {} expressions, {} filters transpiled to Java",
            exprCount, filterCount);
    }

    /**
     * Pre-compile all LAL (Log Analysis Language) scripts.
     * Uses the same CompilerConfiguration as upstream (CompileStatic + LALPrecompiledExtension,
     * SecureASTCustomizer, LALDelegatingScript base class) plus targetDirectory for bytecode capture.
     */
    private static void compileLAL(String outputDir) throws Exception {
        File groovyOutputDir = new File(outputDir);
        org.apache.skywalking.oap.log.analyzer.dsl.DSL.setTargetDirectory(groovyOutputDir);

        // Enumerate all LAL YAML files from classpath
        File[] lalFiles = ResourceUtils.getPathFiles("lal");
        List<String> lalFileNames = new ArrayList<>();
        Map<String, File> lalFileMap = new LinkedHashMap<>();
        for (File f : lalFiles) {
            String name = f.getName();
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                String key = name.substring(0, name.lastIndexOf('.'));
                lalFileNames.add(key);
                lalFileMap.put(key, f);
            }
        }

        // Load all LAL configs and collect DSL text for transpilation
        List<LALConfigs> allConfigs = LALConfigs.load("lal", lalFileNames);
        int totalRules = 0;
        Map<String, String> hashToDsl = new LinkedHashMap<>();

        for (LALConfigs configs : allConfigs) {
            if (configs.getRules() == null) {
                continue;
            }
            for (LALConfig rule : configs.getRules()) {
                try {
                    org.apache.skywalking.oap.log.analyzer.dsl.DSL.compile(
                        rule.getName(), rule.getDsl());
                    totalRules++;
                    // Capture DSL text keyed by hash for transpilation
                    String hash = org.apache.skywalking.oap.log.analyzer.dsl.DSL.sha256(
                        rule.getDsl());
                    hashToDsl.putIfAbsent(hash, rule.getDsl());
                } catch (Exception e) {
                    log.warn("Failed to compile LAL rule: {}", rule.getName(), e);
                }
            }
        }

        // Write manifests — hash-based for runtime lookup (DSL.of() only gets DSL string)
        Path metaInf = Path.of(outputDir, "META-INF");

        List<String> lalNameEntries =
            org.apache.skywalking.oap.log.analyzer.dsl.DSL.getScriptRegistry().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .collect(Collectors.toList());
        writeManifest(metaInf.resolve("lal-scripts.txt"), lalNameEntries);

        List<String> lalHashEntries =
            org.apache.skywalking.oap.log.analyzer.dsl.DSL.getDslHashRegistry().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .sorted()
                .collect(Collectors.toList());
        writeManifest(metaInf.resolve("lal-scripts-by-hash.txt"), lalHashEntries);

        log.info("LAL pre-compilation: {} rules, {} scripts",
            totalRules,
            org.apache.skywalking.oap.log.analyzer.dsl.DSL.getScriptRegistry().size());

        // ---- LAL-to-Java transpilation ----
        transpileLAL(outputDir, hashToDsl);

        // ---- Serialize LAL config data as JSON for runtime loader ----
        serializeLALConfigData(outputDir, lalFileMap);
    }

    /**
     * Transpile LAL scripts from Groovy to pure Java classes implementing LalExpression.
     * Runs after Groovy compilation — uses captured DSL text keyed by SHA-256 hash.
     */
    private static void transpileLAL(String outputDir,
                                     Map<String, String> hashToDsl) throws Exception {
        LalToJavaTranspiler transpiler = new LalToJavaTranspiler();
        int count = 0;

        for (Map.Entry<String, String> entry : hashToDsl.entrySet()) {
            String hash = entry.getKey();
            String dslText = entry.getValue();
            String className = "LalExpr_" + count;
            try {
                String source = transpiler.transpile(className, dslText);
                transpiler.register(className, hash, source);
                count++;
            } catch (Exception e) {
                log.warn("Failed to transpile LAL script (hash={}): {}",
                    hash, dslText.substring(0, Math.min(80, dslText.length())), e);
            }
        }

        // Compile generated Java sources
        File javaSourceDir = new File(outputDir, "generated-lal-sources");
        File javaOutputDir = new File(outputDir);
        String classpath = resolveClasspath();
        File graalvmLogClasses = findGraalvmLogAnalyzerClasses(outputDir);
        if (graalvmLogClasses != null) {
            classpath = graalvmLogClasses.getAbsolutePath() + File.pathSeparator + classpath;
            log.info("LAL transpilation: prepended {} to javac classpath",
                graalvmLogClasses.getAbsolutePath());
        } else {
            log.warn("LAL transpilation: log-analyzer-for-graalvm classes not found; "
                + "transpiled sources may fail to compile");
        }
        transpiler.compileAll(javaSourceDir, javaOutputDir, classpath);

        // Write transpiled manifest
        transpiler.writeManifest(new File(outputDir));

        log.info("LAL transpilation: {} scripts transpiled to Java", count);
    }

    /**
     * Locate log-analyzer-for-graalvm compiled classes directory.
     */
    private static File findGraalvmLogAnalyzerClasses(String outputDir) {
        String relPath = "oap-libs-for-graalvm/log-analyzer-for-graalvm/target/classes";

        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            File candidate = new File(new File(basedir).getParentFile().getParentFile(), relPath);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }

        File candidate = new File(outputDir).getParentFile()
            .getParentFile()
            .getParentFile()
            .getParentFile();
        candidate = new File(candidate, relPath);
        if (candidate.isDirectory()) {
            return candidate;
        }

        candidate = new File(System.getProperty("user.dir"), relPath);
        if (candidate.isDirectory()) {
            return candidate;
        }

        return null;
    }

    /**
     * Validate that all OAL script files referenced by ALL_DEFINES are
     * available on the classpath. Fails fast with a clear error if any are missing.
     */
    private static void validateOALScripts() {
        ClassLoader cl = Precompiler.class.getClassLoader();
        List<String> missing = new ArrayList<>();
        for (OALDefine define : ALL_DEFINES) {
            String configFile = define.getConfigFile();
            if (cl.getResource(configFile) == null) {
                missing.add(configFile + " (" + define.getClass().getSimpleName() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "OAL script files not found on classpath. "
                + "Ensure the skywalking submodule resource directory is on the classpath.\n"
                + "Missing:\n  " + String.join("\n  ", missing));
        }
        log.info("Validated: all {} OAL scripts found on classpath", ALL_DEFINES.length);
    }

    /**
     * Scan a subdirectory under the generated package path for .class files
     * and compute fully-qualified class names.
     */
    private static List<String> scanClassNames(
        String outputDir, String subDir, String packagePrefix) throws IOException {

        Path dir = Path.of(outputDir,
            "org/apache/skywalking/oap/server/core/source/oal/rt", subDir);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                 .filter(p -> Files.isRegularFile(p))
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String simpleName = fileName.substring(0, fileName.length() - ".class".length());
                     classNames.add(packagePrefix + simpleName);
                 });
        }
        Collections.sort(classNames);
        return classNames;
    }

    /**
     * Read disabled source names from DisableRegister singleton via reflection.
     */
    @SuppressWarnings("unchecked")
    private static List<String> getDisabledSources() throws Exception {
        Field field = DisableRegister.class.getDeclaredField("disableEntitySet");
        field.setAccessible(true);
        Set<String> disabledSet = (Set<String>) field.get(DisableRegister.INSTANCE);
        List<String> result = new ArrayList<>(disabledSet);
        Collections.sort(result);
        return result;
    }

    /**
     * Scan for classes annotated with the given annotation type.
     */
    private static List<String> scanAnnotation(
        ImmutableSet<ClassPath.ClassInfo> allClasses,
        Class<? extends Annotation> annotationType) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isAnnotationPresent(annotationType)) {
                    result.add(aClass.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
                // Some classes may fail to load due to missing optional dependencies
            }
        }
        Collections.sort(result);
        log.info("Scanned @{}: {} classes", annotationType.getSimpleName(), result.size());
        return result;
    }

    /**
     * Scan for concrete classes implementing the given interface.
     */
    private static List<String> scanInterface(
        ImmutableSet<ClassPath.ClassInfo> allClasses, Class<?> interfaceType) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (!aClass.isInterface()
                    && !Modifier.isAbstract(aClass.getModifiers())
                    && interfaceType.isAssignableFrom(aClass)) {
                    result.add(aClass.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
                // Some classes may fail to load due to missing optional dependencies
            }
        }
        Collections.sort(result);
        log.info("Scanned {}: {} classes", interfaceType.getSimpleName(), result.size());
        return result;
    }

    /**
     * Scan for @MeterFunction-annotated classes that implement AcceptableValue.
     * Returns lines in format "functionName=FQCN".
     */
    private static List<String> scanMeterFunctions(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isAnnotationPresent(MeterFunction.class)) {
                    MeterFunction mf = aClass.getAnnotation(MeterFunction.class);
                    if (!AcceptableValue.class.isAssignableFrom(aClass)) {
                        log.warn("@MeterFunction class {} doesn't implement AcceptableValue, skipping",
                            aClass.getName());
                        continue;
                    }
                    result.add(mf.functionName() + "=" + aClass.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
                // Some classes may fail to load due to missing optional dependencies
            }
        }
        Collections.sort(result);
        log.info("Scanned @MeterFunction: {} classes", result.size());
        return result;
    }

    /**
     * Serialize MAL config data (Rules and MeterConfigs) as JSON for runtime loaders.
     * At runtime, replacement loader classes deserialize from these JSON files instead
     * of reading YAML from the filesystem.
     */
    private static void serializeMALConfigData(String outputDir,
                                               Map<String, List<Rule>> rulesByPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        // Serialize MeterConfig objects for meter-analyzer-config (runtime uses MeterConfigs.loadConfig)
        List<Rule> meterAnalyzerRules = rulesByPath.get("meter-analyzer-config");
        if (meterAnalyzerRules != null) {
            Map<String, MeterConfig> meterConfigs = loadMeterConfigs("meter-analyzer-config");
            mapper.writeValue(configDataDir.resolve("meter-analyzer-config.json").toFile(), meterConfigs);
            log.info("Serialized {} MeterConfig entries from meter-analyzer-config to config-data JSON",
                meterConfigs.size());
        }

        // Serialize Rule lists for each path (runtime uses Rules.loadRules)
        for (Map.Entry<String, List<Rule>> entry : rulesByPath.entrySet()) {
            String path = entry.getKey();
            if ("meter-analyzer-config".equals(path)) {
                // meter-analyzer-config is already serialized as MeterConfig above
                continue;
            }
            List<Rule> rules = entry.getValue();
            mapper.writeValue(configDataDir.resolve(path + ".json").toFile(), rules);
            log.info("Serialized {} Rule entries from {} to config-data JSON", rules.size(), path);
        }
    }

    /**
     * Load MeterConfig objects from meter-analyzer-config YAML files.
     * Returns a Map keyed by filename (without extension) for filtering at runtime.
     */
    private static Map<String, MeterConfig> loadMeterConfigs(String path) throws Exception {
        File[] files = ResourceUtils.getPathFiles(path);
        Map<String, MeterConfig> result = new LinkedHashMap<>();
        Yaml yaml = new Yaml();
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
                continue;
            }
            String key = name.substring(0, name.lastIndexOf('.'));
            try (Reader r = new FileReader(file)) {
                MeterConfig config = yaml.loadAs(r, MeterConfig.class);
                if (config != null) {
                    result.put(key, config);
                }
            }
        }
        return result;
    }

    /**
     * Serialize LAL config data as JSON for runtime loader.
     * At runtime, the replacement LALConfigs.load() deserializes from this JSON file.
     */
    private static void serializeLALConfigData(String outputDir,
                                               Map<String, File> lalFileMap) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        Map<String, LALConfigs> lalConfigMap = new LinkedHashMap<>();
        Yaml yaml = new Yaml();
        for (Map.Entry<String, File> entry : lalFileMap.entrySet()) {
            try (Reader r = new FileReader(entry.getValue())) {
                LALConfigs config = yaml.loadAs(r, LALConfigs.class);
                if (config != null) {
                    lalConfigMap.put(entry.getKey(), config);
                }
            }
        }

        mapper.writeValue(configDataDir.resolve("lal.json").toFile(), lalConfigMap);
        log.info("Serialized {} LALConfigs entries from lal to config-data JSON", lalConfigMap.size());
    }

    private static void writeManifest(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * Generate GraalVM native-image metadata (reflect-config.json and resource-config.json)
     * from the manifests already produced by the precompiler.
     *
     * This gives native-image 100% coverage of all pre-compiled classes — unlike the tracing
     * agent which only captures code paths exercised during a traced run.
     */
    private static void generateNativeImageConfig(String outputDir) throws IOException {
        Path nativeImageDir = Path.of(outputDir,
            "META-INF", "native-image", "org.apache.skywalking", "oap-graalvm-distro");
        Files.createDirectories(nativeImageDir);

        generateReflectConfig(outputDir, nativeImageDir);
        generateResourceConfig(nativeImageDir);
    }

    /**
     * Generate reflect-config.json from all manifest files.
     *
     * Annotation-scanned classes get full reflection access (fields, methods, constructors)
     * because AnnotationScan, StreamAnnotationListener, etc. inspect annotations and fields.
     *
     * Script/metric/dispatcher classes get constructor-only access since they are loaded
     * via Class.forName() + getDeclaredConstructor().newInstance().
     */
    private static void generateReflectConfig(String outputDir, Path nativeImageDir) throws IOException {
        Path metaInf = Path.of(outputDir, "META-INF");
        Path annotationScanDir = metaInf.resolve("annotation-scan");

        List<Map<String, Object>> entries = new ArrayList<>();

        // Annotation-scanned classes — full reflection access
        String[] fullAccessManifests = {
            "ScopeDeclaration.txt", "Stream.txt", "Disable.txt", "MultipleDisable.txt",
            "SourceDispatcher.txt", "ISourceDecorator.txt"
        };
        for (String manifest : fullAccessManifests) {
            Path file = annotationScanDir.resolve(manifest);
            if (Files.exists(file)) {
                for (String className : readClassNames(file)) {
                    entries.add(fullAccessEntry(className));
                }
            }
        }

        // MeterFunction manifest — key=value format, full access (MeterSystem inspects annotations)
        Path meterFunctionFile = annotationScanDir.resolve("MeterFunction.txt");
        if (Files.exists(meterFunctionFile)) {
            for (String className : readValueFromKeyValue(meterFunctionFile)) {
                entries.add(fullAccessEntry(className));
            }
        }

        // OAL metrics and dispatchers — constructor-only
        addConstructorEntries(entries, metaInf.resolve("oal-metrics-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-dispatcher-classes.txt"));

        // MAL Groovy scripts — key=value format, constructor-only
        Path malScripts = metaInf.resolve("mal-groovy-scripts.txt");
        if (Files.exists(malScripts)) {
            for (String className : readValueFromKeyValue(malScripts)) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // MAL filter scripts — Properties format, constructor-only
        Path filterScripts = metaInf.resolve("mal-filter-scripts.properties");
        if (Files.exists(filterScripts)) {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(filterScripts)) {
                props.load(is);
            }
            List<String> filterClasses = new ArrayList<>(props.stringPropertyNames().stream()
                .map(k -> props.getProperty(k))
                .sorted()
                .collect(Collectors.toList()));
            for (String className : filterClasses) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // MAL transpiled expressions — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("mal-expressions.txt"));

        // MAL transpiled filters — Properties format, constructor-only
        Path transpiledFilters = metaInf.resolve("mal-filter-expressions.properties");
        if (Files.exists(transpiledFilters)) {
            Properties tProps = new Properties();
            try (InputStream is = Files.newInputStream(transpiledFilters)) {
                tProps.load(is);
            }
            for (String className : tProps.stringPropertyNames().stream()
                    .map(tProps::getProperty).sorted().collect(Collectors.toList())) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // LAL scripts — key=value format, constructor-only
        Path lalScripts = metaInf.resolve("lal-scripts-by-hash.txt");
        if (Files.exists(lalScripts)) {
            for (String className : readValueFromKeyValue(lalScripts)) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // LAL transpiled expressions — key=value format, constructor-only
        Path lalExpressions = metaInf.resolve("lal-expressions.txt");
        if (Files.exists(lalExpressions)) {
            for (String className : readValueFromKeyValue(lalExpressions)) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // MAL meter classes — key=value format, constructor-only
        Path meterClasses = metaInf.resolve("mal-meter-classes.txt");
        if (Files.exists(meterClasses)) {
            for (String className : readValueFromKeyValue(meterClasses)) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // Write reflect-config.json
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(nativeImageDir.resolve("reflect-config.json").toFile(), entries);
        log.info("Generated reflect-config.json with {} entries", entries.size());
    }

    /**
     * Generate resource-config.json for all META-INF resources in the generated JAR.
     */
    private static void generateResourceConfig(Path nativeImageDir) throws IOException {
        Map<String, Object> resourceConfig = new LinkedHashMap<>();
        Map<String, Object> resources = new LinkedHashMap<>();
        List<Map<String, String>> includes = new ArrayList<>();

        includes.add(Map.of("pattern", "META-INF/annotation-scan/.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/oal-.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/mal-.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/mal-.*\\.properties"));
        includes.add(Map.of("pattern", "META-INF/lal-.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/config-data/.*\\.json"));

        resources.put("includes", includes);
        resourceConfig.put("resources", resources);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(nativeImageDir.resolve("resource-config.json").toFile(), resourceConfig);
        log.info("Generated resource-config.json with {} resource patterns", includes.size());
    }

    private static Map<String, Object> fullAccessEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("allDeclaredFields", true);
        entry.put("allDeclaredMethods", true);
        entry.put("allDeclaredConstructors", true);
        return entry;
    }

    private static Map<String, Object> constructorOnlyEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("methods", List.of(Map.of("name", "<init>", "parameterTypes", List.of())));
        return entry;
    }

    /**
     * Read class names from a manifest file (one class name per line).
     */
    private static List<String> readClassNames(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Read values from a key=value manifest file. Returns sorted unique values.
     */
    private static List<String> readValueFromKeyValue(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty() && s.contains("="))
            .map(s -> s.substring(s.indexOf('=') + 1))
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Read class names from a one-per-line manifest and add constructor-only entries.
     */
    private static void addConstructorEntries(List<Map<String, Object>> entries, Path file) throws IOException {
        if (Files.exists(file)) {
            for (String className : readClassNames(file)) {
                entries.add(constructorOnlyEntry(className));
            }
        }
    }

    /**
     * Resolve the runtime classpath for javac compilation.
     * exec-maven-plugin creates a URLClassLoader with project dependencies;
     * extract URLs from it. Falls back to java.class.path system property.
     */
    @SuppressWarnings("deprecation")
    /**
     * Locate meter-analyzer-for-graalvm compiled classes directory.
     * Tries multiple strategies: basedir property (set by surefire), outputDir traversal,
     * and user.dir (CWD). Returns the directory if found, null otherwise.
     */
    private static File findGraalvmMeterClasses(String outputDir) {
        String relPath = "oap-libs-for-graalvm/meter-analyzer-for-graalvm/target/classes";

        // Strategy 1: basedir (set by surefire) → build-tools/precompiler/
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            File candidate = new File(new File(basedir).getParentFile().getParentFile(), relPath);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }

        // Strategy 2: outputDir traversal (works when outputDir = target/generated-classes)
        File candidate = new File(outputDir).getParentFile()  // target/
            .getParentFile()  // precompiler/
            .getParentFile()  // build-tools/
            .getParentFile(); // project root
        candidate = new File(candidate, relPath);
        if (candidate.isDirectory()) {
            return candidate;
        }

        // Strategy 3: user.dir may be project root
        candidate = new File(System.getProperty("user.dir"), relPath);
        if (candidate.isDirectory()) {
            return candidate;
        }

        return null;
    }

    private static String resolveClasspath() {
        Set<String> paths = new java.util.LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        while (cl != null) {
            if (cl instanceof java.net.URLClassLoader) {
                for (java.net.URL url : ((java.net.URLClassLoader) cl).getURLs()) {
                    try {
                        paths.add(new File(url.toURI()).getAbsolutePath());
                    } catch (Exception e) {
                        paths.add(url.getPath());
                    }
                }
            }
            cl = cl.getParent();
        }
        if (!paths.isEmpty()) {
            return String.join(File.pathSeparator, paths);
        }
        return System.getProperty("java.class.path", "");
    }
}
