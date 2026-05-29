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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALClassGenerator;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig;
import org.apache.skywalking.oap.server.core.config.v2.compiler.HierarchyRuleClassGenerator;
import org.yaml.snakeyaml.Yaml;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
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
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.source.ScopeDeclaration;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;

/**
 * Build-time pre-compilation tool that runs all four DSL engines (OAL, MAL, LAL, Hierarchy)
 * at build time, exports generated .class files and manifest files, and scans the classpath
 * for annotated classes and interface implementations used at runtime.
 *
 * All four DSL engines share the same pipeline:
 * DSL text → ANTLR4 parse → Immutable AST → Javassist bytecode → .class
 *
 * OAL script files and MAL/LAL/Hierarchy configs are loaded from the skywalking submodule
 * directly via additionalClasspathElements in the exec-maven-plugin configuration.
 */
@Slf4j
public class Precompiler {

    private static final String METRICS_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.metrics.";
    private static final String BUILDER_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.metrics.builder.";
    private static final String DISPATCHER_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.";
    private static final String MAL_RT_PACKAGE =
        "org.apache.skywalking.oap.meter.analyzer.v2.compiler.rt.";
    private static final String LAL_RT_PACKAGE =
        "org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.";
    private static final String HIERARCHY_RT_PACKAGE =
        "org.apache.skywalking.oap.server.core.config.v2.compiler.hierarchy.rule.rt.";

    /**
     * Discover all OALDefine subclasses on the classpath via Guava ClassPath scanning.
     * Each subclass follows the singleton pattern with a {@code public static final INSTANCE} field.
     */
    static OALDefine[] discoverOALDefines() {
        List<OALDefine> defines = new ArrayList<>();
        try {
            ClassPath classPath = ClassPath.from(Precompiler.class.getClassLoader());
            for (ClassPath.ClassInfo ci : classPath.getTopLevelClassesRecursive("org.apache.skywalking")) {
                try {
                    Class<?> clazz = ci.load();
                    if (OALDefine.class.isAssignableFrom(clazz)
                            && !Modifier.isAbstract(clazz.getModifiers())
                            && clazz != OALDefine.class) {
                        Field instanceField = clazz.getField("INSTANCE");
                        OALDefine instance = (OALDefine) instanceField.get(null);
                        defines.add(instance);
                        log.info("Discovered OALDefine: {}", clazz.getSimpleName());
                    }
                } catch (NoSuchFieldException | NoClassDefFoundError
                         | IllegalAccessException | IllegalStateException e) {
                    // Not an OALDefine singleton or can't load — skip
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan classpath for OALDefine subclasses", e);
        }
        if (defines.isEmpty()) {
            throw new IllegalStateException("No OALDefine subclasses found on classpath");
        }
        log.info("Discovered {} OALDefine subclasses", defines.size());
        return defines.toArray(new OALDefine[0]);
    }

    public static void main(String[] args) throws Exception {
        final String outputDir = args.length > 0
            ? args[0]
            : "target/generated-classes";

        log.info("Precompiler: output -> {}", outputDir);

        // Discover OALDefine subclasses from classpath and validate scripts
        OALDefine[] oalDefines = discoverOALDefines();
        validateOALScripts(oalDefines);

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

        // Run all discovered OAL defines
        for (OALDefine define : oalDefines) {
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

        // StorageBuilder scan: extract builder() class from @Stream annotations
        // These are instantiated via getDeclaredConstructor().newInstance() at runtime
        writeManifest(annotationScanDir.resolve("StorageBuilders.txt"),
            scanStorageBuilders(allClasses));

        // ---- Armeria HTTP handler scanning ----
        writeManifest(annotationScanDir.resolve("ArmeriaHandlers.txt"),
            scanArmeriaHandlers(allClasses));

        // ---- GraphQL resolver and type scanning ----
        writeManifest(annotationScanDir.resolve("GraphQLResolvers.txt"),
            scanGraphQLResolvers(allClasses));
        writeManifest(annotationScanDir.resolve("GraphQLTypes.txt"),
            scanGraphQLTypes(allClasses));

        // ---- Query plugin entity class scanning ----
        // Auto-discover all classes under org.apache.skywalking.oap.query.*.entity packages
        // (including inner classes and codec serializers) for Jackson reflection config.
        writeManifest(annotationScanDir.resolve("QueryEntityClasses.txt"),
            scanQueryEntityClasses(allClasses));

        // ---- MAL pre-compilation (v2 ANTLR4 + Javassist) ----
        compileMAL(outputDir, allClasses);

        // ---- LAL pre-compilation (v2 ANTLR4 + Javassist) ----
        compileLAL(outputDir);

        // ---- Hierarchy pre-compilation (v2 ANTLR4 + Javassist) ----
        compileHierarchy(outputDir);

        // ---- GraalVM native-image metadata generation ----
        generateNativeImageConfig(outputDir);

        log.info("Precompiler: done");
    }

    /**
     * Pre-compile all MAL (Meter Analysis Language) rules using v2 ANTLR4 + Javassist engine.
     *
     * Configures the v2 DSL's static MALClassGenerator to write .class files to the output
     * directory, then runs MetricConvert which internally calls DSL.parse() → MALClassGenerator.compile().
     * MeterSystem generates its own Javassist meter function storage classes (orthogonal to MAL).
     */
    @SuppressWarnings("unchecked")
    private static void compileMAL(String outputDir,
                                   ImmutableSet<ClassPath.ClassInfo> allClasses) throws Exception {
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

        // Configure v2 DSL and FilterExpression generators to write .class files
        // to the proper package directory structure within outputDir
        File malRtDir = new File(outputDir, MAL_RT_PACKAGE.replace('.', '/'));
        malRtDir.mkdirs();
        DSL.setClassOutputDir(malRtDir);
        FilterExpression.setClassOutputDir(malRtDir);

        // Set up MeterSystem (unchanged — Javassist meter function storage class generation)
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
        List<Rule> telegrafRules = loadAndCompileRules("telegraf-rules", List.of("*"), meterSystem);
        totalRules += telegrafRules.size();
        rulesByPath.put("telegraf-rules", telegrafRules);

        // 6. Zabbix rules (zabbix-rules/*.yaml)
        List<Rule> zabbixRules = loadAndCompileZabbixRules("zabbix-rules", meterSystem);
        totalRules += zabbixRules.size();
        rulesByPath.put("zabbix-rules", zabbixRules);

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");

        // MeterSystem exported classes (Javassist meter function storage classes)
        writeManifest(metaInf.resolve("mal-meter-classes.txt"), meterSystem.getExportedClasses());

        // Scan for v2 generated MalExpression/MalFilter .class files
        List<String> malV2Classes = scanV2Classes(outputDir, MAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("mal-v2-classes.txt"), malV2Classes);

        // Write per-file MAL manifests (organized by original YAML file structure)
        int expressionCount = writePerFileManifests(rulesByPath, metaInf);

        log.info("MAL pre-compilation: {} rules, {} meter classes, {} v2 expression classes, {} filters",
            totalRules,
            meterSystem.getExportedClasses().size(),
            expressionCount,
            FilterExpression.FILTER_MAP.size());

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
     * Pre-compile hierarchy matching rules using v2 ANTLR4 + Javassist engine.
     *
     * Loads hierarchy-definition.yml, compiles each auto-matching-rule expression
     * via HierarchyRuleClassGenerator, and writes .class files to the output directory.
     */
    @SuppressWarnings("unchecked")
    private static void compileHierarchy(String outputDir) throws Exception {
        HierarchyRuleClassGenerator hierarchyGenerator = new HierarchyRuleClassGenerator();
        File hierarchyRtDir = new File(outputDir, HIERARCHY_RT_PACKAGE.replace('.', '/'));
        hierarchyRtDir.mkdirs();
        hierarchyGenerator.setClassOutputDir(hierarchyRtDir);
        hierarchyGenerator.setYamlSource("hierarchy-definition.yml");

        // Load hierarchy-definition.yml from classpath
        try (InputStream is = Precompiler.class.getClassLoader()
                .getResourceAsStream("hierarchy-definition.yml")) {
            if (is == null) {
                log.warn("hierarchy-definition.yml not found on classpath, skipping hierarchy compilation");
                return;
            }
            Map<String, Object> yamlMap = new Yaml().load(is);
            Map<String, String> autoMatchingRules =
                (Map<String, String>) yamlMap.get("auto-matching-rules");
            if (autoMatchingRules == null || autoMatchingRules.isEmpty()) {
                log.warn("No auto-matching-rules found in hierarchy-definition.yml");
                return;
            }

            int count = 0;
            for (Map.Entry<String, String> entry : autoMatchingRules.entrySet()) {
                String ruleName = entry.getKey();
                String expression = entry.getValue();
                try {
                    hierarchyGenerator.setClassNameHint(ruleName);
                    hierarchyGenerator.compile(ruleName, expression);
                    count++;
                    log.debug("Hierarchy: compiled rule {} -> {}", ruleName, expression);
                } catch (Exception e) {
                    log.warn("Failed to compile hierarchy rule: {}", ruleName, e);
                }
            }

            // Write manifest
            Path metaInf = Path.of(outputDir, "META-INF");
            List<String> hierarchyV2Classes = scanV2Classes(outputDir, HIERARCHY_RT_PACKAGE);
            writeManifest(metaInf.resolve("hierarchy-v2-classes.txt"), hierarchyV2Classes);

            log.info("Hierarchy pre-compilation: {} rules, {} v2 classes",
                count, hierarchyV2Classes.size());
        }
    }

    /**
     * Scan output directory for v2 generated .class files matching a package prefix.
     * Converts directory structure to fully-qualified class names.
     */
    private static List<String> scanV2Classes(String outputDir, String packagePrefix) throws IOException {
        String packagePath = packagePrefix.replace('.', '/');
        Path dir = Path.of(outputDir, packagePath);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                 .filter(Files::isRegularFile)
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
     * Pre-compile all LAL (Log Analysis Language) scripts using v2 ANTLR4 + Javassist engine.
     *
     * Creates a LALClassGenerator with classOutputDir set, then compiles each LAL rule
     * directly. The v2 LAL DSL.of() creates a new generator per call, so we compile
     * directly via our own generator for .class export.
     */
    private static void compileLAL(String outputDir) throws Exception {
        LALClassGenerator lalGenerator = new LALClassGenerator();
        File lalRtDir = new File(outputDir, LAL_RT_PACKAGE.replace('.', '/'));
        lalRtDir.mkdirs();
        lalGenerator.setClassOutputDir(lalRtDir);

        // Discover LALSourceTypeProvider implementations for inputType/outputType resolution
        Map<String, LALSourceTypeProvider> spiProviders = new HashMap<>();
        java.util.ServiceLoader<LALSourceTypeProvider> providers =
            java.util.ServiceLoader.load(LALSourceTypeProvider.class);
        for (LALSourceTypeProvider provider : providers) {
            spiProviders.put(provider.layer().name(), provider);
            log.info("LAL: discovered LALSourceTypeProvider for layer {} -> inputType={}, outputType={}",
                provider.layer(), provider.inputType().getName(),
                provider.outputType() != null ? provider.outputType().getName() : "default(Log)");
        }

        // Build LALOutputBuilder short-name map from SPI (same as LogFilterListener.Factory)
        Map<String, Class<?>> outputBuilderNames = new HashMap<>();
        for (org.apache.skywalking.oap.server.core.source.LALOutputBuilder builder :
                java.util.ServiceLoader.load(org.apache.skywalking.oap.server.core.source.LALOutputBuilder.class)) {
            String name = builder.name();
            outputBuilderNames.put(name, builder.getClass());
            log.info("LAL: LALOutputBuilder registered: name={}, class={}", name, builder.getClass().getName());
        }

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

        // Load all LAL configs and compile each rule
        List<LALConfigs> allConfigs = LALConfigs.load("lal", lalFileNames);
        int totalRules = 0;

        for (LALConfigs configs : allConfigs) {
            if (configs.getRules() == null) {
                continue;
            }
            for (LALConfig rule : configs.getRules()) {
                try {
                    String yamlSource = rule.getName();
                    lalGenerator.setYamlSource(yamlSource);
                    lalGenerator.setClassNameHint(rule.getName());

                    // Resolve inputType from rule config or SPI
                    Class<?> inputType = null;
                    if (rule.getInputType() != null && !rule.getInputType().isEmpty()) {
                        try {
                            inputType = Class.forName(rule.getInputType());
                        } catch (ClassNotFoundException e) {
                            log.warn("LAL: inputType not found: {}", rule.getInputType());
                        }
                    } else if (rule.getLayer() != null) {
                        LALSourceTypeProvider spi = spiProviders.get(rule.getLayer());
                        if (spi != null) {
                            inputType = spi.inputType();
                        }
                    }
                    lalGenerator.setInputType(inputType);

                    // Resolve outputType from rule config or SPI
                    Class<?> outputType = null;
                    String yamlOutputType = rule.getOutputType();
                    if (yamlOutputType != null && !yamlOutputType.isEmpty()) {
                        // Try short name first
                        if (!yamlOutputType.contains(".")) {
                            outputType = outputBuilderNames.get(yamlOutputType);
                        }
                        // Fall back to FQCN
                        if (outputType == null) {
                            try {
                                outputType = Class.forName(yamlOutputType);
                            } catch (ClassNotFoundException e) {
                                log.warn("LAL: outputType not found: {}", yamlOutputType);
                            }
                        }
                    } else if (rule.getLayer() != null) {
                        LALSourceTypeProvider spi = spiProviders.get(rule.getLayer());
                        if (spi != null && spi.outputType() != null) {
                            outputType = spi.outputType();
                        }
                    }
                    lalGenerator.setOutputType(outputType);

                    LalExpression compiled = lalGenerator.compile(rule.getDsl());
                    totalRules++;
                    log.debug("LAL: compiled rule {} -> {}", rule.getName(),
                        compiled.getClass().getName());
                } catch (Exception e) {
                    log.warn("Failed to compile LAL rule: {}", rule.getName(), e);
                }
            }
        }

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> lalV2Classes = scanV2Classes(outputDir, LAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("lal-v2-classes.txt"), lalV2Classes);

        log.info("LAL pre-compilation: {} rules, {} v2 expression classes",
            totalRules, lalV2Classes.size());

        // ---- Serialize LAL config data as JSON for runtime loader ----
        serializeLALConfigData(outputDir, lalFileMap);
    }


    /**
     * Validate that all OAL script files referenced by the discovered OALDefines are
     * available on the classpath. Fails fast with a clear error if any are missing.
     */
    private static void validateOALScripts(OALDefine[] oalDefines) {
        ClassLoader cl = Precompiler.class.getClassLoader();
        List<String> missing = new ArrayList<>();
        for (OALDefine define : oalDefines) {
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
        log.info("Validated: all {} OAL scripts found on classpath", oalDefines.length);
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
     * Scan @Stream-annotated classes for their builder() class reference, and
     * @MeterFunction-annotated classes for their AcceptableValue.builder() return type.
     * These StorageBuilder classes are instantiated via getDeclaredConstructor().newInstance()
     * at runtime and need reflection registration for native image.
     */
    private static List<String> scanStorageBuilders(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                // @Stream-annotated classes declare builder in annotation
                if (aClass.isAnnotationPresent(org.apache.skywalking.oap.server.core.analysis.Stream.class)) {
                    org.apache.skywalking.oap.server.core.analysis.Stream stream =
                        aClass.getAnnotation(org.apache.skywalking.oap.server.core.analysis.Stream.class);
                    Class<?> builderClass = stream.builder();
                    if (builderClass != null && builderClass != void.class) {
                        result.add(builderClass.getName());
                    }
                }
                // @MeterFunction classes have StorageBuilder inner classes
                if (aClass.isAnnotationPresent(MeterFunction.class)) {
                    for (Class<?> inner : aClass.getDeclaredClasses()) {
                        if (org.apache.skywalking.oap.server.core.storage.type.StorageBuilder.class
                                .isAssignableFrom(inner)) {
                            result.add(inner.getName());
                        }
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        // Deduplicate (multiple @Stream classes may share the same builder)
        result = result.stream().distinct().sorted().collect(Collectors.toList());
        log.info("Scanned StorageBuilder classes: {} unique entries", result.size());
        return result;
    }

    /**
     * Scan for Armeria HTTP handler classes — classes with methods annotated with
     * {@code @Post}, {@code @Get}, or {@code @Path} from {@code com.linecorp.armeria.server.annotation}.
     * Also collects classes referenced in {@code @ExceptionHandler} and {@code @RequestConverter}.
     * Armeria's annotatedService().build(handler) uses reflection to discover these annotations;
     * without reflection metadata, routes are silently not registered (returning 404).
     */
    private static List<String> scanArmeriaHandlers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        // Load Armeria annotation classes if available
        Class<? extends Annotation> postAnno = loadAnnotation("com.linecorp.armeria.server.annotation.Post");
        Class<? extends Annotation> getAnno = loadAnnotation("com.linecorp.armeria.server.annotation.Get");
        Class<? extends Annotation> pathAnno = loadAnnotation("com.linecorp.armeria.server.annotation.Path");

        if (postAnno == null && getAnno == null && pathAnno == null) {
            log.warn("Armeria annotations not found on classpath, skipping HTTP handler scan");
            return Collections.emptyList();
        }

        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                    continue;
                }
                // Check if any method (including inherited) has Armeria routing annotations
                boolean matched = false;
                for (Method method : aClass.getMethods()) {
                    if ((postAnno != null && method.isAnnotationPresent(postAnno))
                        || (getAnno != null && method.isAnnotationPresent(getAnno))
                        || (pathAnno != null && method.isAnnotationPresent(pathAnno))) {
                        matched = true;
                        // Include declaring class of inherited annotated methods (e.g. abstract base handlers)
                        // so Armeria can reflect on their @Get/@Path annotations at runtime
                        Class<?> declaring = method.getDeclaringClass();
                        if (declaring != aClass && declaring != Object.class) {
                            result.add(declaring.getName());
                        }
                    }
                }
                if (matched) {
                    result.add(aClass.getName());
                    collectExceptionHandlerClasses(aClass, result);
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }

        // Also scan for ExceptionHandlerFunction implementations (instantiated by Armeria via reflection)
        Class<?> ehfInterface = loadClass("com.linecorp.armeria.server.annotation.ExceptionHandlerFunction");
        if (ehfInterface != null) {
            for (ClassPath.ClassInfo classInfo : allClasses) {
                try {
                    Class<?> aClass = classInfo.load();
                    if (!aClass.isInterface()
                        && !Modifier.isAbstract(aClass.getModifiers())
                        && ehfInterface.isAssignableFrom(aClass)) {
                        result.add(aClass.getName());
                    }
                } catch (NoClassDefFoundError | Exception ignored) {
                }
            }
        }

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned Armeria HTTP handlers: {} classes", sorted.size());
        return sorted;
    }

    /**
     * Collect classes referenced by @ExceptionHandler annotation on the handler class.
     * Also scans @RequestConverter classes referenced on methods.
     */
    private static void collectExceptionHandlerClasses(Class<?> handlerClass, Set<String> result) {
        try {
            Class<? extends Annotation> ehAnno = loadAnnotation(
                "com.linecorp.armeria.server.annotation.ExceptionHandler");
            if (ehAnno == null) {
                return;
            }
            // Check class-level @ExceptionHandler
            for (Annotation anno : handlerClass.getAnnotations()) {
                if (ehAnno.isInstance(anno)) {
                    try {
                        Method valueMethod = anno.getClass().getMethod("value");
                        Class<?>[] handlerClasses = (Class<?>[]) valueMethod.invoke(anno);
                        for (Class<?> hc : handlerClasses) {
                            result.add(hc.getName());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (NoClassDefFoundError | Exception ignored) {
        }
    }

    /**
     * Auto-discover all classes under {@code org.apache.skywalking.oap.query.*.entity} packages.
     * These are Jackson-serialized POJOs (Lombok {@code @Data}) returned by Armeria HTTP handlers
     * in query plugins (PromQL, LogQL, TraceQL, etc.). Inner static classes and codec serializers
     * are included. Enums are excluded (Jackson handles them without reflection metadata).
     *
     * <p>This replaces hardcoded class lists — new query plugins are picked up automatically.
     */
    private static List<String> scanQueryEntityClasses(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        // Match: org.apache.skywalking.oap.query.<plugin>.entity[.<sub>].<ClassName>
        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            String name = classInfo.getName();
            if (!name.startsWith("org.apache.skywalking.oap.query.")) {
                continue;
            }
            // Check that the package contains ".entity."
            String afterQuery = name.substring("org.apache.skywalking.oap.query.".length());
            if (afterQuery.indexOf(".entity.") < 0) {
                continue;
            }
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isInterface()) {
                    continue;
                }
                result.add(aClass.getName());
                // Also include inner static classes (e.g. SearchResponse$Trace, StreamLog$Result)
                for (Class<?> inner : aClass.getDeclaredClasses()) {
                    if (!inner.isInterface()) {
                        result.add(inner.getName());
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned query entity classes: {} classes", sorted.size());
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> loadAnnotation(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn);
            if (c.isAnnotation()) {
                return (Class<? extends Annotation>) c;
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        }
        return null;
    }

    /**
     * Scan for GraphQL resolver classes — implementations of
     * {@code graphql.kickstart.tools.GraphQLQueryResolver} or
     * {@code graphql.kickstart.tools.GraphQLMutationResolver}.
     * graphql-java-tools reflects on methods to map GraphQL schema operations to Java methods.
     */
    private static List<String> scanGraphQLResolvers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        Class<?> queryResolver = loadClass("graphql.kickstart.tools.GraphQLQueryResolver");
        Class<?> mutationResolver = loadClass("graphql.kickstart.tools.GraphQLMutationResolver");

        if (queryResolver == null && mutationResolver == null) {
            log.warn("GraphQL resolver interfaces not found on classpath, skipping resolver scan");
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            try {
                Class<?> aClass = classInfo.load();
                if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                    continue;
                }
                if ((queryResolver != null && queryResolver.isAssignableFrom(aClass))
                    || (mutationResolver != null && mutationResolver.isAssignableFrom(aClass))) {
                    result.add(aClass.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        Collections.sort(result);
        log.info("Scanned GraphQL resolvers: {} classes", result.size());
        return result;
    }

    /**
     * Scan GraphQL schema files (.graphqls) for type/input/enum definitions and match
     * them to Java classes on the classpath by simple name.
     * graphql-java-tools (kickstart) maps schema type names to Java class simple names;
     * reflection is used to access fields/getters for field resolution.
     */
    private static List<String> scanGraphQLTypes(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        // Build simple name → list of FQCNs index for all SkyWalking classes.
        // Multiple classes can share a simple name (e.g., AlarmMessage exists in
        // core.alarm, core.alarm.grpc, and core.query.type packages).
        // We include ALL matching classes for reflection to avoid missing the
        // graphql-java-tools type resolver's target class.
        Map<String, List<String>> simpleNameIndex = new HashMap<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            simpleNameIndex.computeIfAbsent(classInfo.getSimpleName(), k -> new ArrayList<>())
                .add(classInfo.getName());
        }

        // Parse .graphqls files from classpath
        Set<String> graphqlTypeNames = new HashSet<>();
        Pattern typePattern = Pattern.compile("^\\s*(type|input|enum)\\s+(\\w+)");

        String[] schemaFiles = listGraphQLSchemaFiles();
        for (String schemaFile : schemaFiles) {
            try (InputStream is = Precompiler.class.getClassLoader().getResourceAsStream(schemaFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = typePattern.matcher(line);
                    if (m.find()) {
                        graphqlTypeNames.add(m.group(2));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse GraphQL schema file: {}", schemaFile, e);
            }
        }

        // Remove root types that are resolvers, not DTOs
        graphqlTypeNames.remove("Query");
        graphqlTypeNames.remove("Mutation");
        graphqlTypeNames.remove("Subscription");

        // Match schema type names to Java classes (include all FQCN variants)
        List<String> result = new ArrayList<>();
        Set<String> unmatched = new HashSet<>();
        for (String typeName : graphqlTypeNames) {
            List<String> fqcns = simpleNameIndex.get(typeName);
            if (fqcns != null) {
                result.addAll(fqcns);
            } else {
                unmatched.add(typeName);
            }
        }
        Collections.sort(result);
        if (!unmatched.isEmpty()) {
            log.info("GraphQL types not matched to Java classes (may be scalars or external): {}",
                unmatched.stream().sorted().collect(Collectors.joining(", ")));
        }
        log.info("Scanned GraphQL types: {} matched from {} schema types",
            result.size(), graphqlTypeNames.size());
        return result;
    }

    /**
     * List all query-protocol/*.graphqls files from the classpath.
     * Scans both filesystem directories and JAR entries since the schema files
     * are packaged inside the query-graphql-plugin JAR.
     */
    private static String[] listGraphQLSchemaFiles() {
        Set<String> files = new HashSet<>();
        try {
            java.util.Enumeration<java.net.URL> urls =
                Precompiler.class.getClassLoader().getResources("query-protocol");
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    // Filesystem directory
                    File dir = new File(url.toURI());
                    File[] children = dir.listFiles();
                    if (children != null) {
                        for (File f : children) {
                            if (f.getName().endsWith(".graphqls")) {
                                files.add("query-protocol/" + f.getName());
                            }
                        }
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    // JAR entry: jar:file:/path/to/jar.jar!/query-protocol
                    String jarPath = url.getPath();
                    String jarFile = jarPath.substring(5, jarPath.indexOf('!'));
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith("query-protocol/") && name.endsWith(".graphqls")) {
                                files.add(name);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate query-protocol/ directory", e);
        }
        if (files.isEmpty()) {
            log.warn("No .graphqls files found in query-protocol/");
        }
        return files.toArray(new String[0]);
    }

    private static Class<?> loadClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        }
        return null;
    }

    /**
     * Write per-file MAL manifests organized by original YAML file structure.
     *
     * <p>Creates:
     * <ul>
     *   <li>{@code META-INF/mal-v2.manifest} — lists all per-file config paths</li>
     *   <li>{@code META-INF/mal-v2/{path}/{ruleName}.yaml} — per-file properties with
     *       rule names, expressions, filter, and compiled class FQCNs</li>
     * </ul>
     *
     * @return total number of expression entries written
     */
    private static int writePerFileManifests(Map<String, List<Rule>> rulesByPath,
                                             Path metaInf) throws Exception {
        Path malV2Dir = metaInf.resolve("mal-v2");
        List<String> manifestEntries = new ArrayList<>();
        int totalExpressions = 0;

        for (Map.Entry<String, List<Rule>> pathEntry : rulesByPath.entrySet()) {
            String path = pathEntry.getKey();
            for (Rule rule : pathEntry.getValue()) {
                String ruleName = rule.getName();
                String configFile = path + "/" + ruleName + ".yaml";
                manifestEntries.add(configFile);

                List<String> lines = new ArrayList<>();
                lines.add("# Source: " + configFile);

                // Filter
                String filterText = rule.getFilter();
                if (filterText != null && !filterText.isEmpty()) {
                    String filterClassName = FilterExpression.FILTER_MAP.get(filterText);
                    lines.add("filter=" + escapePropertiesValue(filterText));
                    lines.add("filter.class=" + (filterClassName != null ? filterClassName : ""));
                } else {
                    lines.add("filter=");
                    lines.add("filter.class=");
                }

                // Rules
                List<MetricsRule> metricsRules = rule.getMetricsRules();
                if (metricsRules != null) {
                    for (int i = 0; i < metricsRules.size(); i++) {
                        MetricsRule mr = metricsRules.get(i);
                        String metricName = rule.getMetricPrefix() + "_" + mr.getName();
                        // Must use the real formatExp: this text keys COMPILE_MAP at compile time
                        // and is what MetricConvert passes to DSL.parse at runtime — a replica drifts.
                        String fullExp = MetricConvert.formatExp(
                            rule.getExpPrefix(), rule.getExpSuffix(), mr.getExp());
                        String exprKey = DSL.expressionKey(fullExp, metricName);
                        String className = DSL.COMPILE_MAP.get(exprKey);

                        lines.add("rule." + i + ".name=" + metricName);
                        lines.add("rule." + i + ".exp=" + escapePropertiesValue(fullExp));
                        lines.add("rule." + i + ".class=" + (className != null ? className : ""));
                        totalExpressions++;
                    }
                }

                Path configPath = malV2Dir.resolve(configFile);
                Files.createDirectories(configPath.getParent());
                Files.write(configPath, lines, StandardCharsets.UTF_8);
            }
        }

        // Write top-level manifest listing all config files
        List<String> manifestLines = new ArrayList<>();
        manifestLines.add("# Auto-generated by Precompiler — lists all MAL per-file configs");
        manifestLines.addAll(manifestEntries);
        Files.write(metaInf.resolve("mal-v2.manifest"), manifestLines, StandardCharsets.UTF_8);

        log.info("MAL: wrote {} per-file configs with {} expressions to mal-v2/",
            manifestEntries.size(), totalExpressions);
        return totalExpressions;
    }

    private static String escapePropertiesValue(String value) {
        // Escape backslashes and newlines in property values
        return value.replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
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
     * Write a key=value properties manifest file.
     * Keys and values are escaped for Java Properties format.
     */
    private static void writePropertiesManifest(Path path, Map<String, String> map) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Auto-generated by Precompiler");
        map.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> lines.add(escapePropertiesKey(e.getKey()) + "=" + e.getValue()));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String escapePropertiesKey(String key) {
        return key.replace("\\", "\\\\")
                  .replace("=", "\\=")
                  .replace(":", "\\:")
                  .replace(" ", "\\ ");
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

        // Armeria HTTP handlers — full access (Armeria reflects on @Post/@Get/@Path method annotations)
        // Query entity classes — full access (Jackson serializes @Data POJOs in HTTP responses)
        String[] httpHandlerManifests = {
            "ArmeriaHandlers.txt", "GraphQLResolvers.txt", "GraphQLTypes.txt",
            "QueryEntityClasses.txt"
        };
        for (String manifest : httpHandlerManifests) {
            Path file = annotationScanDir.resolve(manifest);
            if (Files.exists(file)) {
                for (String className : readClassNames(file)) {
                    entries.add(fullAccessEntry(className));
                }
            }
        }

        // Config POJOs deserialized by Jackson/SnakeYAML at runtime — full access
        String[] configPojos = {
            "org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs",
            "org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig",
            "org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig",
            "org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule",
            "org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.MetricsRule",
            "org.apache.skywalking.oap.server.core.management.ui.menu.UIMenuInitializer$MenuData",
            "org.apache.skywalking.oap.server.core.management.ui.menu.UIMenuItemSetting",
            "org.apache.skywalking.oap.server.receiver.telegraf.provider.handler.pojo.TelegrafData",
            "org.apache.skywalking.oap.server.receiver.telegraf.provider.handler.pojo.TelegrafDatum",
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig",
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig$Entities",
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig$EntityLabel",
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig$Metric",
            // Zabbix protocol: Gson serializes ActiveChecks response to agent
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.protocol.bean.ZabbixResponse$ActiveChecks",
            // Zabbix protocol: Gson deserializes AgentData from agent push
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.protocol.bean.ZabbixRequest$AgentData",
            // Meter base class: attr0-attr5 fields must be discoverable via getDeclaredFields()
            // for StorageModels.retrieval() to include them in BanyanDB schemas
            "org.apache.skywalking.oap.server.core.analysis.meter.Meter",
            // GraphQL query types deserialized at runtime
            "org.apache.skywalking.oap.server.core.query.type.event.Source",
            // Searchable tag POJO used in alarm/log query results
            "org.apache.skywalking.oap.server.core.analysis.manual.searchtag.Tag",
            // Alarm snapshot: Gson serializes/deserializes at runtime
            "org.apache.skywalking.oap.server.core.alarm.AlarmSnapshotRecord",
            // Alarm webhook: Gson serializes AlarmMessage list to JSON for webhook POST
            "org.apache.skywalking.oap.server.core.alarm.AlarmMessage",
            // Alarm recovery: extends AlarmMessage with recoveryTime field, serialized by Gson
            "org.apache.skywalking.oap.server.core.alarm.AlarmRecoveryMessage",
            // LALOutputBuilder SPI: ServiceLoader instantiates to call name() for short-name resolution
            "org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.DatabaseSlowStatementBuilder",
            "org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.SampledTraceBuilder",
            "org.apache.skywalking.oap.server.receiver.envoy.persistence.EnvoyAccessLogBuilder",
            // LALSourceTypeProvider SPI: ServiceLoader instantiates for per-layer input/output type resolution
            "org.apache.skywalking.oap.server.receiver.envoy.EnvoyHTTPLALSourceTypeProvider",
            // TTL status REST endpoint: Jackson serializes TTLDefinition returned by /status/config/ttl
            "org.apache.skywalking.oap.server.core.storage.ttl.TTLDefinition"
        };
        for (String className : configPojos) {
            entries.add(fullAccessEntry(className));
        }

        // ModuleConfig subclasses from all accepted providers — full access.
        // Read from manifest generated by config-generator (see build-tools/config-generator).
        // ModuleDefine.prepare() calls type.getDeclaredConstructor().newInstance() to create config beans,
        // and YamlConfigLoaderUtils.copyProperties() populates them via setters/getters.
        try (InputStream configManifest = Precompiler.class.getResourceAsStream("/META-INF/module-config-classes.txt")) {
            if (configManifest == null) {
                throw new IllegalStateException("META-INF/module-config-classes.txt not found on classpath. "
                    + "Run config-generator first to produce it.");
            }
            List<String> moduleConfigClasses = new BufferedReader(new InputStreamReader(configManifest, StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
            log.info("Loaded {} ModuleConfig classes from manifest", moduleConfigClasses.size());
            for (String className : moduleConfigClasses) {
                entries.add(fullAccessEntry(className));
            }
        }

        // MeterFunction manifest — key=value format, full access (MeterSystem inspects annotations)
        Path meterFunctionFile = annotationScanDir.resolve("MeterFunction.txt");
        if (Files.exists(meterFunctionFile)) {
            for (String className : readValueFromKeyValue(meterFunctionFile)) {
                entries.add(fullAccessEntry(className));
            }
        }

        // StorageBuilder classes — constructor-only (instantiated via getDeclaredConstructor().newInstance())
        addConstructorEntries(entries, annotationScanDir.resolve("StorageBuilders.txt"));

        // OAL metrics and dispatchers — constructor-only
        addConstructorEntries(entries, metaInf.resolve("oal-metrics-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-dispatcher-classes.txt"));

        // MAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("mal-v2-classes.txt"));

        // LAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("lal-v2-classes.txt"));

        // Hierarchy v2 rule classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("hierarchy-v2-classes.txt"));

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
        includes.add(Map.of("pattern", "META-INF/mal-v2\\.manifest"));
        includes.add(Map.of("pattern", "META-INF/mal-v2/.*"));
        includes.add(Map.of("pattern", "META-INF/lal-.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/hierarchy-.*\\.txt"));
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
}
