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

package org.apache.skywalking.oap.server.buildtools.oal;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.aop.server.receiver.mesh.MeshOALDefine;
import org.apache.skywalking.oal.v2.OALEngineV2;
import org.apache.skywalking.oal.v2.generator.OALClassGeneratorV2;
import org.apache.skywalking.oap.server.core.analysis.Disable;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.ISourceDecorator;
import org.apache.skywalking.oap.server.core.analysis.MultipleDisable;
import org.apache.skywalking.oap.server.core.analysis.SourceDispatcher;
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
 * Build-time tool that runs the OAL engine for all 9 OALDefine configurations,
 * exports generated .class files and manifest files, and scans the classpath for
 * hardcoded annotated classes and interface implementations used at runtime.
 *
 * OAL script files are loaded from the skywalking submodule directly via
 * additionalClasspathElements in the exec-maven-plugin configuration.
 */
@Slf4j
public class OALClassExporter {

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
            : "target/generated-oal-classes";

        log.info("OAL Class Exporter: output -> {}", outputDir);

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
            engine.start(OALClassExporter.class.getClassLoader());
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

        log.info("OAL Class Exporter: {} metrics, {} dispatchers, {} disabled sources",
            metricsClasses.size(), dispatcherClasses.size(), disabledSources.size());

        // ---- Annotation & interface scanning for hardcoded classes ----
        Path annotationScanDir = metaInf.resolve("annotation-scan");
        Files.createDirectories(annotationScanDir);

        ImmutableSet<ClassPath.ClassInfo> allClasses = ClassPath
            .from(OALClassExporter.class.getClassLoader())
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

        log.info("OAL Class Exporter: done");
    }

    /**
     * Validate that all OAL script files referenced by ALL_DEFINES are
     * available on the classpath. Fails fast with a clear error if any are missing.
     */
    private static void validateOALScripts() {
        ClassLoader cl = OALClassExporter.class.getClassLoader();
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

    private static void writeManifest(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }
}
