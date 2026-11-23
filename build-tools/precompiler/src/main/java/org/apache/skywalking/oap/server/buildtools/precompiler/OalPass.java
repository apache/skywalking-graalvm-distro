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

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oal.v2.OALEngineV2;
import org.apache.skywalking.oal.v2.generator.OALClassGeneratorV2;
import org.apache.skywalking.oal.v2.parser.OALScriptParserV2;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;

import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeEscapedProperties;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;

/**
 * The OAL pass: discovers the OAL defines and runs each script's engine as upstream's loader does, recording what it
 * registers.
 */
@Slf4j
final class OalPass {
    /**
     * Discover all OALDefine subclasses on the classpath via Guava ClassPath scanning.
     */
    static OALDefine[] discoverOALDefines() {
        final List<OALDefine> defines = new ArrayList<>();
        final ImmutableSet<ClassPath.ClassInfo> classes;
        try {
            classes = ClassPath.from(OalPass.class.getClassLoader())
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

    static void compileOAL(final String outputDir, final OALDefine[] oalDefines, final OverlayInventory overlay)
        throws Exception {
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
                engine.start(OalPass.class.getClassLoader());
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
     * Validate that all OAL script files referenced by the discovered OALDefines are
     * available on the classpath. Fails fast with a clear error if any are missing.
     */
    static void validateOALScripts(OALDefine[] oalDefines) {
        ClassLoader cl = OalPass.class.getClassLoader();
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
        for (final File f : RuleTree.filesOf(RuleCatalog.OAL.getDirectory())) {
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
}
