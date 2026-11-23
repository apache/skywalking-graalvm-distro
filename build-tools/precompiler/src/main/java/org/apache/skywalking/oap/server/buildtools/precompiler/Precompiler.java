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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.ExecutionContext;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALCallables;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALClassGenerator;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.log.analyzer.v2.provider.log.listener.LalStaticRuleResolver;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LalInputDebugRenderer;
import org.apache.skywalking.oap.server.buildtools.common.AcceptedModules;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.TalMetrics;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixMetrics;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALFunctionRegistry;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricShapes;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.TALSourceTypeProvider;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.core.source.TALFunctionProvider;
import org.apache.skywalking.oap.server.core.config.v2.compiler.HierarchyRuleClassGenerator;
import org.apache.skywalking.oap.server.core.dsl.debug.DSLDebugCodegenSwitch;
import org.apache.skywalking.oap.server.core.dsl.DslCallables;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.DslYamlLineIndex;
import org.apache.skywalking.oap.server.core.dsl.debug.ToJson;
import org.yaml.snakeyaml.Yaml;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oal.v2.OALEngineV2;
import org.apache.skywalking.oal.v2.generator.OALClassGeneratorV2;
import org.apache.skywalking.oal.v2.parser.OALScriptParserV2;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.ISourceDecorator;
import org.apache.skywalking.oap.server.core.analysis.SourceDispatcher;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction;
import org.apache.skywalking.oap.server.core.annotation.AnnotationListener;
import org.apache.skywalking.oap.server.core.annotation.AnnotationScan;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.remote.data.StreamData;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;
import org.apache.skywalking.oap.server.core.storage.type.StorageBuilder;

import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.buildtools.common.ProtobufMessages.generatedMessage;

/**
 * Build-time pre-compilation tool that runs the five DSL engines (OAL, MAL, LAL, TAL, Hierarchy)
 * at build time, exports generated .class files and manifest files, and scans the classpath
 * for annotated classes and interface implementations used at runtime.
 *
 * The DSL engines share the same pipeline:
 * DSL text → ANTLR4 parse → Immutable AST → Javassist bytecode → .class
 *
 * The rule files are read from the staged rule tree ({@link RulesStager}: upstream's server-starter resources with
 * the rules overlay merged in), on the classpath through additionalClasspathElements.
 */
@Slf4j
public class Precompiler {

    private static final String MAL_RT_PACKAGE =
        "org.apache.skywalking.oap.meter.analyzer.v2.compiler.rt.";
    private static final String LAL_RT_PACKAGE =
        "org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.";
    private static final String TAL_RT_PACKAGE =
        "org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt.";
    private static final String HIERARCHY_RT_PACKAGE =
        "org.apache.skywalking.oap.server.core.config.v2.compiler.hierarchy.rule.rt.";

    /**
     * Discover all OALDefine subclasses on the classpath via Guava ClassPath scanning.
     */
    static OALDefine[] discoverOALDefines() {
        final List<OALDefine> defines = new ArrayList<>();
        final ImmutableSet<ClassPath.ClassInfo> classes;
        try {
            classes = ClassPath.from(Precompiler.class.getClassLoader())
                               .getTopLevelClassesRecursive("org.apache.skywalking");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan the classpath for OALDefine subclasses", e);
        }
        for (final ClassPath.ClassInfo info : classes) {
            final Class<?> clazz = info.load();
            if (OALDefine.class.isAssignableFrom(clazz) && !Modifier.isAbstract(clazz.getModifiers())) {
                defines.add(defineOf(clazz));
                log.info("Discovered OALDefine: {}", clazz.getSimpleName());
            }
        }
        if (defines.isEmpty()) {
            throw new IllegalStateException("No OALDefine subclasses found on classpath");
        }
        log.info("Discovered {} OALDefine subclasses", defines.size());
        return defines.toArray(new OALDefine[0]);
    }

    /**
     * @return the define's {@code public static INSTANCE}, the one object of it providers load
     */
    static OALDefine defineOf(final Class<?> defineClass) {
        try {
            final Field instance = defineClass.getField("INSTANCE");
            if (Modifier.isStatic(instance.getModifiers()) && defineClass.isInstance(instance.get(null))) {
                return (OALDefine) instance.get(null);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(notASingleton(defineClass), e);
        }
        throw new IllegalStateException(notASingleton(defineClass));
    }

    private static String notASingleton(final Class<?> defineClass) {
        return "OALDefine " + defineClass.getName() + " has no public static INSTANCE of its own type: the build "
            + "compiles each define's script through that instance";
    }

    public static void main(String[] args) throws Exception {
        final String outputDir = args.length > 0
            ? args[0]
            : "target/generated-classes";
        final Path registrySources = args.length > 1
            ? Path.of(args[1])
            : Files.createTempDirectory("compiled-rules-src");

        log.info("Precompiler: output -> {}", outputDir);
        // The manifests list every class in the directory: a class a rule no longer compiles must not linger.
        deleteRecursively(Path.of(outputDir));

        // Discover OALDefine subclasses from classpath and validate scripts
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        OALDefine[] oalDefines = effectiveOALDefines(discoverOALDefines(), overlay);
        validateOALScripts(oalDefines);
        requireEveryScriptCompiled(oalDefines, stagedOalScripts());

        declareScopes();
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

        // Same switch upstream's DSLDebuggingModuleProvider.prepare() flips at boot: every OAL / MAL /
        // LAL generator then emits the GateHolder field and probe call sites, so the pre-compiled
        // classes are debuggable by the dsl-debugging module at runtime (upstream has it on by default).
        DSLDebugCodegenSwitch.enableInjection();

        // Run every OAL define as upstream's loader does, recording what its engine hands the listeners and what the
        // script disables (oal-scripts.txt, which the rule registry's per-script classes are generated from)
        final Properties oalScripts = new Properties();
        final Set<String> metricsClasses = new TreeSet<>();
        final Set<String> dispatcherClasses = new TreeSet<>();
        final Set<String> builderClasses = new TreeSet<>();
        final Set<String> disabledSources = new TreeSet<>();
        for (int i = 0; i < oalDefines.length; i++) {
            final OALDefine define = oalDefines[i];
            log.info("Processing: {}", define.getConfigFile());
            final OalListenerRecorder recorder = new OalListenerRecorder();
            OALEngineV2 engine = new OALEngineV2(define);
            engine.getClassGeneratorV2().setOpenEngineDebug(true);
            // The factory the shipped storage registers (ShippedStorageBuilderFactoryTest)
            engine.setStorageBuilderFactory(new StorageBuilderFactory.Default());
            engine.setStreamListener(recorder);
            engine.setDispatcherListener(recorder);
            try {
                engine.start(Precompiler.class.getClassLoader());
            } catch (Exception | LinkageError e) {
                if (define instanceof OverlayOALDefine) {
                    throw new IllegalStateException("The overlay OAL script " + define.getConfigFile() + " does not "
                        + "compile. An overlay script may use the sources of " + OverlayOALDefine.SOURCE_PACKAGE
                        + " only, and define no metric another script defines", e);
                }
                throw e;
            }
            engine.notifyAllListeners();
            final List<String> disabled = scriptDisables(define);
            final String prefix = "script." + i + ".";
            // An overlay's own script has no define class: the registry recreates its define from these.
            oalScripts.setProperty(prefix + "define", define instanceof OverlayOALDefine ? "" : define.getClass().getName());
            oalScripts.setProperty(prefix + "file", define.getConfigFile());
            oalScripts.setProperty(prefix + "sourcePackage", define.getSourcePackage());
            oalScripts.setProperty(prefix + "dispatcherPackage", define.getDynamicDispatcherClassPackage());
            oalScripts.setProperty(prefix + "metrics", String.join(",", recorder.getMetrics()));
            oalScripts.setProperty(prefix + "dispatchers", String.join(",", recorder.getDispatchers()));
            oalScripts.setProperty(prefix + "disabled", String.join(",", disabled));
            metricsClasses.addAll(recorder.getMetrics());
            dispatcherClasses.addAll(recorder.getDispatchers());
            builderClasses.addAll(recorder.getBuilders());
            disabledSources.addAll(disabled);
        }
        oalScripts.setProperty("excluded", overlay.excluded().stream().filter(f -> f.startsWith("oal/"))
                                                  .collect(Collectors.joining(",")));
        // No stream is defined at build time, so these are all the disables upstream's generators registered.
        if (!DisableRegister.INSTANCE.scriptDisablesWithoutStream().equals(disabledSources)) {
            throw new IllegalStateException("The OAL generators registered the disables "
                + DisableRegister.INSTANCE.scriptDisablesWithoutStream() + ", the scripts name " + disabledSources);
        }

        // Write manifest files
        Path metaInf = Path.of(outputDir, "META-INF");
        Files.createDirectories(metaInf);
        writeManifest(metaInf.resolve("oal-metrics-classes.txt"), List.copyOf(metricsClasses));
        writeManifest(metaInf.resolve("oal-dispatcher-classes.txt"), List.copyOf(dispatcherClasses));
        writeManifest(metaInf.resolve("oal-builder-classes.txt"), List.copyOf(builderClasses));
        writeManifest(metaInf.resolve("oal-disabled-sources.txt"), List.copyOf(disabledSources));
        writeEscapedProperties(metaInf.resolve("oal-scripts.txt"), oalScripts);

        log.info("Precompiler: {} metrics, {} dispatchers, {} disabled sources",
            metricsClasses.size(), dispatcherClasses.size(), disabledSources.size());

        // ---- Annotation & interface scanning for hardcoded classes ----
        Path annotationScanDir = metaInf.resolve("annotation-scan");
        Files.createDirectories(annotationScanDir);

        ImmutableSet<ClassPath.ClassInfo> allClasses = ClassPath
            .from(Precompiler.class.getClassLoader())
            .getTopLevelClassesRecursive("org.apache.skywalking");

        // The class lists AnnotationScan and SourceReceiverImpl read at boot: one per annotation an AnnotationListener
        // listens for, and the decorators and dispatchers
        List<String> scannedClassManifests = new ArrayList<>();
        for (Class<? extends Annotation> annotation : listenedAnnotations(allClasses)) {
            String manifest = annotation.getSimpleName() + ".txt";
            writeManifest(annotationScanDir.resolve(manifest), scanAnnotation(allClasses, annotation));
            scannedClassManifests.add(manifest);
        }
        writeManifest(annotationScanDir.resolve("SourceDispatcher.txt"),
            scanImplementations(allClasses, SourceDispatcher.class));
        writeManifest(annotationScanDir.resolve("ISourceDecorator.txt"),
            scanImplementations(allClasses, ISourceDecorator.class));
        scannedClassManifests.add("SourceDispatcher.txt");
        scannedClassManifests.add("ISourceDecorator.txt");

        // The meter functions, for the build-time MeterSystem and, through the rule registry, the runtime one
        final Map<String, Class<? extends AcceptableValue>> meterFunctions = meterFunctionRegister(allClasses);
        writeManifest(annotationScanDir.resolve("MeterFunction.txt"), meterFunctions.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue().getName()).collect(Collectors.toList()));

        // The rest are build intermediates of the reflection metadata, which the generated jar leaves out.
        // StorageBuilder scan: extract builder() class from @Stream annotations
        // These are instantiated via getDeclaredConstructor().newInstance() at runtime
        writeManifest(annotationScanDir.resolve("StorageBuilders.txt"),
            scanStorageBuilders(allClasses));
        // RemoteHandleWorker rebuilds the stream data another OAP node sends with newInstance(), of any class a module
        // registers through IWorkerInstanceSetter, an @Stream class or not
        writeManifest(annotationScanDir.resolve("StreamData.txt"), scanStreamData(allClasses));

        // ---- Armeria HTTP handler scanning ----
        final List<String> armeriaHandlers = scanArmeriaHandlers(allClasses);
        writeManifest(annotationScanDir.resolve("ArmeriaHandlers.txt"), armeriaHandlers);
        writeManifest(annotationScanDir.resolve("HttpBodyTypes.txt"), scanHttpBodyTypes(armeriaHandlers));

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

        // ---- layer-extensions.yml and the LayerExtension SPI, as Core registers them before any rule loads ----
        LayerExtensionLoader.load();
        // Core's hierarchy service checks the hierarchy now, before any rule file's own layers register.
        checkHierarchy(RuleCatalog.HIERARCHY_DEFINITION);

        // ---- MAL pre-compilation (v2 ANTLR4 + Javassist) ----
        MeterSystem meterSystem = compileMAL(outputDir, meterFunctions);

        // ---- Every TAL file's inline layers before LAL checks rules against the registered layers: at boot the
        // module start order decides which rule file's layers a LAL rule sees, so the image takes them all. A file
        // requiring a module the image does not ship is skipped first, as upstream skips it, its layers unregistered ----
        ModuleManager shipped = shippedModules();
        final String talPath = RuleCatalog.TAL.getDirectory();
        for (File f : filesOf(talPath)) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                TALConfigs.load(talPath, List.of(getNameWithoutExtension(f.getName())), shipped);
            }
        }

        // ---- LAL pre-compilation (v2 ANTLR4 + Javassist) ----
        compileLAL(outputDir, RuleCatalog.LAL.getDirectory());
        exportOalScripts(outputDir, oalDefines);
        exportRuleSources(outputDir, RuleCatalog.mergedByLoader().stream().filter(c -> c != RuleCatalog.TAL)
                                                .collect(Collectors.toList()));

        // ---- TAL pre-compilation (v2 ANTLR4 + Javassist); its metrics share MAL's MeterSystem ----
        compileTAL(outputDir, meterSystem, talPath, shipped);
        exportRuleSources(outputDir, List.of(RuleCatalog.TAL));

        // ---- Hierarchy pre-compilation (v2 ANTLR4 + Javassist) ----
        compileHierarchy(outputDir, RuleCatalog.HIERARCHY_DEFINITION);

        // ---- The static rule registry the runtime reads instead of rule files and the manifests above ----
        RegistryGenerator.generate(Path.of(outputDir), registrySources);

        // ---- GraalVM native-image metadata generation ----
        generateNativeImageConfig(outputDir, scannedClassManifests);

        log.info("Precompiler: done");
    }

    /**
     * Pre-compile all MAL (Meter Analysis Language) rules using v2 ANTLR4 + Javassist engine.
     *
     * Configures the v2 DSL's static MALClassGenerator to write .class files to the output
     * directory, then runs MetricConvert which internally calls DSL.parse() → MALClassGenerator.compile().
     * MeterSystem generates its own Javassist meter function storage classes (orthogonal to MAL).
     */
    private static MeterSystem compileMAL(String outputDir,
                                          Map<String, Class<? extends AcceptableValue>> functionRegister)
        throws Exception {
        log.info("MAL: built function register with {} entries", functionRegister.size());

        // Configure v2 DSL and FilterExpression generators to write .class files
        // to the proper package directory structure within outputDir
        File malRtDir = new File(outputDir, MAL_RT_PACKAGE.replace('.', '/'));
        malRtDir.mkdirs();
        DSL.setClassOutputDir(malRtDir);
        FilterExpression.setClassOutputDir(malRtDir);
        DSL.clearCompiled();
        FilterExpression.clearCompiled();

        MeterSystem.setFunctionRegister(functionRegister);
        MeterSystem.setOutputDirectory(outputDir);

        MeterSystem meterSystem = new MeterSystem(null);
        int totalRules = 0;
        Map<String, List<Rule>> rulesByPath = new LinkedHashMap<>();
        for (RuleCatalog catalog : RuleCatalog.malRules()) {
            String path = catalog.getDirectory();
            List<Rule> rules = loadAndCompileRules(path, ruleNames(path), meterSystem);
            totalRules += rules.size();
            rulesByPath.put(path, rules);
        }
        // Zabbix rules, which the registry carries as ZabbixConfigs
        totalRules += compileZabbixRules(RuleCatalog.ZABBIX_RULES.getDirectory(), meterSystem);

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");

        // MeterSystem exported classes (Javassist meter function storage classes)
        writeManifest(metaInf.resolve("mal-meter-classes.txt"), meterSystem.getExportedClasses());

        // Scan for v2 generated MalExpression/MalFilter .class files
        List<String> malV2Classes = scanV2Classes(outputDir, MAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("mal-v2-classes.txt"), malV2Classes);

        writeMalRules(metaInf);

        log.info("MAL pre-compilation: {} rules, {} meter classes, {} v2 expression classes, {} filters",
            totalRules,
            meterSystem.getExportedClasses().size(),
            DSL.COMPILED.size(),
            FilterExpression.COMPILED.size());

        // ---- Serialize config data as JSON, which the rule registry is generated from ----
        serializeMALConfigData(outputDir, rulesByPath);
        return meterSystem;
    }

    /**
     * Every rule file of a catalog by name (its path in the catalog without extension), enumerated so that a catalog
     * an overlay emptied compiles to nothing: upstream's loader fails on a pattern that matches no file. A file that
     * yields no rule is one too: upstream's loader reads it, and its setting lists it.
     */
    static List<String> ruleNames(final String catalog) throws IOException {
        return rulePaths(catalog).stream().map(rel -> rel.substring(0, rel.lastIndexOf('.'))).collect(Collectors.toList());
    }

    /**
     * @return every rule file of a catalog, by its path in the catalog, as the rules stager staged them (YAML files,
     *     below the directory where its loader walks it)
     */
    static List<String> rulePaths(final String catalog) throws IOException {
        final Path dir = existingDirectory(catalog);
        if (dir == null) {
            return new ArrayList<>();
        }
        final boolean recursive = RuleCatalog.ofDirectory(catalog)
            .orElseThrow(() -> new IllegalArgumentException("Not a rule directory: " + catalog)).isRecursive();
        try (Stream<Path> files = Files.walk(dir, recursive ? Integer.MAX_VALUE : 1)) {
            return files.filter(Files::isRegularFile)
                        .map(p -> dir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .filter(rel -> !rel.startsWith(".") && (rel.endsWith(".yaml") || rel.endsWith(".yml")))
                        .sorted()
                        .collect(Collectors.toList());
        }
    }

    /**
     * @return the rule directory on the classpath, or null when there is none (an overlay excluded every file of it)
     */
    private static Path existingDirectory(final String path) throws IOException {
        final URL url = Precompiler.class.getClassLoader().getResource(path);
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            final Path dir = Path.of(url.toURI());
            return Files.isDirectory(dir) ? dir : null;
        } catch (URISyntaxException e) {
            throw new IOException("Unreadable rule directory " + url, e);
        }
    }

    private static File[] filesOf(final String path) throws IOException {
        return existingDirectory(path) == null ? new File[0] : ResourceUtils.getPathFiles(path);
    }

    /**
     * The OAL defines the build compiles: the discovered ones, minus those whose script the overlay excluded, plus one
     * for every script the overlay added.
     */
    static OALDefine[] effectiveOALDefines(final OALDefine[] discovered, final OverlayInventory overlay) {
        final List<OALDefine> defines = new ArrayList<>();
        for (final OALDefine define : discovered) {
            if (overlay.excluded().contains(define.getConfigFile())) {
                log.info("OAL: {} excluded by the rules overlay", define.getConfigFile());
            } else {
                defines.add(define);
            }
        }
        final Set<String> referenced = Arrays.stream(discovered).map(OALDefine::getConfigFile)
                                             .collect(Collectors.toSet());
        for (final String added : overlay.added()) {
            if (added.startsWith("oal/") && !referenced.contains(added)) {
                defines.add(new OverlayOALDefine(added));
            }
        }
        // Bundled scripts share the default dispatcher package; an added script's must be its own.
        final Map<String, String> dispatcherPackages = new HashMap<>();
        for (final OALDefine define : defines) {
            if (!(define instanceof OverlayOALDefine)) {
                dispatcherPackages.putIfAbsent(define.getDynamicDispatcherClassPackage().toLowerCase(Locale.ROOT),
                    define.getConfigFile());
            }
        }
        for (final OALDefine define : defines) {
            if (define instanceof OverlayOALDefine) {
                // By case too: the dispatchers are class files, one file on a case-insensitive disk.
                final String previous = dispatcherPackages.put(
                    define.getDynamicDispatcherClassPackage().toLowerCase(Locale.ROOT), define.getConfigFile());
                if (previous != null) {
                    throw new IllegalStateException("The OAL scripts " + previous + " and " + define.getConfigFile()
                        + " would name their dispatchers alike; rename the overlay script");
                }
            }
        }
        return defines.toArray(new OALDefine[0]);
    }

    /**
     * The define of an OAL script the overlay adds: its dispatchers are named {@code Overlay<script>_<Source>Dispatcher},
     * so they never clash with another script's dispatchers of the same source. The trailing separator keeps two
     * scripts apart whose name and source run into each other ({@code fleet} + {@code CiliumService},
     * {@code fleet-cilium} + {@code Service}).
     */
    static final class OverlayOALDefine extends OALDefine {
        static final String SOURCE_PACKAGE = "org.apache.skywalking.oap.server.core.source";

        OverlayOALDefine(final String configFile) {
            super(configFile, SOURCE_PACKAGE, catalog(configFile));
        }

        static String catalog(final String configFile) {
            final String name = configFile.substring(configFile.lastIndexOf('/') + 1, configFile.lastIndexOf('.'));
            final StringBuilder catalog = new StringBuilder("Overlay");
            // Letters and digits as they are, '_', '-' and '.' (all an overlay name has besides) as _u, _m and _p: two
            // names stay two prefixes, and a dispatcher's class file name stays short. The 'D' of a "Dispatcher" as
            // _d: the rule browser takes a dispatcher's source from its simple name up to the first "Dispatcher".
            for (int i = 0; i < name.length(); i++) {
                final char c = name.charAt(i);
                if (name.startsWith("Dispatcher", i)) {
                    catalog.append("_d");
                } else if (c < 128 && Character.isLetterOrDigit(c)) {
                    catalog.append(c);
                } else if (c == '_') {
                    catalog.append("_u");
                } else if (c == '-') {
                    catalog.append("_m");
                } else if (c == '.') {
                    catalog.append("_p");
                } else {
                    throw new IllegalStateException("OAL script " + configFile + " has a character the stager refuses");
                }
            }
            return catalog.append('_').toString();
        }
    }

    /**
     * The {@code @MeterFunction} classes by function name, as upstream {@code MeterSystem}'s constructor registers
     * them, and failing as it does on one that is no {@code AcceptableValue}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Class<? extends AcceptableValue>> meterFunctionRegister(
        Iterable<ClassPath.ClassInfo> classes) {
        Map<String, Class<? extends AcceptableValue>> functionRegister = new TreeMap<>();
        for (ClassPath.ClassInfo classInfo : classes) {
            Class<?> aClass = classInfo.load();
            if (aClass.isAnnotationPresent(MeterFunction.class)) {
                MeterFunction mf = aClass.getAnnotation(MeterFunction.class);
                if (!AcceptableValue.class.isAssignableFrom(aClass)) {
                    throw new IllegalArgumentException(
                        "Function " + aClass.getCanonicalName() + " doesn't implement AcceptableValue.");
                }
                functionRegister.put(mf.functionName(), (Class<? extends AcceptableValue>) aClass);
            }
        }
        return functionRegister;
    }

    /**
     * Load rules from a resource directory and compile them through the MAL pipeline.
     * Returns the loaded rules for config data serialization. A file that does not load or a rule that does not
     * compile fails the build: the image would boot without it, where upstream fails to boot.
     */
    static List<Rule> loadAndCompileRules(String path, List<String> enabledPatterns,
                                                  MeterSystem meterSystem) {
        if (enabledPatterns.isEmpty()) {
            log.info("MAL: no rule files under {}", path);
            return new ArrayList<>();
        }
        List<Rule> loadedRules;
        try {
            loadedRules = Rules.loadRules(path, enabledPatterns);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load MAL rules from " + path, e);
        }
        for (Rule rule : loadedRules) {
            try {
                new MetricConvert(rule, meterSystem);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to compile MAL rule " + rule.getName() + " of " + path, e);
            }
        }
        log.info("MAL: compiled {} rules from {}", loadedRules.size(), path);
        return loadedRules;
    }

    /**
     * Compiles the Zabbix rule files as the Zabbix receiver does at boot: upstream's loader reads them and upstream's
     * {@code ZabbixMetrics} initialises its host groups over them, compiling each file's expressions once a host
     * pattern of it reaches them (once per coordinate and text, as the runtime registry holds them). A file without a
     * host pattern compiles nothing; a host pattern that is no regular expression fails the build, as it fails the
     * boot.
     *
     * @return the number of rule files
     */
    static int compileZabbixRules(final String path, final MeterSystem meterSystem) throws IOException {
        return compileZabbixRules(path, ruleNames(path), meterSystem);
    }

    static int compileZabbixRules(final String path, final List<String> names, final MeterSystem meterSystem) {
        final List<ZabbixConfig> configs;
        try {
            configs = ZabbixConfigs.loadConfigs(path, names);
        } catch (ModuleStartException e) {
            throw new IllegalStateException("Failed to load Zabbix rules from " + path, e);
        }
        try {
            new ZabbixMetrics(configs, meterSystem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise the Zabbix rules of " + path + " as the Zabbix "
                + "receiver does", e);
        }
        log.info("MAL: compiled {} rules from {}", configs.size(), path);
        return configs.size();
    }

    /**
     * What Core's {@code HierarchyDefinitionService} checks at boot, before any rule file's own layers register: every
     * layer of {@code layer-levels} is registered, and every layer of {@code hierarchy} has a level above each of its
     * lower layers.
     */
    @SuppressWarnings("unchecked")
    static void checkHierarchy(String definitionFile) throws Exception {
        final Map<String, Object> definition;
        try (InputStream is = Precompiler.class.getClassLoader().getResourceAsStream(definitionFile)) {
            definition = new Yaml().load(is);
        }
        final Map<String, Object> levels = definition.get("layer-levels") instanceof Map
            ? (Map<String, Object>) definition.get("layer-levels") : Map.of();
        for (final Map.Entry<String, Object> level : levels.entrySet()) {
            if (Layer.nameOf(level.getKey()) == Layer.UNDEFINED) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + level.getKey() + " is not a "
                    + "registered layer; declare it in layer-extensions.yml");
            }
        }
        final Map<String, Object> hierarchy = definition.get("hierarchy") instanceof Map
            ? (Map<String, Object>) definition.get("hierarchy") : Map.of();
        for (final Map.Entry<String, Object> upper : hierarchy.entrySet()) {
            final Object upperLevel = levels.get(upper.getKey());
            if (upperLevel == null) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + upper.getKey() + " is not defined");
            }
            // The hierarchy service reads the level of each layer here, upper or lower, as an integer.
            if (!(upperLevel instanceof Integer)) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + upper.getKey()
                    + " is not an integer level: " + upperLevel);
            }
            if (!(upper.getValue() instanceof Map)) {
                throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey()
                    + " does not map its lower layers to matching rules");
            }
            final Map<String, Object> lowers = (Map<String, Object>) upper.getValue();
            for (final Map.Entry<String, Object> lower : lowers.entrySet()) {
                final Object lowerLevel = levels.get(lower.getKey());
                if (lowerLevel == null) {
                    throw new IllegalStateException(definitionFile + " layer-levels: " + lower.getKey()
                        + " is not defined");
                }
                if (!(lowerLevel instanceof Integer)) {
                    throw new IllegalStateException(definitionFile + " layer-levels: " + lower.getKey()
                        + " is not an integer level: " + lowerLevel);
                }
                // A matching rule's name, or none: upstream relates the two layers without automatic matching then.
                if (lower.getValue() != null && !(lower.getValue() instanceof String)) {
                    throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey() + "."
                        + lower.getKey() + " does not name a matching rule: " + lower.getValue());
                }
                if ((Integer) upperLevel <= (Integer) lowerLevel) {
                    throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey()
                        + " layer-level should be greater than " + lower.getKey() + " layer-level");
                }
            }
        }
    }

    /**
     * Pre-compile hierarchy matching rules using v2 ANTLR4 + Javassist engine.
     *
     * Loads hierarchy-definition.yml, compiles each auto-matching-rule expression
     * via HierarchyRuleClassGenerator, and writes .class files to the output directory.
     */
    @SuppressWarnings("unchecked")
    static void compileHierarchy(String outputDir, String definitionFile) throws Exception {
        HierarchyRuleClassGenerator hierarchyGenerator = new HierarchyRuleClassGenerator();
        File hierarchyRtDir = new File(outputDir, HIERARCHY_RT_PACKAGE.replace('.', '/'));
        hierarchyRtDir.mkdirs();
        hierarchyGenerator.setClassOutputDir(hierarchyRtDir);

        // Upstream HierarchyDefinitionService fails the boot without the file or its auto-matching-rules section.
        try (InputStream is = Precompiler.class.getClassLoader().getResourceAsStream(definitionFile)) {
            if (is == null) {
                throw new IllegalStateException(definitionFile + " is not on the classpath");
            }
            final String yamlText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> yamlMap = new Yaml().load(yamlText);
            // Per-rule lines, as upstream CompiledHierarchyRuleProvider stamps them, so class
            // names ({file}_L{line}_{rule}) match the JVM distro.
            final Map<String, Integer> ruleLines =
                DslYamlLineIndex.keyLines(yamlText, "auto-matching-rules");
            final Properties hierarchyRules = new Properties();
            if (yamlMap == null || !(yamlMap.get("auto-matching-rules") instanceof Map)) {
                throw new IllegalStateException(definitionFile + " has no auto-matching-rules section");
            }
            // An empty section is valid: the manifests are written empty, as there are no rules to load.
            Map<String, String> autoMatchingRules =
                (Map<String, String>) yamlMap.get("auto-matching-rules");

            int count = 0;
            for (Map.Entry<String, String> entry : autoMatchingRules.entrySet()) {
                String ruleName = entry.getKey();
                String expression = entry.getValue();
                try {
                    final Integer line = ruleLines.get(ruleName);
                    hierarchyGenerator.setSourceRef(DslSourceRef.ofRule(
                        "hierarchy-definition.yml", line == null ? 0 : line));
                    hierarchyGenerator.setClassNameHint(ruleName);
                    final Object compiled = hierarchyGenerator.compile(ruleName, expression);
                    GeneratedInstanceCheck.requireNoStateAfterConstruction(
                        compiled, Set.of(), "Hierarchy rule " + ruleName);
                    hierarchyRules.setProperty(ruleName, compiled.getClass().getName());
                    count++;
                    log.debug("Hierarchy: compiled rule {} -> {}", ruleName, expression);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to compile hierarchy rule " + ruleName, e);
                }
            }

            // Write manifest
            Path metaInf = Path.of(outputDir, "META-INF");
            List<String> hierarchyV2Classes = scanV2Classes(outputDir, HIERARCHY_RT_PACKAGE);
            writeManifest(metaInf.resolve("hierarchy-v2-classes.txt"), hierarchyV2Classes);
            // Rule name to class, escaped: a rule may be named anything upstream accepts
            writeEscapedProperties(metaInf.resolve("hierarchy-v2-rules.txt"), hierarchyRules);

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
     * Pre-compile every LAL rule file under {@code lalPath} with the v2 engine, exporting each class. Upstream's
     * {@code DSL.of} makes a generator per call with no class output, so this pass drives one generator with what
     * upstream's {@code LogFilterListener.Factory} passes it: the rule's {@link DslSourceRef}, its text, and the input
     * and output types the factory resolves ({@link LalStaticRuleResolver}).
     *
     * <p>{@code lal-v2-rules.txt} carries, per rule, the coordinates, the class, the effective input type and what the
     * class was compiled from; {@code lal-io-classes.txt} the {@code LALSourceTypeProvider} and {@code LALOutputBuilder}
     * implementations ServiceLoader instantiates; {@code lal-input-types.txt} every type a rule reads values from,
     * which the reflection metadata walks.
     */
    static void compileLAL(String outputDir, String lalPath) throws Exception {
        LALClassGenerator lalGenerator = new LALClassGenerator();
        File lalRtDir = new File(outputDir, LAL_RT_PACKAGE.replace('.', '/'));
        lalRtDir.mkdirs();
        lalGenerator.setClassOutputDir(lalRtDir);
        LalStaticRuleResolver boot = new LalStaticRuleResolver();

        // Enumerate all LAL YAML files from classpath
        File[] lalFiles = filesOf(lalPath);
        Map<String, File> lalFileMap = new LinkedHashMap<>();
        for (File f : lalFiles) {
            String name = f.getName();
            if (f.isFile() && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                lalFileMap.put(name.substring(0, name.lastIndexOf('.')), f);
            }
        }

        // Loaded a file at a time, so each file's configs are kept by its name, a file of an empty rule list too: its
        // layerDefinitions are registered when the runtime loads it, as upstream does for every listed file.
        Map<String, LALConfigs> byFile = new LinkedHashMap<>();
        for (Map.Entry<String, File> file : lalFileMap.entrySet()) {
            for (LALConfigs configs : LALConfigs.load(lalPath, List.of(file.getKey()))) {
                byFile.put(file.getValue().getName(), configs);
            }
        }
        // In the order one upstream load over every file returns them, as the log analyzer loads its lalFiles (all of
        // them): the order their rules run on a log, which the registry keeps and the boot returns them in
        Map<String, LALConfigs> configsByFile = new LinkedHashMap<>();
        for (LALConfigs configs : LALConfigs.load(lalPath, new ArrayList<>(lalFileMap.keySet()))) {
            // No stamped source names a file without rules: files of one content are interchangeable
            String file = configs.getRules().isEmpty()
                ? byFile.entrySet().stream()
                        .filter(e -> !configsByFile.containsKey(e.getKey())
                            && StateComparison.firstDifference(configs, e.getValue(), "configs") == null)
                        .map(Map.Entry::getKey).findFirst().orElse(null)
                : configs.getRules().get(0).getSourceName();
            if (file == null || !byFile.containsKey(file) || configsByFile.put(file, byFile.get(file)) != null) {
                throw new IllegalStateException("Upstream's LAL loader returned a file of " + lalPath + " the LAL "
                    + "pass did not load alone: " + (file == null ? configs : file));
            }
        }
        if (!configsByFile.keySet().equals(byFile.keySet())) {
            throw new IllegalStateException("Upstream's LAL loader returned the files " + configsByFile.keySet()
                + " of " + lalPath + " loading them together, " + byFile.keySet() + " one at a time");
        }

        int totalRules = 0;
        Properties lalRules = new Properties();
        Set<String> inputTypes = new TreeSet<>();
        final Set<List<String>> registered = new HashSet<>();
        // A rule without a name is compiled, as upstream compiles it: keys keep it apart from one named "null"
        final Set<List<String>> compiledFrom = new HashSet<>();
        for (Map.Entry<String, LALConfigs> file : configsByFile.entrySet()) {
            for (LALConfig rule : file.getValue().getRules()) {
                if (!checkRegistration(rule, registered)) {
                    continue;
                }
                // The DslSourceRef upstream's factory passes: its coordinates name the class ({yaml}_L{line}_{rule},
                // as the JVM OAP names it) and key it for the runtime DSL, an unresolved line included.
                final DslSourceRef sourceRef = DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo());
                try {
                    final Class<?> inputType = boot.inputType(rule);
                    final Class<?> outputType = boot.outputType(rule);
                    final String dslSha256 = sha256Hex(rule.getDsl().getBytes(StandardCharsets.UTF_8));
                    // The runtime DSL takes the class compiled at the rule's coordinates from the name, text and types
                    // upstream passes: two rules agreeing on all of them (one line, two layers) compile to the same class,
                    // the layer being no input of it, so both run instances of one class, as upstream runs two alike.
                    if (!compiledFrom.add(Arrays.asList(sourceRef.describeYaml(), rule.getName(), dslSha256,
                        typeName(inputType), typeName(outputType)))) {
                        log.info("LAL rule {} of layer {} at {} is compiled as the rule of its name, text and types "
                            + "on the same line", rule.getName(), rule.getLayer(), sourceRef.describeYaml());
                        continue;
                    }
                    lalGenerator.setSourceRef(sourceRef);
                    lalGenerator.setClassNameHint(rule.getName());
                    // The holder's content, shown inline by the debugger (upstream DSL.of does the same).
                    lalGenerator.setContent(rule.getDsl());
                    lalGenerator.setInputType(inputType);
                    lalGenerator.setOutputType(outputType);

                    LalExpression compiled = lalGenerator.compile(rule.getDsl());
                    GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled, Set.of(),
                        "LAL rule " + rule.getName() + " (" + sourceRef.describeYaml() + ")");
                    final Class<?> effectiveInputType = lalGenerator.getEffectiveInputType();
                    final int idx = totalRules++;
                    lalRules.setProperty("rule." + idx + ".source", sourceRef.getYamlFile());
                    // The file itself: two files can share a source, which stamps .yaml onto another extension.
                    lalRules.setProperty("rule." + idx + ".file", file.getKey());
                    lalRules.setProperty("rule." + idx + ".line", String.valueOf(sourceRef.getYamlLine()));
                    setRuleName(lalRules, "rule." + idx + ".", rule.getName());
                    lalRules.setProperty("rule." + idx + ".class", compiled.getClass().getName());
                    lalRules.setProperty("rule." + idx + ".inputType", typeName(effectiveInputType));
                    // What the class was compiled from, which the runtime DSL.of requires upstream to pass again.
                    lalRules.setProperty("rule." + idx + ".dslSha256", dslSha256);
                    lalRules.setProperty("rule." + idx + ".requestedInputType", typeName(inputType));
                    lalRules.setProperty("rule." + idx + ".requestedOutputType", typeName(outputType));
                    if (effectiveInputType != null) {
                        inputTypes.add(effectiveInputType.getName());
                    }
                    if (inputType != null) {
                        inputTypes.add(inputType.getName());
                    }
                    log.debug("LAL: compiled rule {} -> {}", rule.getName(),
                        compiled.getClass().getName());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to compile LAL rule " + rule.getName() + " ("
                        + sourceRef.describeYaml() + ")", e);
                }
            }
        }

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> lalV2Classes = scanV2Classes(outputDir, LAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("lal-v2-classes.txt"), lalV2Classes);
        writeEscapedProperties(metaInf.resolve("lal-v2-rules.txt"), lalRules);
        Set<String> ioClasses = new TreeSet<>();
        for (LALSourceTypeProvider provider : boot.sourceTypeProviders()) {
            ioClasses.add(provider.getClass().getName());
            // The layer's logs arrive as this type, whichever rule runs on them.
            inputTypes.add(provider.inputType().getName());
        }
        LalStaticRuleResolver.outputBuilders().forEach(builder -> ioClasses.add(builder.getName()));
        // The renderers JSON-print their input types whichever rules exist: a parser-only rule runs on every entry its
        // layer receives, of either Envoy protocol.
        for (LalInputDebugRenderer renderer : ServiceLoader.load(LalInputDebugRenderer.class)) {
            ioClasses.add(renderer.getClass().getName());
            inputTypes.add(renderer.inputType().getName());
        }
        writeManifest(metaInf.resolve("lal-io-classes.txt"), new ArrayList<>(ioClasses));
        // The log a rule runs on by default; its log.* and parsed.<field> fallbacks read its trace context and tags.
        inputTypes.add(LogData.class.getName());
        writeManifest(metaInf.resolve("lal-input-types.txt"), new ArrayList<>(inputTypes));

        log.info("LAL pre-compilation: {} rules, {} v2 expression classes",
            totalRules, lalV2Classes.size());

        serializeLALConfigData(outputDir, configsByFile);
    }

    private static String typeName(Class<?> type) {
        return type == null ? "" : type.getName();
    }

    /**
     * What upstream {@code LogFilterListener.Factory} refuses among the static rules at boot, which fails the build
     * instead: {@code fallback} off a {@code layer: auto} rule, two rules of one name in a layer (or among the auto
     * rules).
     *
     * @return false for a rule upstream skips at boot with a warning before it resolves or compiles anything
     *     ({@link LalStaticRuleResolver#skippedAtBoot}, every rule file's inline layers registered by now). Not
     *     compiled here either; its config stays in the registry, so the boot still warns.
     */
    static boolean checkRegistration(final LALConfig rule, final Set<List<String>> registered) {
        if (LalStaticRuleResolver.skippedAtBoot(rule)) {
            return false;
        }
        final boolean auto = LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer());
        final String at = "LAL rule " + rule.getName() + " of " + rule.getSourcePath();
        if (rule.isFallback() && !auto) {
            throw new IllegalStateException(at + " declares fallback: true, which only a layer: auto rule may");
        }
        // Upstream keys the rules of a layer by name in a map, where a rule without a name is one apart
        if (!registered.add(Arrays.asList(auto ? null : rule.getLayer(), rule.getName()))) {
            throw new IllegalStateException(at + ": " + (auto ? "two auto-layer rules" : "two rules of layer "
                + rule.getLayer()) + " are named " + rule.getName());
        }
        return true;
    }

    /**
     * Initialize DefaultScopeDefine: scan the @ScopeDeclaration annotations on the source classes (Service, Endpoint,
     * etc.) to populate the scope name → ID → columns registry, which the OAL enricher and a TAL {@code source}
     * block resolve through. Once per JVM: the registry rejects a scope declared twice.
     */
    static synchronized void declareScopes() throws Exception {
        if (!DefaultScopeDefine.names().isEmpty()) {
            return;
        }
        AnnotationScan scopeScan = new AnnotationScan();
        scopeScan.registerListener(new DefaultScopeDefine.Listener());
        scopeScan.scan();
    }

    /**
     * Pre-compile every TAL (Trace Analysis Language) rule file under {@code talPath}, whatever rules the
     * operator wrote there, resolving each rule's input and layer as upstream
     * {@code SpanFilterListener.Factory.compile} does, against a function registry bound as the factory binds its
     * own, and naming its class from the same {@link DslSourceRef}.
     * Every file is loaded through upstream's loader before any rule compiles, as upstream's {@code loadRules} does, so
     * a layer one file declares is registered for the rules of every file. A rule that fails here would fail the boot,
     * so the build fails.
     *
     * <p>{@code tal-v2-rules.txt} carries, per rule, the coordinates, the class, the input type and alias, the
     * registry key of every function object the class holds, and the metric declarations the same-FQCN
     * {@code TalDSL} sets, claims and creates at runtime; the meter classes the declarations need are generated here
     * through {@code createMetrics} and listed in {@code tal-meter-classes.txt}. {@code tal-io-classes.txt} lists the
     * input types, providers and function objects, for reflection metadata.
     */
    static void compileTAL(String outputDir, MeterSystem meterSystem, String talPath, ModuleManager modules)
        throws Exception {
        File talRtDir = new File(outputDir, TAL_RT_PACKAGE.replace('.', '/'));
        talRtDir.mkdirs();

        Map<String, TALSourceTypeProvider> inputProviders = new LinkedHashMap<>();
        for (TALSourceTypeProvider provider : ServiceLoader.load(TALSourceTypeProvider.class)) {
            inputProviders.put(provider.alias(), provider);
        }
        // Discovered and bound in the factory's order (ServiceLoader's), which the registry keys depend on; no module
        // manager, so every module's functions are bound, as a file is skipped at runtime when its modules are not.
        TALFunctionRegistry registry = TALFunctionRegistry.discover();
        registry.bind(null, inputProviders.values());
        log.info("TAL: input providers {}, function modules {}", inputProviders.keySet(), registry.modules());

        // Each name talFiles can list, loaded through upstream's loader with the modules the image ships, which picks
        // the file of the name (or fails where it cannot), keyed by the name of the file it read. A file requiring a
        // module the image does not ship is never loaded at boot: it registers no layer, compiles no rule and is not
        // carried.
        Map<String, TALConfigs> configsByFile = new LinkedHashMap<>();
        for (String stem : talStems(talPath)) {
            List<TALConfigs> loaded = TALConfigs.load(talPath, List.of(stem), modules);
            if (!loaded.isEmpty()) {
                TALConfigs configs = loaded.get(0);
                configsByFile.put(configs.getRules().isEmpty() ? onlyTalFile(talPath, stem).getName()
                    : configs.getRules().get(0).getSourceName(), configs);
                continue;
            }
            // Skipped for its modules as upstream's loader decides it, on the requiredModules it bound (not
            // requiredModulesOf's own read of the key, which differs for a value of another shape)
            File file = onlyTalFile(talPath, stem);
            TALConfigs typed = talFileConfigs(file.toPath());
            if (!typed.requiredModulesLoaded(modules)) {
                log.info("TAL: {} requires the modules {}, which the image does not all ship; upstream's boot skips it",
                    stem, typed.getRequiredModules());
                continue;
            }
            // Upstream's loader reads a file without rules (passing it through RuleSetMerger), registers its
            // layerDefinitions and returns nothing for it: the registry carries the file, so the boot does the same.
            if (typed.getRules() == null) {
                configsByFile.put(file.getName(), typed);
            }
        }

        TALMetricShapes metricShapes = new TALMetricShapes();
        int meterClassesBefore = meterSystem.getExportedClasses().size();
        Properties talRules = new Properties();
        Set<String> ioClasses = new TreeSet<>();
        // What each recorded rule's class was compiled from, to the wiring recorded with it
        Map<List<String>, String> wiringByInputs = new HashMap<>();
        Map<String, Boolean> nativeByFile = new HashMap<>();
        Map<String, String> nativeRuleSources = new HashMap<>();
        int totalRules = 0;
        for (Map.Entry<String, TALConfigs> file : configsByFile.entrySet()) {
            TALConfigs configs = file.getValue();
            for (TALConfig rule : configs.getRules() == null ? List.<TALConfig>of() : configs.getRules()) {
                List<String> declaredInputs = rule.inputTypes();
                if (declaredInputs.size() != 1) {
                    throw new IllegalStateException("TAL rule " + rule.getName() + " declares "
                        + declaredInputs.size() + " input types; the span root can only be typed to one");
                }
                TALSourceTypeProvider input = resolveTalInput(rule, declaredInputs.get(0), inputProviders);
                Class<?> inputType = input.inputType();
                resolveTalLayer(rule);
                // Over every rule as upstream's factory sees it, before rules alike share a class
                checkTalActivation(file.getKey(), rule.getName(), input.alias(), nativeByFile, nativeRuleSources);

                DslSourceRef sourceRef = DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo());
                // The runtime TalDSL takes the class recorded at the rule's coordinates for the name, text, input and
                // prefix upstream passes: a second rule agreeing on all of them (one line, two layers) runs that class,
                // as upstream runs its own alike class, only when the wiring recorded with it is its own too.
                List<String> inputs = Arrays.asList(sourceRef.describeYaml(), rule.getName(),
                    sha256Hex(rule.getDsl().getBytes(StandardCharsets.UTF_8)), inputType.getName(), input.alias(),
                    rule.getMetricPrefix() == null ? "" : rule.getMetricPrefix());
                String recordedWiring = wiringByInputs.get(inputs);
                TALClassGenerator generator = new TALClassGenerator();
                generator.setClassOutputDir(recordedWiring == null ? talRtDir : null);
                generator.setInputType(inputType);
                generator.setClassNameHint(rule.getName());
                generator.setSourceRef(sourceRef);
                generator.setMetricPrefix(rule.getMetricPrefix());
                generator.setContent(rule.getDsl());
                generator.setInputAlias(input.alias());
                generator.setFunctionRegistry(registry);
                TalExpression compiled = generator.compile(rule.getDsl());
                if (recordedWiring != null) {
                    String wiring = talWiring(talFunctionFields(rule, compiled, registry, inputProviders.keySet()),
                        generator.getMetricDeclarations());
                    if (!wiring.equals(recordedWiring)) {
                        throw new IllegalStateException("Two TAL rules named " + rule.getName() + " start at "
                            + sourceRef.describeYaml() + " with the same DSL text, input and metric prefix, but "
                            + "compile to classes wired differently, which the image cannot tell apart; give one of "
                            + "them a line of its own");
                    }
                    log.info("TAL rule {} of layer {} at {} runs the class of the rule of its name, text, input and "
                        + "prefix on the same line", rule.getName(), rule.getLayer(), sourceRef.describeYaml());
                    continue;
                }
                metricShapes.claim(sourceRef + "#" + rule.getName(), generator.getMetricDeclarations());
                List<String> created = meterSystem.recordCalls(false, () -> generator.createMetrics(meterSystem));
                // The runtime rebuilds each declaration from the fields recorded below: rebuild it so here, and a field
                // upstream adds would show as a difference instead of resetting to its default at boot.
                List<TALMetricDeclaration> rebuilt = new ArrayList<>();
                for (TALMetricDeclaration d : generator.getMetricDeclarations()) {
                    TALMetricDeclaration copy = new TALMetricDeclaration(d.fullName, d.function, d.scopeType,
                        d.declaredName, d.buckets, d.ranks, d.labelLimit);
                    String difference = StateComparison.firstDifference(d, copy, "TAL metric " + d.fullName);
                    if (difference != null) {
                        throw new IllegalStateException("TAL rule " + rule.getName() + ": the registry does not "
                            + "carry " + difference);
                    }
                    rebuilt.add(copy);
                }
                List<String> replayed = meterSystem.recordCalls(true, () -> TalMetrics.create(meterSystem, rebuilt));
                if (!created.equals(replayed)) {
                    throw new IllegalStateException("TAL rule " + rule.getName() + ": upstream createMetrics makes "
                        + created + ", but the runtime replays " + replayed + " from the recorded declarations; "
                        + "update TalMetrics");
                }

                // Keyed by the DslSourceRef the runtime TalDSL is given. Coordinates alone are not an identity: two
                // rules can share a line in flow-style YAML, so every input the class was compiled from is recorded,
                // and the runtime TalDSL requires all of them to match.
                String prefix = "rule." + totalRules++ + ".";
                talRules.setProperty(prefix + "source", sourceRef.getYamlFile());
                talRules.setProperty(prefix + "file", file.getKey());
                talRules.setProperty(prefix + "line", String.valueOf(sourceRef.getYamlLine()));
                setRuleName(talRules, prefix, rule.getName());
                talRules.setProperty(prefix + "class", compiled.getClass().getName());
                talRules.setProperty(prefix + "inputType", inputType.getName());
                talRules.setProperty(prefix + "inputAlias", input.alias());
                talRules.setProperty(prefix + "metricPrefix", rule.getMetricPrefix() == null ? "" : rule.getMetricPrefix());
                talRules.setProperty(prefix + "dslSha256", sha256Hex(rule.getDsl().getBytes(StandardCharsets.UTF_8)));
                ioClasses.add(inputType.getName());
                List<String[]> functions = talFunctionFields(rule, compiled, registry, inputProviders.keySet());
                wiringByInputs.put(inputs, talWiring(functions, generator.getMetricDeclarations()));
                GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled,
                    functions.stream().map(f -> f[0]).collect(Collectors.toSet()),
                    "TAL rule " + rule.getName() + " (" + sourceRef.describeYaml() + ")");
                for (int f = 0; f < functions.size(); f++) {
                    String functionPrefix = prefix + "function." + f + ".";
                    talRules.setProperty(functionPrefix + "field", functions.get(f)[0]);
                    talRules.setProperty(functionPrefix + "key", functions.get(f)[1]);
                    talRules.setProperty(functionPrefix + "type", functions.get(f)[2]);
                    ioClasses.add(functions.get(f)[2]);
                }
                List<TALMetricDeclaration> declarations = generator.getMetricDeclarations();
                for (int m = 0; m < declarations.size(); m++) {
                    TALMetricDeclaration d = declarations.get(m);
                    String metricPrefix = prefix + "metric." + m + ".";
                    talRules.setProperty(metricPrefix + "fullName", d.fullName);
                    talRules.setProperty(metricPrefix + "function", d.function);
                    talRules.setProperty(metricPrefix + "scopeType", d.scopeType);
                    talRules.setProperty(metricPrefix + "declaredName", d.declaredName);
                    talRules.setProperty(metricPrefix + "buckets", d.buckets.stream().map(String::valueOf)
                                                                     .collect(Collectors.joining(",")));
                    talRules.setProperty(metricPrefix + "ranks", d.ranks.stream().map(String::valueOf)
                                                                   .collect(Collectors.joining(",")));
                    talRules.setProperty(metricPrefix + "labelLimit", String.valueOf(d.labelLimit));
                }
                log.info("TAL: compiled rule {} ({}) -> {}, {} metrics", rule.getName(), sourceRef,
                    compiled.getClass().getName(), declarations.size());
            }
        }

        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> talV2Classes = scanV2Classes(outputDir, TAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("tal-v2-classes.txt"), talV2Classes);
        writeEscapedProperties(metaInf.resolve("tal-v2-rules.txt"), talRules);
        List<String> exported = meterSystem.getExportedClasses();
        writeManifest(metaInf.resolve("tal-meter-classes.txt"),
            new ArrayList<>(exported.subList(meterClassesBefore, exported.size())));
        // The SPI implementations ServiceLoader instantiates, and every object the registry binds: it lists their
        // methods at boot, whether or not a rule calls one.
        for (TALSourceTypeProvider provider : inputProviders.values()) {
            ioClasses.add(provider.getClass().getName());
            provider.helperFunctions().forEach(helper -> ioClasses.add(helper.getClass().getName()));
        }
        for (TALFunctionProvider provider : ServiceLoader.load(TALFunctionProvider.class)) {
            ioClasses.add(provider.getClass().getName());
        }
        writeManifest(metaInf.resolve("tal-io-classes.txt"), new ArrayList<>(ioClasses));
        log.info("TAL pre-compilation: {} files, {} rules, {} v2 expression classes, {} meter classes",
            configsByFile.size(), totalRules, talV2Classes.size(), exported.size() - meterClassesBefore);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = metaInf.resolve("config-data");
        Files.createDirectories(configDataDir);
        writeConfigData(mapper, configDataDir.resolve("tal.json"), configsByFile,
            new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
    }

    /**
     * Every compiled TAL file is active, in {@code nativeTalFiles} (its rules are {@code sw-native}) or in
     * {@code talFiles}. Fails on a file that list would refuse, as upstream's boot does: a native file holding a rule of
     * another input, and two native rules of one name (upstream's factory keys them by name in a map, where a rule
     * without a name is one apart). The TAL pass compiles no file requiring a module the image does not ship.
     *
     * @param nativeByFile      whether each file seen so far holds native rules
     * @param nativeRuleSources the file of each native rule seen so far, by name
     */
    static void checkTalActivation(String file, String ruleName, String inputAlias, Map<String, Boolean> nativeByFile,
                                   Map<String, String> nativeRuleSources) {
        String source = RuleCatalog.TAL.getDirectory() + "/" + file;
        boolean isNative = TALFunctionProvider.SW_NATIVE_INPUT_TYPE.equals(inputAlias);
        Boolean fileIsNative = nativeByFile.putIfAbsent(source, isNative);
        if (fileIsNative != null && fileIsNative != isNative) {
            throw new IllegalStateException("TAL file " + source + " mixes " + TALFunctionProvider.SW_NATIVE_INPUT_TYPE
                + " rules with rules of another input; native rules and span rules of other inputs go in separate files");
        }
        if (isNative && nativeRuleSources.containsKey(ruleName)) {
            throw new IllegalStateException("The TAL native rules of " + nativeRuleSources.get(ruleName) + " and "
                + source + " are both named " + ruleName);
        }
        if (isNative) {
            nativeRuleSources.put(ruleName, source);
        }
    }

    /**
     * Writes a rule's name into a build manifest, a rule without one (which upstream's factories compile) as such:
     * {@code unnamed=true} and no {@code name}, so it is told apart from a rule named {@code ""} or {@code "null"}.
     */
    static void setRuleName(Properties manifest, String prefix, String name) {
        if (name == null) {
            manifest.setProperty(prefix + "unnamed", "true");
        } else {
            manifest.setProperty(prefix + "name", name);
        }
    }

    /**
     * @return the function fields and metric declarations the runtime wires a TAL rule's class with, as text
     */
    private static String talWiring(List<String[]> functions, List<TALMetricDeclaration> declarations) {
        StringBuilder wiring = new StringBuilder();
        functions.forEach(f -> wiring.append(String.join("|", f)).append('\n'));
        for (TALMetricDeclaration d : declarations) {
            wiring.append(d.fullName).append('|').append(d.function).append('|').append(d.scopeType).append('|')
                  .append(d.declaredName).append('|').append(d.buckets).append('|').append(d.ranks).append('|')
                  .append(d.labelLimit).append('\n');
        }
        return wiring.toString();
    }

    /**
     * Same checks and messages as upstream {@code SpanFilterListener.Factory.resolveLayer}.
     */
    private static void resolveTalLayer(TALConfig rule) {
        if (rule.getLayer() == null || rule.getLayer().isEmpty()) {
            throw new IllegalStateException("TAL rule " + rule.getName() + " declares no layer");
        }
        try {
            Layer.valueOf(rule.getLayer());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TAL rule " + rule.getName() + " declares layer '" + rule.getLayer()
                + "' which is not registered; declare it under layerDefinitions or name a registered layer", e);
        }
    }

    private static TALSourceTypeProvider resolveTalInput(TALConfig rule, String declared,
                                                         Map<String, TALSourceTypeProvider> inputProviders) {
        TALSourceTypeProvider byAlias = inputProviders.get(declared);
        if (byAlias != null) {
            return byAlias;
        }
        for (TALSourceTypeProvider provider : inputProviders.values()) {
            if (provider.inputType().getName().equals(declared)) {
                return provider;
            }
        }
        throw new IllegalStateException("TAL rule " + rule.getName() + " declares inputType '" + declared
            + "' which no TALSourceTypeProvider claims; registered aliases are " + inputProviders.keySet());
    }

    /**
     * The function objects upstream's generator set on the compiled instance, every instance field the class declares
     * besides its static logger, each as {field, registry key, object class}. The key is the one the registry holds
     * that very object under, so the runtime TalDSL re-binds the same object from the factory's registry.
     */
    static List<String[]> talFunctionFields(TALConfig rule, TalExpression compiled, TALFunctionRegistry registry,
                                            Collection<String> inputAliases) throws IllegalAccessException {
        List<String[]> functions = new ArrayList<>();
        for (Field field : compiled.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // The generator's function slots are _fn<N>; every other field goes through GeneratedInstanceCheck.
            if (!field.getName().matches("_fn\\d+")) {
                continue;
            }
            field.setAccessible(true);
            Object owner = field.get(compiled);
            if (owner == null) {
                throw new IllegalStateException("TAL rule " + rule.getName() + ": the generator left function slot "
                    + field.getName() + " of " + compiled.getClass().getName() + " unset");
            }
            String byClass = owner.getClass().getName();
            List<String> candidates = new ArrayList<>();
            candidates.add(byClass);
            inputAliases.forEach(alias -> candidates.add(byClass + "/" + alias));
            List<String> keys = candidates.stream().filter(k -> registry.instance(k) == owner)
                                          .collect(Collectors.toList());
            if (keys.size() != 1) {
                throw new IllegalStateException("TAL rule " + rule.getName() + ": the " + byClass + " in field "
                    + field.getName() + " is held by the function registry under " + keys.size() + " of the keys "
                    + candidates);
            }
            functions.add(new String[] {field.getName(), keys.get(0), byClass});
        }
        return functions;
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
                + "Ensure the staged rule tree is on the classpath.\n"
                + "Missing:\n  " + String.join("\n  ", missing));
        }
        log.info("Validated: all {} OAL scripts found on classpath", oalDefines.length);
    }

    /**
     * Every OAL script of the staged rule tree compiles through a define: one an {@code OALDefine} class names, or
     * the define of a script the rules overlay added. A script no define names would be left out of the image.
     */
    static void requireEveryScriptCompiled(final OALDefine[] defines, final Collection<String> scripts) {
        final Set<String> compiled = Arrays.stream(defines).map(OALDefine::getConfigFile).collect(Collectors.toSet());
        final List<String> uncompiled = scripts.stream().filter(script -> !compiled.contains(script)).sorted()
                                               .collect(Collectors.toList());
        if (!uncompiled.isEmpty()) {
            throw new IllegalStateException("No OALDefine on the precompiler's class path names the OAL scripts "
                + uncompiled + ": the precompiler is missing the module that declares their define");
        }
    }

    /**
     * @return the {@code oal/*.oal} files of the staged rule tree
     */
    static List<String> stagedOalScripts() throws IOException {
        final List<String> scripts = new ArrayList<>();
        for (final File f : filesOf(RuleCatalog.OAL.getDirectory())) {
            if (f.isFile() && !f.getName().startsWith(".") && f.getName().endsWith(".oal")) {
                scripts.add("oal/" + f.getName());
            }
        }
        return scripts;
    }

    /**
     * @return the sources the script disables, as upstream's parser reads them for the generator to register
     */
    private static List<String> scriptDisables(final OALDefine define) throws IOException {
        try (Reader reader = ResourceUtils.read(define.getConfigFile())) {
            return OALScriptParserV2.parse(reader, define.getConfigFile()).getDisabledSources();
        }
    }

    /**
     * @return the names {@code talFiles} can list a file under {@code talPath} by: its name without the extension
     */
    static Set<String> talStems(final String talPath) throws IOException {
        Set<String> stems = new TreeSet<>();
        for (File f : filesOf(talPath)) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                stems.add(getNameWithoutExtension(f.getName()));
            }
        }
        return stems;
    }

    /**
     * The file of a name upstream's loader returned no rule of, so no stamped source name says which file it read.
     */
    private static File onlyTalFile(final String talPath, final String stem) throws IOException {
        List<File> files = Arrays.stream(filesOf(talPath))
                                 .filter(f -> f.isFile() && getNameWithoutExtension(f.getName()).equals(stem))
                                 .collect(Collectors.toList());
        if (files.size() != 1) {
            throw new IllegalStateException("TAL rule files " + files.stream().map(File::getName).sorted()
                .collect(Collectors.toList()) + " under " + talPath + " all name the rule file " + stem
                + ", which holds no rule, so upstream's loader does not tell which one it reads; keep one");
        }
        return files.get(0);
    }

    /**
     * @return the configs of a file as upstream's loader binds them (empty for a document of comments only)
     */
    static TALConfigs talFileConfigs(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            TALConfigs configs = new Yaml().loadAs(reader, TALConfigs.class);
            // A document of comments only binds nothing, as upstream reads it
            return configs == null ? new TALConfigs() : configs;
        }
    }

    /**
     * @return the annotation of every concrete AnnotationListener, by its simple name, the manifest name AnnotationScan
     * reads; each listener's own annotation(), on its singleton {@code INSTANCE} or an instance its constructor builds
     * from nulls (listeners keep their arguments for notify)
     */
    static Collection<Class<? extends Annotation>> listenedAnnotations(ImmutableSet<ClassPath.ClassInfo> allClasses)
        throws ReflectiveOperationException {
        Map<String, Class<? extends Annotation>> annotations = new TreeMap<>();
        List<Class<?>> candidates = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            collectNested(classInfo.load(), candidates);
        }
        for (Class<?> type : candidates) {
            if (!AnnotationListener.class.isAssignableFrom(type) || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Class<? extends Annotation> annotation = listenerInstance(type).annotation();
            Class<? extends Annotation> clash = annotations.put(annotation.getSimpleName(), annotation);
            if (clash != null && clash != annotation) {
                throw new IllegalStateException("Two listened annotations share the manifest name "
                    + annotation.getSimpleName() + ": " + clash.getName() + ", " + annotation.getName());
            }
        }
        return annotations.values();
    }

    private static void collectNested(Class<?> type, List<Class<?>> into) {
        into.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectNested(nested, into);
        }
    }

    private static AnnotationListener listenerInstance(Class<?> type) throws ReflectiveOperationException {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                return (AnnotationListener) field.get(null);
            }
        }
        Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
            .min(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow();
        constructor.setAccessible(true);
        return (AnnotationListener) constructor.newInstance(new Object[constructor.getParameterCount()]);
    }

    /**
     * Scan for classes annotated with the given annotation type. As upstream's scans, a class that does not load fails
     * the build: every class scanned here is on the image's class path.
     */
    private static List<String> scanAnnotation(
        ImmutableSet<ClassPath.ClassInfo> allClasses,
        Class<? extends Annotation> annotationType) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (aClass.isAnnotationPresent(annotationType)) {
                result.add(aClass.getName());
            }
        }
        Collections.sort(result);
        log.info("Scanned @{}: {} classes", annotationType.getSimpleName(), result.size());
        return result;
    }

    /**
     * Scan for concrete classes implementing or extending the given type.
     */
    private static List<String> scanImplementations(
        ImmutableSet<ClassPath.ClassInfo> allClasses, Class<?> type) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (!aClass.isInterface()
                && !Modifier.isAbstract(aClass.getModifiers())
                && type.isAssignableFrom(aClass)) {
                result.add(aClass.getName());
            }
        }
        Collections.sort(result);
        log.info("Scanned {}: {} classes", type.getSimpleName(), result.size());
        return result;
    }

    /**
     * @return every concrete {@link StreamData} class, nested ones included
     */
    private static List<String> scanStreamData(ImmutableSet<ClassPath.ClassInfo> allClasses) {
        List<Class<?>> classes = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            collectNested(classInfo.load(), classes);
        }
        List<String> result = classes.stream()
            .filter(c -> StreamData.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()))
            .map(Class::getName).sorted().collect(Collectors.toList());
        log.info("Scanned StreamData: {} classes", result.size());
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
            Class<?> aClass = classInfo.load();
            // @Stream-annotated classes declare builder in annotation
            if (aClass.isAnnotationPresent(org.apache.skywalking.oap.server.core.analysis.Stream.class)) {
                Class<?> builderClass =
                    aClass.getAnnotation(org.apache.skywalking.oap.server.core.analysis.Stream.class).builder();
                if (builderClass != null && builderClass != void.class) {
                    result.add(builderClass.getName());
                }
            }
            // @MeterFunction classes have StorageBuilder inner classes
            if (aClass.isAnnotationPresent(MeterFunction.class)) {
                for (Class<?> inner : aClass.getDeclaredClasses()) {
                    if (StorageBuilder.class.isAssignableFrom(inner)) {
                        result.add(inner.getName());
                    }
                }
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
     * Also collects the classes {@code @ExceptionHandler}, {@code @Decorator}, {@code @RequestConverter} and
     * {@code @ResponseConverter} name.
     * Armeria's annotatedService().build(handler) uses reflection to discover these annotations;
     * without reflection metadata, routes are silently not registered (returning 404).
     */
    private static List<String> scanArmeriaHandlers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                continue;
            }
            // Check if any method (including inherited) has Armeria routing annotations
            boolean matched = false;
            for (Method method : aClass.getMethods()) {
                if (isRoute(method)) {
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
                collectNamedClasses(aClass, ExceptionHandler.class, result);
                collectNamedClasses(aClass, Decorator.class, result);
                collectNamedClasses(aClass, RequestConverter.class, result);
                collectNamedClasses(aClass, ResponseConverter.class, result);
            }
        }

        // Also scan for ExceptionHandlerFunction implementations (instantiated by Armeria via reflection)
        result.addAll(scanImplementations(allClasses, ExceptionHandlerFunction.class));

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned Armeria HTTP handlers: {} classes", sorted.size());
        return sorted;
    }

    private static boolean isRoute(Method method) {
        return method.isAnnotationPresent(Get.class) || method.isAnnotationPresent(Post.class)
            || method.isAnnotationPresent(com.linecorp.armeria.server.annotation.Path.class);
    }

    /**
     * Collect the classes the {@code annotation}s on the handler class and its methods name ({@code value()}): Armeria
     * instantiates them reflectively while building the annotated service, and a miss fails the boot.
     */
    private static void collectNamedClasses(Class<?> handlerClass, Class<? extends Annotation> annotation,
                                            Set<String> result) {
        List<Annotation> annotations = new ArrayList<>(List.of(handlerClass.getAnnotationsByType(annotation)));
        for (Method method : handlerClass.getMethods()) {
            annotations.addAll(List.of(method.getAnnotationsByType(annotation)));
        }
        for (Annotation anno : annotations) {
            Object value;
            try {
                value = annotation.getMethod("value").invoke(anno);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read the @" + annotation.getSimpleName() + " of "
                    + handlerClass.getName(), e);
            }
            for (Class<?> named : value instanceof Class[] ? (Class<?>[]) value : new Class<?>[] {(Class<?>) value}) {
                result.add(named.getName());
            }
        }
    }

    /**
     * The classes Armeria binds a route's request and response bodies to: each route's parameters that are no
     * {@code @Param} or {@code @Header} value, its return type, the classes their fields hold and their superclasses, as
     * far as they are this project's classes, its enums included (Gson reads their fields); a protobuf message of any
     * package, which Armeria converts through {@code JsonFormat}, stands for its descriptor closure. A body a route
     * builds itself (an {@code HttpResponse} of an object) is not in a signature: its class stays on the
     * {@code configPojos} list by hand.
     */
    static List<String> scanHttpBodyTypes(List<String> handlers) {
        Set<Class<?>> bodies = new LinkedHashSet<>();
        for (String handler : handlers) {
            Class<?> handlerClass = loadClass(handler);
            // A route a generic superclass declares binds the bodies the handler gives its type variables
            Map<TypeVariable<?>, Type> bindings = typeBindings(handlerClass);
            for (Method method : handlerClass.getMethods()) {
                if (!isRoute(method)) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(Param.class) && !parameter.isAnnotationPresent(Header.class)) {
                        collectBodyTypes(parameter.getParameterizedType(), bindings, bodies);
                    }
                }
                collectBodyTypes(method.getGenericReturnType(), bindings, bodies);
            }
        }
        List<String> result = bodies.stream().map(Class::getName).sorted().collect(Collectors.toList());
        log.info("Scanned HTTP body types: {} classes", result.size());
        return result;
    }

    private static void collectBodyTypes(Type type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> bodies) {
        for (Class<?> element : valueTypes(type, bindings)) {
            Class<?> message = generatedMessage(element);
            if (message != null) {
                bodies.add(message);
                continue;
            }
            // An enum's own fields are what Gson reads; it holds no other body
            if (!element.getName().startsWith("org.apache.skywalking.") || element.isInterface()
                || element.isAnnotation() || !bodies.add(element) || element.isEnum()
                || Message.class.isAssignableFrom(element)) {
                continue;
            }
            // Jackson and Gson read the fields and accessors each class of the hierarchy declares
            Map<TypeVariable<?>, Type> hierarchy = typeBindings(element);
            for (Class<?> declaring = element; declaring != null
                && declaring.getName().startsWith("org.apache.skywalking."); declaring = declaring.getSuperclass()) {
                bodies.add(declaring);
                for (Field field : declaring.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                        collectBodyTypes(field.getGenericType(), hierarchy, bodies);
                    }
                }
            }
        }
    }

    /**
     * Auto-discover all classes under {@code org.apache.skywalking.oap.query.*.entity} and
     * {@code org.apache.skywalking.oap.server.admin.*.response} packages.
     * These are Jackson-serialized POJOs (Lombok {@code @Data}) returned by Armeria HTTP handlers
     * in query plugins (PromQL, LogQL, TraceQL, etc.) and the admin-server family (inspect,
     * ui-management). Inner static classes and codec serializers are included. Enums are excluded
     * (Jackson handles them without reflection metadata).
     *
     * <p>This replaces hardcoded class lists — new query plugins are picked up automatically.
     */
    private static List<String> scanQueryEntityClasses(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        // Match: org.apache.skywalking.oap.query.<plugin>.entity[.<sub>].<ClassName>
        //        org.apache.skywalking.oap.server.admin.<module>.response.<ClassName>
        //        org.apache.skywalking.oap.server.admin.status[.<sub>].Debugging*Rsp (the /debugging/* YAML bodies)
        //        org.apache.skywalking.oap.server.core.alarm.provider.status.<ClassName> (the /status/alarm/* bodies,
        //        Gson both ways: each node's status is printed, then parsed and merged by the node answering)
        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            String name = classInfo.getName();
            boolean queryEntity = name.startsWith("org.apache.skywalking.oap.query.") && name.contains(".entity.");
            boolean adminResponse = name.startsWith("org.apache.skywalking.oap.server.admin.")
                && name.contains(".response.");
            boolean debuggingResponse = name.startsWith("org.apache.skywalking.oap.server.admin.status.")
                && classInfo.getSimpleName().startsWith("Debugging") && classInfo.getSimpleName().endsWith("Rsp");
            boolean alarmStatus = name.startsWith("org.apache.skywalking.oap.server.core.alarm.provider.status.");
            if (!queryEntity && !adminResponse && !debuggingResponse && !alarmStatus) {
                continue;
            }
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
        }

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned query entity classes: {} classes", sorted.size());
        return sorted;
    }

    /**
     * Scan for GraphQL resolver classes — implementations of
     * {@code graphql.kickstart.tools.GraphQLQueryResolver} or
     * {@code graphql.kickstart.tools.GraphQLMutationResolver}.
     * graphql-java-tools reflects on methods to map GraphQL schema operations to Java methods.
     */
    private static List<String> scanGraphQLResolvers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        Set<String> result = new TreeSet<>(scanImplementations(allClasses, GraphQLQueryResolver.class));
        result.addAll(scanImplementations(allClasses, GraphQLMutationResolver.class));
        log.info("Scanned GraphQL resolvers: {} classes", result.size());
        return new ArrayList<>(result);
    }

    /**
     * Scan GraphQL schema files (.graphqls) for type/input/enum definitions and match
     * them to Java classes on the classpath by simple name.
     * graphql-java-tools (kickstart) maps schema type names to Java class simple names;
     * reflection is used to access fields/getters for field resolution.
     */
    private static List<String> scanGraphQLTypes(
        ImmutableSet<ClassPath.ClassInfo> allClasses) throws IOException {

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

        for (String schemaFile : listGraphQLSchemaFiles()) {
            try (BufferedReader reader = new BufferedReader(ResourceUtils.read(schemaFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = typePattern.matcher(line);
                    if (m.find()) {
                        graphqlTypeNames.add(m.group(2));
                    }
                }
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
    private static List<String> listGraphQLSchemaFiles() throws IOException {
        Set<String> files = new TreeSet<>();
        for (URL url : Collections.list(Precompiler.class.getClassLoader().getResources("query-protocol"))) {
            if ("file".equals(url.getProtocol())) {
                try (Stream<Path> children = Files.list(Path.of(url.toURI()))) {
                    children.map(f -> f.getFileName().toString()).filter(name -> name.endsWith(".graphqls"))
                            .forEach(name -> files.add("query-protocol/" + name));
                } catch (URISyntaxException e) {
                    throw new IOException("Unreadable schema directory " + url, e);
                }
            } else if ("jar".equals(url.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        if (entry.getName().startsWith("query-protocol/") && entry.getName().endsWith(".graphqls")) {
                            files.add(entry.getName());
                        }
                    }
                }
            } else {
                throw new IllegalStateException("query-protocol/ is at " + url + ", which the scan cannot list");
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No query-protocol/*.graphqls on the precompiler's class path: the "
                + "GraphQL types the image reflects on are read from them");
        }
        return new ArrayList<>(files);
    }

    private static Class<?> loadClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(fqcn + " is not on the precompiler's class path", e);
        }
    }

    /**
     * {@code mal-v2-rules.txt}: every MAL expression and filter the pass compiled, with the coordinates, metric name
     * and text {@code MetricConvert} passed, by which the rule registry keys the classes.
     */
    private static void writeMalRules(Path metaInf) throws IOException {
        Properties rules = new Properties();
        int i = 0;
        for (DSL.Compiled compiled : DSL.COMPILED) {
            String p = "expression." + i++ + ".";
            rules.setProperty(p + "source", compiled.sourceRef().getYamlFile());
            rules.setProperty(p + "line", String.valueOf(compiled.sourceRef().getYamlLine()));
            rules.setProperty(p + "name", compiled.metricName());
            rules.setProperty(p + "text", compiled.expression());
            rules.setProperty(p + "class", compiled.className());
        }
        i = 0;
        for (FilterExpression.Compiled compiled : FilterExpression.COMPILED) {
            String p = "filter." + i++ + ".";
            rules.setProperty(p + "source", compiled.sourceRef().getYamlFile());
            rules.setProperty(p + "line", String.valueOf(compiled.sourceRef().getYamlLine()));
            rules.setProperty(p + "text", compiled.literal());
            rules.setProperty(p + "class", compiled.className());
        }
        writeEscapedProperties(metaInf.resolve("mal-v2-rules.txt"), rules);
    }

    /**
     * Serialize what upstream's loader read from the MAL rule files (Rules) as JSON, which the rule registry is
     * generated from.
     */
    private static void serializeMALConfigData(String outputDir,
                                               Map<String, List<Rule>> rulesByPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        // Since 11.0.0 upstream loads meter-analyzer-config through the same Rules path, so it is no special case
        for (Map.Entry<String, List<Rule>> entry : rulesByPath.entrySet()) {
            String path = entry.getKey();
            List<Rule> rules = entry.getValue();
            writeConfigData(mapper, configDataDir.resolve(path + ".json"), rules, new TypeReference<List<Rule>>() {
            });
            log.info("Serialized {} Rule entries from {} to config-data JSON", rules.size(), path);
        }
    }

    /**
     * The rule registry is generated from this JSON, so it has to hold all upstream's loader read: write it, read it
     * back and require the same state, or a property Jackson cannot see (a setter without a getter) would vanish
     * before the registry is generated.
     */
    private static void writeConfigData(ObjectMapper mapper, Path file, Object value, TypeReference<?> type)
        throws Exception {
        byte[] json = mapper.writeValueAsBytes(value);
        // Read back as RegistryGenerator reads it: a computed getter (Rule#getSourceName) is written, never read.
        Object readBack = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                .readValue(json, type);
        String difference = StateComparison.firstDifference(value, readBack, file.getFileName().toString());
        if (difference != null) {
            throw new IllegalStateException("Rule data does not survive its JSON copy, at " + difference
                + ": the rule registry would lose it; teach the precompiler to carry it");
        }
        Files.write(file, json);
    }

    /**
     * Write the LAL configs upstream's loader returned as {@code lal.json}, the build intermediate the rule registry
     * is generated from.
     */
    private static void serializeLALConfigData(String outputDir,
                                               Map<String, LALConfigs> lalConfigMap) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        // Keyed by the file's own name (a stem would lose `.yml` and any dot in the name). The configs carry what
        // upstream LALConfigs.load stamped (sourceName/sourcePath/lineNo), which LogFilterListener builds the
        // DslSourceRef from at boot.
        writeConfigData(mapper, configDataDir.resolve("lal.json"), lalConfigMap,
            new TypeReference<LinkedHashMap<String, LALConfigs>>() {
            });
        log.info("Serialized {} LALConfigs entries from lal to config-data JSON", lalConfigMap.size());
    }

    /**
     * Exports the rule files the image was compiled from, of the catalogs whose upstream loader records the files it
     * reads ({@link RuleCatalog#mergedByLoader}), verbatim under {@code META-INF/rule-source/}, with a line each in its
     * {@code index.txt}: {@code catalog|relativePath}, the rule name being the path without extension. The replacement
     * loaders record the files they load from these, as upstream's loaders record the files they read, which the
     * read-only {@code /runtime/rule} catalog lists. The files are every staged one, a file yielding no rule too, as
     * upstream's loaders record every file they read.
     */
    private static void exportRuleSources(String outputDir, List<RuleCatalog> catalogs) throws Exception {
        Path root = Path.of(outputDir, "META-INF", "rule-source");
        Files.createDirectories(root);
        Path configData = Path.of(outputDir, "META-INF", "config-data");
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<String> index = new ArrayList<>();
        for (RuleCatalog ruleCatalog : catalogs) {
            String catalog = ruleCatalog.getDirectory();
            // Every staged file the loader reads, a file yielding no rule too, as upstream's loader records it; of TAL,
            // the files the TAL pass carries (none requiring a module the image does not ship).
            List<String> files = new ArrayList<>();
            if (ruleCatalog.getEngine() == RuleCatalog.Engine.TAL) {
                mapper.readTree(configData.resolve(catalog + ".json").toFile()).fieldNames().forEachRemaining(files::add);
            } else {
                files.addAll(rulePaths(catalog));
            }
            Path dir = existingDirectory(catalog);
            for (String rel : files) {
                Path out = root.resolve(catalog).resolve(rel);
                Files.createDirectories(out.getParent());
                Files.write(out, Files.readAllBytes(dir.resolve(rel)));
                index.add(catalog + "|" + rel);
            }
        }
        Files.write(root.resolve("index.txt"), index, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        log.info("Rule sources: exported {} files of {}", index.size(), catalogs);
    }

    /**
     * Copies the OAL scripts to their upstream classpath paths ({@code oal/core.oal} ...): the dsl-debugging module's
     * {@code RuntimeOalRestHandler} reads them for {@code GET /runtime/oal/files/{name}}.
     */
    private static void exportOalScripts(String outputDir, OALDefine[] oalDefines) throws IOException {
        ClassLoader cl = Precompiler.class.getClassLoader();
        for (OALDefine define : oalDefines) {
            String configFile = define.getConfigFile();
            try (InputStream is = cl.getResourceAsStream(configFile)) {
                if (is == null) {
                    throw new IllegalStateException("The OAL script " + configFile + " is not on the classpath");
                }
                Path out = Path.of(outputDir, configFile);
                Files.createDirectories(out.getParent());
                Files.write(out, is.readAllBytes());
            }
        }
        log.info("Rule sources: exported {} OAL scripts", oalDefines.length);
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (final Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                if (!p.equals(dir)) {
                    Files.delete(p);
                }
            }
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void writeManifest(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * {@link Properties#store}'s escaping, so any rule or file name round-trips through
     * {@code Properties.load(Reader)} with UTF-8, one entry per line, sorted so the build is reproducible.
     */
    static void writeEscapedProperties(Path path, Properties props) throws IOException {
        StringWriter raw = new StringWriter();
        props.store(raw, null);
        List<String> lines = raw.toString().lines()
            .filter(line -> !line.startsWith("#"))
            .sorted()
            .collect(Collectors.toList());
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * Generate GraalVM native-image metadata (reflect-config.json and resource-config.json)
     * from the manifests already produced by the precompiler.
     *
     * This gives native-image 100% coverage of all pre-compiled classes — unlike the tracing
     * agent which only captures code paths exercised during a traced run.
     */
    private static void generateNativeImageConfig(String outputDir, List<String> scannedClassManifests)
        throws IOException {
        Path nativeImageDir = Path.of(outputDir,
            "META-INF", "native-image", "org.apache.skywalking", "oap-graalvm-distro");
        Files.createDirectories(nativeImageDir);

        generateReflectConfig(outputDir, nativeImageDir, scannedClassManifests);
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
    private static void generateReflectConfig(String outputDir, Path nativeImageDir,
                                              List<String> scannedClassManifests) throws IOException {
        Path metaInf = Path.of(outputDir, "META-INF");
        Path annotationScanDir = metaInf.resolve("annotation-scan");

        List<Map<String, Object>> entries = new ArrayList<>();

        // Annotation-scanned classes — full reflection access
        for (String manifest : scannedClassManifests) {
            for (String className : readClassNames(annotationScanDir.resolve(manifest))) {
                entries.add(fullAccessEntry(className));
            }
        }

        // Armeria HTTP handlers — full access (Armeria reflects on @Post/@Get/@Path method annotations)
        // Query entity classes — full access (Jackson serializes @Data POJOs in HTTP responses)
        String[] httpHandlerManifests = {
            "ArmeriaHandlers.txt", "GraphQLResolvers.txt", "GraphQLTypes.txt", "QueryEntityClasses.txt"
        };
        for (String manifest : httpHandlerManifests) {
            for (String className : readClassNames(annotationScanDir.resolve(manifest))) {
                entries.add(fullAccessEntry(className));
            }
        }

        // Classes reflected on at runtime that no scan finds, each with its reason — full access
        String[] configPojos = {
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
            // Cluster status REST endpoint: Jackson serializes the node Addresses /status/cluster/nodes returns
            "org.apache.skywalking.oap.server.core.remote.client.Address",
            // AWS Firehose receiver: Jackson writes the response the route builds
            "org.apache.skywalking.oap.server.receiver.aws.firehose.FirehoseRes",
            // UI template REST (/ui-management/templates): Jackson writes the answers the routes build
            "org.apache.skywalking.oap.server.core.query.type.DashboardConfiguration",
            "org.apache.skywalking.oap.server.core.query.type.TemplateChangeStatus"
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
        try (InputStream setByField = Precompiler.class.getResourceAsStream("/META-INF/module-config-field-set.txt")) {
            if (setByField == null) {
                throw new IllegalStateException("META-INF/module-config-field-set.txt not found on classpath. "
                    + "Run config-generator first to produce it.");
            }
            entries.addAll(configFieldSetEntries(
                new BufferedReader(new InputStreamReader(setByField, StandardCharsets.UTF_8)).lines()
                    .map(String::trim).filter(line -> !line.isEmpty()).toList()));
        }

        // MeterFunction manifest — key=value format, full access (MeterSystem inspects annotations)
        for (String className : readValueFromKeyValue(annotationScanDir.resolve("MeterFunction.txt"))) {
            entries.add(fullAccessEntry(className));
        }

        // StorageBuilder classes — constructor-only (instantiated via getDeclaredConstructor().newInstance())
        addConstructorEntries(entries, annotationScanDir.resolve("StorageBuilders.txt"));
        // Stream data RemoteHandleWorker rebuilds — constructor-only
        addConstructorEntries(entries, annotationScanDir.resolve("StreamData.txt"));

        // OAL metrics, their storage builders and dispatchers — constructor-only. The builders are generated here
        // too, so a rules overlay's OAL script gets them.
        addConstructorEntries(entries, metaInf.resolve("oal-metrics-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-builder-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-dispatcher-classes.txt"));

        // MAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("mal-v2-classes.txt"));

        // LAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("lal-v2-classes.txt"));

        // TAL v2 expression classes: the constructor, plus the function-object fields the runtime TalDSL sets
        addTalExpressionEntries(entries, metaInf.resolve("tal-v2-classes.txt"), metaInf.resolve("tal-v2-rules.txt"));
        // The input types, providers and function objects the TAL rules were resolved through: ServiceLoader
        // instantiates the SPI ones, TALFunctionRegistry.bind lists the methods of every bound object (getMethods on
        // the providers), and toJson() in a rule serializes whatever object it is given through Gson
        for (String className : readClassNames(metaInf.resolve("tal-io-classes.txt"))) {
            Map<String, Object> entry = fullAccessEntry(className);
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        // The TAL roots `pairing` and `meta` of every rule, which toJson() hands to Gson's reflective serializer
        entries.add(fullAccessEntry("org.apache.skywalking.oap.trace.analyzer.v2.provider.span.PairingFact"));
        entries.add(fullAccessEntry("org.apache.skywalking.oap.server.core.trace.SpanMetadata"));
        // A protobuf input (a rule's `span`, an event) handed to toJson() is walked by Gson field by field: the message
        // classes themselves are in the descriptor closure below, the runtime classes holding their state are these.
        // Gson only reads fields, so their entries grant field access alone.
        for (String className : GSON_PROTOBUF_RUNTIME_CLASSES) {
            addFieldsWithNestedClasses(entries, className);
        }

        // Proto messages that go through protobuf's JsonFormat, whose accessor table finds the
        // generated getters and setters by reflection on the message, its Builder and every nested
        // message and enum type. Register the whole descriptor closure with full access. Roots: the
        // OTLP/HTTP JSON receivers' export requests, LogData parsed by ProtoBufJsonUtils, the TAL input
        // types, and the messages among the LAL values (addLalEntries; the LAL debug dump and
        // EnvoyAccessLogBuilder print the inputs).
        Set<String> protoJsonRoots = new TreeSet<>(List.of(
            "io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest",
            "io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest",
            "io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest",
            // Printed by the OTLP/HTTP JSON trace receiver and the TraceQL /otlp trace-by-id route.
            "io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse",
            "io.grafana.tempo.tempopb.TraceByIDResponse",
            "org.apache.skywalking.apm.network.logging.v3.LogData"));
        // The protobuf bodies of the HTTP routes, which Armeria converts through JsonFormat; the other bodies with full
        // access
        for (String className : readClassNames(annotationScanDir.resolve("HttpBodyTypes.txt"))) {
            Class<?> message = generatedMessage(loadClass(className));
            if (message != null) {
                protoJsonRoots.add(message.getName());
            } else {
                entries.add(fullAccessEntry(className));
            }
        }
        Properties talRules = loadProperties(metaInf.resolve("tal-v2-rules.txt"));
        for (String key : talRules.stringPropertyNames()) {
            if (key.matches("rule\\.\\d+\\.inputType") && !talRules.getProperty(key).isBlank()) {
                protoJsonRoots.add(talRules.getProperty(key).trim());
            }
        }
        addLalEntries(entries, metaInf, protoJsonRoots);
        addProtoMessageEntries(entries, protoJsonRoots);

        // Hierarchy v2 rule classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("hierarchy-v2-classes.txt"));

        // MAL and TAL meter classes — key=value format, constructor-only
        for (String meterManifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            for (String className : readValueFromKeyValue(metaInf.resolve(meterManifest))) {
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

        // Rule data is code (the generated registry); these are the scans and the rule text the catalogs display.
        includes.add(Map.of("pattern", "META-INF/annotation-scan/.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/rule-source/.*"));
        includes.add(Map.of("pattern", "oal/.*\\.oal"));

        resources.put("includes", includes);
        resourceConfig.put("resources", resources);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(nativeImageDir.resolve("resource-config.json").toFile(), resourceConfig);
        log.info("Generated resource-config.json with {} resource patterns", includes.size());
    }

    /**
     * Register every message and enum type reachable from {@code rootTypes} through the proto
     * descriptors, plus their {@code Builder} classes, with full access. A non-message root gets a
     * name-only entry.
     */
    private static void addProtoMessageEntries(List<Map<String, Object>> entries, Set<String> rootTypes) {
        Set<Class<?>> closure = new LinkedHashSet<>();
        for (String rootType : rootTypes) {
            Class<?> root;
            try {
                root = Class.forName(rootType);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Proto JSON root type is not on the precompiler classpath: " + rootType, e);
            }
            Class<?> generated = generatedMessage(root);
            if (generated != null) {
                collectProtoClosure(generated, closure);
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", rootType);
                entries.add(entry);
            }
        }
        for (Class<?> clazz : closure) {
            entries.add(fullAccessEntry(clazz.getName()));
        }
        log.info("Registered {} protobuf classes reachable from {} root types", closure.size(), rootTypes.size());
    }

    /**
     * What the LAL runtime reflects on. The SPI implementations ServiceLoader instantiates; the output type upstream's
     * boot resolves a rule's {@code outputType} to ({@code Class.forName} when the rule names a class); and the values
     * {@code LalRuntimeHelper} reads a property of when the rule's compiler could not type it ({@code parsed.<key>} no
     * parser set, read from the input, or {@code value.<name>}): it lists the value's public methods, checks the
     * getter against {@link LALCallables} and calls it. Those values are walked from every type a rule reads values
     * from ({@link #collectLalValues}); the generated messages among them join {@code protoRoots}.
     */
    private static void addLalEntries(List<Map<String, Object>> entries, Path metaInf, Set<String> protoRoots)
        throws IOException {
        for (String className : readClassNames(metaInf.resolve("lal-io-classes.txt"))) {
            entries.add(fullAccessEntry(className));
        }
        Properties rules = loadProperties(metaInf.resolve("lal-v2-rules.txt"));
        for (String key : rules.stringPropertyNames()) {
            if (key.matches("rule\\.\\d+\\.requestedOutputType") && !rules.getProperty(key).isBlank()) {
                entries.add(constructorOnlyEntry(rules.getProperty(key).trim()));
            }
        }
        Set<Class<?>> values = new LinkedHashSet<>();
        // The values every rule can read whatever its input: the log's own (log.*, a parser-less parsed.*) and the
        // parsed map; a value read from them through a call the compiler cannot type (a map's getOrDefault falling
        // back to log.traceContext) is reflected on at run time like an input's
        for (Class<?> builtin : LAL_BUILTIN_VALUES) {
            collectLalValues(builtin, protoRoots, values);
        }
        for (String className : readClassNames(metaInf.resolve("lal-input-types.txt"))) {
            try {
                collectLalValues(Class.forName(className), protoRoots, values);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("LAL input type is not on the precompiler classpath: " + className, e);
            }
        }
        for (Class<?> type : values) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        addLalCallablesEntries(entries);
        log.info("Registered {} LAL value classes besides the protobuf ones", values.size());
    }

    /**
     * The values LAL gives every rule, whatever its input: the log's metadata ({@code log.*}, and {@code parsed.*}
     * without a parser), its {@code LogData}, and the parsed map's holder.
     */
    static final List<Class<?>> LAL_BUILTIN_VALUES =
        List.of(LogMetadata.class, LogData.class, ExecutionContext.Parsed.class);

    /**
     * The types {@code LalRuntimeHelper} reflects on when a value is of {@code type}. A generated message joins
     * {@code protoRoots}: its descriptor closure holds every message its getters return. Another type is registered
     * with its public methods, and so is every public type of its hierarchy declaring a getter {@link LALCallables}
     * lets a rule call (the runtime calls a getter of a non-public class through one); a getter's return type, or an
     * element type of it, that is a protobuf message, a {@link ToJson}, or another type of this project whose getters
     * {@link LALCallables} lets a rule call ({@code LogMetadata.TraceContext}) is walked in turn.
     */
    static void collectLalValues(Class<?> type, Set<String> protoRoots, Set<Class<?>> registered) {
        Class<?> message = generatedMessage(type);
        if (message != null) {
            protoRoots.add(message.getName());
            return;
        }
        if (!registered.add(type)) {
            return;
        }
        DslCallables callables = LALCallables.get();
        for (Method getter : type.getMethods()) {
            if (getter.getParameterCount() != 0 || !isPropertyGetter(getter.getName())
                || !callables.allows(type, getter)) {
                continue;
            }
            registered.addAll(publicDeclarers(type, getter.getName()));
            for (Class<?> value : valueTypes(getter.getGenericReturnType(), typeBindings(type))) {
                if (MessageOrBuilder.class.isAssignableFrom(value) || ToJson.class.isAssignableFrom(value)
                    || hasCallableGetter(value, callables)) {
                    collectLalValues(value, protoRoots, registered);
                }
            }
        }
    }

    /**
     * @return whether the type is one of this project's whose getters a rule may call, read by reflection when a value
     *     of it reaches a rule untyped
     */
    private static boolean hasCallableGetter(Class<?> type, DslCallables callables) {
        return type.getName().startsWith("org.apache.skywalking.") && Arrays.stream(type.getMethods())
            .anyMatch(m -> m.getParameterCount() == 0 && isPropertyGetter(m.getName()) && callables.allows(type, m));
    }

    /** {@code getX()} or {@code isX()}: what {@code LalRuntimeHelper} looks a property {@code x} up as. */
    private static boolean isPropertyGetter(String name) {
        return name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))
            || name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static Set<Class<?>> publicDeclarers(Class<?> type, String getter) {
        Set<Class<?>> declarers = new LinkedHashSet<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            if (Modifier.isPublic(c.getModifiers()) && Arrays.stream(c.getDeclaredMethods()).anyMatch(
                m -> m.getName().equals(getter) && m.getParameterCount() == 0 && Modifier.isPublic(m.getModifiers())
                    && !Modifier.isStatic(m.getModifiers()))) {
                declarers.add(c);
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(Arrays.asList(c.getInterfaces()));
        }
        return declarers;
    }

    /**
     * The classes a value of the type holds: a class, an array's element class, a parameterized type's raw class and
     * those of its type arguments ({@code List<Foo>}'s {@code Foo}), a wildcard's upper bounds
     * ({@code List<? extends Foo>}), a generic array's element type ({@code List<Foo>[]}), and a type variable's binding
     * in {@code bindings}, or else its bounds ({@code T[]} with {@code T extends Foo}).
     */
    static List<Class<?>> valueTypes(Type type, Map<TypeVariable<?>, Type> bindings) {
        return valueTypes(type, bindings, new HashSet<>());
    }

    private static List<Class<?>> valueTypes(Type type, Map<TypeVariable<?>, Type> bindings,
                                             Set<TypeVariable<?>> resolving) {
        List<Class<?>> types = new ArrayList<>();
        if (type instanceof Class) {
            Class<?> element = (Class<?>) type;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            types.add(element);
        } else if (type instanceof ParameterizedType) {
            types.add((Class<?>) ((ParameterizedType) type).getRawType());
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                types.addAll(valueTypes(argument, bindings, resolving));
            }
        } else if (type instanceof WildcardType) {
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                types.addAll(valueTypes(bound, bindings, resolving));
            }
        } else if (type instanceof GenericArrayType) {
            types.addAll(valueTypes(((GenericArrayType) type).getGenericComponentType(), bindings, resolving));
        } else if (type instanceof TypeVariable && resolving.add((TypeVariable<?>) type)) {
            // A variable bound to itself through its bounds (T extends Comparable<T>) is resolved once
            TypeVariable<?> variable = (TypeVariable<?>) type;
            Type binding = bindings.get(variable);
            for (Type bound : binding != null ? new Type[] {binding} : variable.getBounds()) {
                types.addAll(valueTypes(bound, bindings, resolving));
            }
            resolving.remove(variable);
        }
        return types;
    }

    /**
     * @return the type each type variable of the class's superclasses and interfaces is given in its hierarchy
     *     ({@code Box<T>}'s {@code T} in {@code FilledBox extends Box<Bound>}: {@code Bound})
     */
    static Map<TypeVariable<?>, Type> typeBindings(Class<?> type) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            List<Type> supertypes = new ArrayList<>(Arrays.asList(c.getGenericInterfaces()));
            if (c.getGenericSuperclass() != null) {
                supertypes.add(c.getGenericSuperclass());
            }
            for (Type supertype : supertypes) {
                Class<?> raw = supertype instanceof ParameterizedType
                    ? (Class<?>) ((ParameterizedType) supertype).getRawType() : (Class<?>) supertype;
                if (supertype instanceof ParameterizedType) {
                    TypeVariable<?>[] parameters = raw.getTypeParameters();
                    Type[] arguments = ((ParameterizedType) supertype).getActualTypeArguments();
                    for (int i = 0; i < parameters.length; i++) {
                        bindings.put(parameters[i], arguments[i]);
                    }
                }
                queue.add(raw);
            }
        }
        return bindings;
    }

    /**
     * {@link LALCallables} is first built in the image, when a getter read at run time is checked: building it reads
     * every listed class's public methods (and {@code Object}'s, for an interface) and static fields.
     */
    private static void addLalCallablesEntries(List<Map<String, Object>> entries) {
        DslCallables callables = LALCallables.get();
        Set<Class<?>> listed = new LinkedHashSet<>();
        listed.add(Object.class);
        listed.addAll(callables.declaredMethods().keySet());
        listed.addAll(callables.declaredStatics().keySet());
        for (Class<?> type : listed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        callables.declaredStaticFields().forEach((type, names) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("fields", names.stream().map(n -> Map.of("name", n)).collect(Collectors.toList()));
            entries.add(entry);
        });
        log.info("Registered the public methods of {} classes LALCallables lists", listed.size());
    }

    /**
     * @return a module manager that has exactly the modules the image ships ({@link AcceptedModules#shippedModuleNames})
     */
    static ModuleManager shippedModules() {
        return modules(AcceptedModules.shippedModuleNames());
    }

    /**
     * @return a module manager that has exactly these modules, asked as upstream's hash-map-backed one is: a null name
     *     is no module it has
     */
    static ModuleManager modules(Set<String> names) {
        return new ModuleManager("precompiler") {
            @Override
            public boolean has(String moduleName) {
                return moduleName != null && names.contains(moduleName);
            }
        };
    }

    private static void collectProtoClosure(Class<?> messageClass, Set<Class<?>> closure) {
        if (!closure.add(messageClass)) {
            return;
        }
        for (Class<?> nested : messageClass.getDeclaredClasses()) {
            if (Message.Builder.class.isAssignableFrom(nested)) {
                closure.add(nested);
            }
        }
        Descriptors.Descriptor descriptor;
        try {
            descriptor = (Descriptors.Descriptor) messageClass.getMethod("getDescriptor").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Not a generated protobuf message: " + messageClass.getName(), e);
        }
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            Descriptors.FieldDescriptor.JavaType javaType = field.getJavaType();
            if (javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE
                && javaType != Descriptors.FieldDescriptor.JavaType.ENUM) {
                continue;
            }
            Class<?> fieldType = field.isMapField()
                ? mapValueType(messageClass, field)
                : accessorReturnType(messageClass, field);
            if (fieldType == null) {
                continue; // map with scalar values: nothing generated to register
            }
            if (Message.class.isAssignableFrom(fieldType)) {
                collectProtoClosure(fieldType, closure);
            } else if (fieldType.isEnum()) {
                closure.add(fieldType);
            }
        }
    }

    /** {@code getFoo()} for singular fields, {@code getFoo(int)} for repeated ones. */
    private static Class<?> accessorReturnType(Class<?> messageClass, Descriptors.FieldDescriptor field) {
        return findGetter(messageClass, "get" + field.getName(), field.isRepeated() ? 1 : 0).getReturnType();
    }

    /** The value type argument of {@code Map<K, V> getFooMap()}; {@code null} for scalar values. */
    private static Class<?> mapValueType(Class<?> messageClass, Descriptors.FieldDescriptor field) {
        Type type = findGetter(messageClass, "get" + field.getName() + "Map", 0).getGenericReturnType();
        if (type instanceof ParameterizedType) {
            Type value = ((ParameterizedType) type).getActualTypeArguments()[1];
            if (value instanceof Class) {
                return (Class<?>) value;
            }
        }
        return null;
    }

    /**
     * protobuf-java derives getter names from the proto field name (underscores to camel case, a
     * trailing '_' when the name clashes with a reserved one); match case- and underscore-insensitively
     * instead of replicating its rules.
     */
    private static Method findGetter(Class<?> messageClass, String name, int parameterCount) {
        String wanted = name.replace("_", "").toLowerCase(Locale.ROOT);
        for (Method method : messageClass.getMethods()) {
            if (method.getDeclaringClass() != Object.class
                && method.getParameterCount() == parameterCount
                && method.getName().replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return method;
            }
        }
        throw new IllegalStateException("No getter " + name + " on " + messageClass.getName());
    }

    private static final String[] GSON_PROTOBUF_RUNTIME_CLASSES = {
        "com.google.protobuf.AbstractMessage",
        "com.google.protobuf.AbstractMessageLite",
        "com.google.protobuf.GeneratedMessage",
        "com.google.protobuf.GeneratedMessageV3",
        "com.google.protobuf.ByteString",
        "com.google.protobuf.RopeByteString",
        "com.google.protobuf.UnknownFieldSet",
        "com.google.protobuf.MapField",
        "com.google.protobuf.AbstractProtobufList",
        "com.google.protobuf.ProtobufArrayList",
        "com.google.protobuf.LazyStringArrayList",
        "com.google.protobuf.BooleanArrayList",
        "com.google.protobuf.IntArrayList",
        "com.google.protobuf.LongArrayList",
        "com.google.protobuf.FloatArrayList",
        "com.google.protobuf.DoubleArrayList"
    };

    /**
     * Field access for the class and every class nested in it. The protobuf runtime's internals differ between
     * versions: a class the protobuf version on the class path no longer has fails the build, for the list to be
     * reviewed.
     */
    private static void addFieldsWithNestedClasses(List<Map<String, Object>> entries, String className) {
        Class<?> clazz = loadClass(className);
        entries.add(fieldsOnlyEntry(clazz.getName()));
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            entries.add(fieldsOnlyEntry(nested.getName()));
        }
    }

    /**
     * The fields the generated config binder writes through {@code Field.set} as upstream does (no setter writes a
     * final or a static field), as the config-generator lists them ({@code declaringClass#field}, then {@code static}
     * and {@code final} where they apply): each declaring class with those fields, so the image finds them, and a final
     * instance one writable; a static final one is only found, so {@code Field.set} refuses it as upstream's does.
     */
    static List<Map<String, Object>> configFieldSetEntries(List<String> setByField) {
        Map<String, List<Map<String, Object>>> byClass = new TreeMap<>();
        for (String line : setByField) {
            String[] parts = line.split("\\s+");
            int hash = parts[0].indexOf('#');
            List<String> modifiers = Arrays.asList(parts).subList(1, parts.length);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", parts[0].substring(hash + 1));
            if (modifiers.contains("final") && !modifiers.contains("static")) {
                field.put("allowWrite", true);
            }
            byClass.computeIfAbsent(parts[0].substring(0, hash), k -> new ArrayList<>()).add(field);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        byClass.forEach((className, fields) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", className);
            entry.put("fields", fields);
            entries.add(entry);
        });
        return entries;
    }

    private static Map<String, Object> fieldsOnlyEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("allDeclaredFields", true);
        return entry;
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
     * The TAL expression classes of {@code tal-v2-classes.txt}: each one's constructor, plus the function-object
     * fields {@code tal-v2-rules.txt} records for it, which the runtime TalDSL writes.
     */
    private static void addTalExpressionEntries(List<Map<String, Object>> entries, Path classes, Path rules)
        throws IOException {
        Map<String, List<Map<String, Object>>> fieldsByClass = new HashMap<>();
        Properties props = loadProperties(rules);
        for (int i = 0; props.getProperty("rule." + i + ".class") != null; i++) {
            List<Map<String, Object>> fields = fieldsByClass.computeIfAbsent(
                props.getProperty("rule." + i + ".class"), k -> new ArrayList<>());
            for (int f = 0; props.getProperty("rule." + i + ".function." + f + ".field") != null; f++) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", props.getProperty("rule." + i + ".function." + f + ".field"));
                field.put("allowWrite", true);
                fields.add(field);
            }
        }
        for (String className : readClassNames(classes)) {
            Map<String, Object> entry = constructorOnlyEntry(className);
            List<Map<String, Object>> fields = fieldsByClass.get(className);
            if (fields != null && !fields.isEmpty()) {
                entry.put("fields", fields);
            }
            entries.add(entry);
        }
    }

    /**
     * Read class names from a one-per-line manifest and add constructor-only entries.
     */
    private static void addConstructorEntries(List<Map<String, Object>> entries, Path file) throws IOException {
        for (String className : readClassNames(file)) {
            entries.add(constructorOnlyEntry(className));
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
