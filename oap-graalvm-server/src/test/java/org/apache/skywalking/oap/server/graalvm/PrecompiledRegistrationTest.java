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

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.apache.skywalking.oap.server.core.analysis.ISourceDecorator;
import org.apache.skywalking.oap.server.core.analysis.SourceDispatcher;
import org.apache.skywalking.oap.server.core.analysis.Stream;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction;
import org.apache.skywalking.oap.server.core.annotation.AnnotationScan;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.source.ISource;
import org.apache.skywalking.oap.server.core.source.ScopeDeclaration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that pre-compiled manifests produced by OALClassExporter are complete
 * and consistent. Checks:
 * 1. Manifest contents match live Guava ClassPath scan (for hardcoded classes)
 * 2. All OAL-generated classes are loadable and properly annotated
 * 3. DefaultScopeDefine initializes correctly from manifests
 * 4. Source -> Dispatcher -> Metrics chain is consistent
 */
class PrecompiledRegistrationTest {

    private static final String OAL_RT_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.";

    @BeforeAll
    static void initScopeDefine() throws Exception {
        DefaultScopeDefine.reset();
        AnnotationScan scopeScan = new AnnotationScan();
        scopeScan.registerListener(new DefaultScopeDefine.Listener());
        scopeScan.scan();
    }

    @AfterAll
    static void cleanUp() {
        DefaultScopeDefine.reset();
    }

    // =========================================================================
    // 1. Manifest vs Guava ClassPath comparison (hardcoded classes only)
    // =========================================================================

    @Test
    void scopeDeclarationManifestMatchesClasspath() throws Exception {
        Set<String> manifest = readManifestAsSet("META-INF/annotation-scan/ScopeDeclaration.txt");
        Set<String> scanned = guavaScanAnnotation(ScopeDeclaration.class);
        assertFalse(manifest.isEmpty(), "ScopeDeclaration manifest is empty");
        assertEquals(scanned, manifest,
            "ScopeDeclaration manifest does not match classpath scan");
    }

    @Test
    void hardcodedStreamManifestMatchesClasspath() throws Exception {
        Set<String> manifest = readManifestAsSet("META-INF/annotation-scan/Stream.txt");
        Set<String> scanned = guavaScanAnnotation(Stream.class);
        // Filter out OAL-generated classes from Guava scan
        scanned.removeIf(name -> name.startsWith(OAL_RT_PACKAGE));
        assertFalse(manifest.isEmpty(), "Stream manifest is empty");
        assertEquals(scanned, manifest,
            "Hardcoded @Stream manifest does not match classpath scan");
    }

    @Test
    void sourceDispatcherManifestMatchesClasspath() throws Exception {
        Set<String> manifest = readManifestAsSet("META-INF/annotation-scan/SourceDispatcher.txt");
        Set<String> scanned = guavaScanInterface(SourceDispatcher.class);
        // Filter out OAL-generated dispatchers from Guava scan
        scanned.removeIf(name -> name.startsWith(OAL_RT_PACKAGE));
        assertFalse(manifest.isEmpty(), "SourceDispatcher manifest is empty");
        assertEquals(scanned, manifest,
            "SourceDispatcher manifest does not match classpath scan");
    }

    @Test
    void sourceDecoratorManifestMatchesClasspath() throws Exception {
        Set<String> manifest = readManifestAsSet("META-INF/annotation-scan/ISourceDecorator.txt");
        Set<String> scanned = guavaScanInterface(ISourceDecorator.class);
        assertFalse(manifest.isEmpty(), "ISourceDecorator manifest is empty");
        assertEquals(scanned, manifest,
            "ISourceDecorator manifest does not match classpath scan");
    }

    // =========================================================================
    // 1b. MeterFunction manifest vs Guava ClassPath comparison
    // =========================================================================

    @Test
    void meterFunctionManifestMatchesClasspath() throws Exception {
        // Build expected map from Guava ClassPath scan
        Map<String, String> scanned = guavaScanMeterFunctions();

        // Read manifest
        List<String> manifestLines = readManifest("META-INF/annotation-scan/MeterFunction.txt");
        Map<String, String> manifest = new HashMap<>();
        for (String line : manifestLines) {
            String[] parts = line.split("=", 2);
            assertEquals(2, parts.length, "Invalid MeterFunction manifest line: " + line);
            manifest.put(parts[0], parts[1]);
        }

        assertFalse(manifest.isEmpty(), "MeterFunction manifest is empty");
        assertEquals(scanned, manifest,
            "MeterFunction manifest does not match classpath scan");
    }

    @Test
    void meterFunctionManifestHasExpectedFunctions() throws Exception {
        List<String> manifestLines = readManifest("META-INF/annotation-scan/MeterFunction.txt");
        Map<String, String> manifest = new HashMap<>();
        for (String line : manifestLines) {
            String[] parts = line.split("=", 2);
            manifest.put(parts[0], parts[1]);
        }

        // Verify well-known meter functions exist
        String[] expectedFunctions = {
            "avg", "avgLabeled", "avgHistogram", "avgHistogramPercentile",
            "latest", "latestLabeled",
            "max", "maxLabeled", "min", "minLabeled",
            "sum", "sumLabeled", "sumHistogram", "sumHistogramPercentile",
            "sumPerMin", "sumPerMinLabeled"
        };
        for (String fn : expectedFunctions) {
            assertTrue(manifest.containsKey(fn),
                "Missing expected meter function: " + fn);
        }
        assertEquals(expectedFunctions.length, manifest.size(),
            "MeterFunction manifest should have exactly " + expectedFunctions.length + " entries");
    }

    @Test
    @SuppressWarnings("unchecked")
    void meterFunctionManifestClassesAreLoadableAndValid() throws Exception {
        List<String> manifestLines = readManifest("META-INF/annotation-scan/MeterFunction.txt");
        for (String line : manifestLines) {
            String[] parts = line.split("=", 2);
            String functionName = parts[0];
            String className = parts[1];

            Class<?> clazz = Class.forName(className);
            assertTrue(AcceptableValue.class.isAssignableFrom(clazz),
                "MeterFunction class " + className + " should implement AcceptableValue");
            assertTrue(clazz.isAnnotationPresent(MeterFunction.class),
                "MeterFunction class " + className + " should have @MeterFunction annotation");

            MeterFunction mf = clazz.getAnnotation(MeterFunction.class);
            assertEquals(functionName, mf.functionName(),
                "functionName mismatch for " + className);
        }
    }

    // =========================================================================
    // 2. OAL-generated classes: loadable and properly annotated
    // =========================================================================

    @Test
    void allOalMetricsClassesLoadAndHaveStreamAnnotation() throws Exception {
        List<String> classes = readManifest("META-INF/oal-metrics-classes.txt");
        assertFalse(classes.isEmpty(), "OAL metrics manifest is empty");
        for (String className : classes) {
            Class<?> clazz = Class.forName(className);
            assertTrue(clazz.isAnnotationPresent(Stream.class),
                "OAL metrics class missing @Stream: " + className);
        }
    }

    @Test
    void allOalDispatcherClassesLoadAndImplementInterface() throws Exception {
        List<String> classes = readManifest("META-INF/oal-dispatcher-classes.txt");
        assertFalse(classes.isEmpty(), "OAL dispatcher manifest is empty");
        for (String className : classes) {
            Class<?> clazz = Class.forName(className);
            assertTrue(SourceDispatcher.class.isAssignableFrom(clazz),
                "OAL dispatcher doesn't implement SourceDispatcher: " + className);
        }
    }

    // =========================================================================
    // 3. Scope registration verification
    // =========================================================================

    @Test
    void allDeclaredScopesRegisteredInDefaultScopeDefine() throws Exception {
        List<String> classes = readManifest("META-INF/annotation-scan/ScopeDeclaration.txt");
        for (String className : classes) {
            Class<?> clazz = Class.forName(className);
            ScopeDeclaration decl = clazz.getAnnotation(ScopeDeclaration.class);
            assertNotNull(decl, "Missing @ScopeDeclaration on " + className);

            String registeredName = DefaultScopeDefine.nameOf(decl.id());
            assertEquals(decl.name(), registeredName,
                "Scope name mismatch for id=" + decl.id() + " on " + className);
        }
    }

    @Test
    void wellKnownScopesAreRegistered() {
        // Verify essential scopes exist after manifest-based initialization
        assertNotNull(DefaultScopeDefine.nameOf(DefaultScopeDefine.SERVICE));
        assertNotNull(DefaultScopeDefine.nameOf(DefaultScopeDefine.SERVICE_INSTANCE));
        assertNotNull(DefaultScopeDefine.nameOf(DefaultScopeDefine.ENDPOINT));
    }

    // =========================================================================
    // 4. Source -> Dispatcher -> Metrics chain
    // =========================================================================

    @Test
    void allStreamAnnotationsReferenceValidScopes() throws Exception {
        // Check hardcoded @Stream classes
        List<String> hardcoded = readManifest("META-INF/annotation-scan/Stream.txt");
        for (String className : hardcoded) {
            Class<?> clazz = Class.forName(className);
            Stream stream = clazz.getAnnotation(Stream.class);
            assertNotNull(stream, "Missing @Stream on " + className);
            assertScopeExists(stream.scopeId(), className);
        }

        // Check OAL-generated @Stream classes
        List<String> oalMetrics = readManifest("META-INF/oal-metrics-classes.txt");
        for (String className : oalMetrics) {
            Class<?> clazz = Class.forName(className);
            Stream stream = clazz.getAnnotation(Stream.class);
            assertNotNull(stream, "Missing @Stream on OAL class " + className);
            assertScopeExists(stream.scopeId(), className);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void hardcodedDispatchersHaveDeclaredSourceScopes() throws Exception {
        List<String> dispatchers = readManifest("META-INF/annotation-scan/SourceDispatcher.txt");
        for (String className : dispatchers) {
            Class<?> clazz = Class.forName(className);
            Class<?> sourceType = extractSourceType(clazz);
            if (sourceType == null) {
                fail("Cannot extract source type from " + className);
            }
            ISource source = (ISource) sourceType.newInstance();
            int scopeId = source.scope();
            assertScopeExists(scopeId,
                className + " (source=" + sourceType.getSimpleName() + ")");
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void oalDispatchersHaveDeclaredSourceScopes() throws Exception {
        List<String> dispatchers = readManifest("META-INF/oal-dispatcher-classes.txt");
        for (String className : dispatchers) {
            Class<?> clazz = Class.forName(className);
            Class<?> sourceType = extractSourceType(clazz);
            if (sourceType == null) {
                fail("Cannot extract source type from " + className);
            }
            ISource source = (ISource) sourceType.newInstance();
            int scopeId = source.scope();
            assertScopeExists(scopeId,
                className + " (source=" + sourceType.getSimpleName() + ")");
        }
    }

    @Test
    void knownScopesHaveDispatchersAndStreamMetrics() throws Exception {
        // Build scope -> dispatchers map from both hardcoded and OAL manifests
        Map<Integer, List<String>> scopeDispatchers = buildScopeDispatcherMap();

        // Build scope -> @Stream metrics map from both hardcoded and OAL manifests
        Map<Integer, List<String>> scopeStreams = buildScopeStreamMap();

        // Verify key scopes have both dispatchers and stream metrics
        int[] keyScopeIds = {
            DefaultScopeDefine.SERVICE,
            DefaultScopeDefine.ENDPOINT,
            DefaultScopeDefine.SERVICE_INSTANCE,
            DefaultScopeDefine.SERVICE_RELATION,
        };

        for (int scopeId : keyScopeIds) {
            String scopeName = DefaultScopeDefine.nameOf(scopeId);

            assertTrue(
                scopeDispatchers.containsKey(scopeId)
                    && !scopeDispatchers.get(scopeId).isEmpty(),
                "Scope " + scopeName + " (id=" + scopeId + ") has no dispatchers"
            );

            assertTrue(
                scopeStreams.containsKey(scopeId)
                    && !scopeStreams.get(scopeId).isEmpty(),
                "Scope " + scopeName + " (id=" + scopeId + ") has no @Stream metrics"
            );
        }
    }

    // =========================================================================
    // 5. MAL pre-compiled classes
    // =========================================================================

    @Test
    void malMeterClassManifestExistsAndIsNotEmpty() throws Exception {
        List<String> entries = readManifest("META-INF/mal-meter-classes.txt");
        assertFalse(entries.isEmpty(), "MAL meter classes manifest should not be empty");
        for (String entry : entries) {
            assertTrue(entry.contains("="),
                "MAL meter class entry should be in format name=FQCN: " + entry);
        }
    }

    @Test
    void malMeterClassesAreLoadable() throws Exception {
        List<String> entries = readManifest("META-INF/mal-meter-classes.txt");
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            String className = parts[1];
            Class<?> clazz = Class.forName(className);
            assertNotNull(clazz, "Should be able to load meter class: " + className);
            // Verify it can be instantiated
            Object instance = clazz.getDeclaredConstructor().newInstance();
            assertNotNull(instance, "Should be able to instantiate: " + className);
        }
    }

    @Test
    void malGroovyScriptManifestExistsAndIsNotEmpty() throws Exception {
        List<String> entries = readManifest("META-INF/mal-groovy-scripts.txt");
        assertFalse(entries.isEmpty(), "MAL groovy scripts manifest should not be empty");
    }

    @Test
    void malGroovyScriptsAreLoadable() throws Exception {
        List<String> entries = readManifest("META-INF/mal-groovy-scripts.txt");
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            String metricName = parts[0];
            String className = parts[1];
            Class<?> clazz = Class.forName(className);
            assertNotNull(clazz,
                "Should be able to load MAL groovy script: " + metricName + " -> " + className);
        }
    }

    @Test
    void malFilterScriptManifestExistsAndScriptsAreLoadable() throws Exception {
        ClassLoader cl = PrecompiledRegistrationTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream("META-INF/mal-filter-scripts.properties")) {
            assertNotNull(is, "Filter script manifest should exist");
            Properties props = new Properties();
            props.load(is);
            assertFalse(props.isEmpty(), "Filter script manifest should not be empty");
            for (Object value : props.values()) {
                String className = (String) value;
                Class<?> clazz = Class.forName(className);
                assertNotNull(clazz, "Should be able to load filter script: " + className);
            }
        }
    }

    // =========================================================================
    // 6. LAL pre-compiled classes
    // =========================================================================

    @Test
    void lalScriptManifestExistsAndIsNotEmpty() throws Exception {
        List<String> entries = readManifest("META-INF/lal-scripts.txt");
        assertFalse(entries.isEmpty(), "LAL scripts manifest should not be empty");
    }

    @Test
    void lalScriptsAreLoadable() throws Exception {
        List<String> entries = readManifest("META-INF/lal-scripts.txt");
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            String scriptName = parts[0];
            String className = parts[1];
            Class<?> clazz = Class.forName(className);
            assertNotNull(clazz,
                "Should be able to load LAL script: " + scriptName + " -> " + className);
        }
    }

    @Test
    void lalHashManifestExistsAndScriptsAreLoadable() throws Exception {
        List<String> entries = readManifest("META-INF/lal-scripts-by-hash.txt");
        assertFalse(entries.isEmpty(), "LAL hash manifest should not be empty");
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            String className = parts[1];
            Class<?> clazz = Class.forName(className);
            assertNotNull(clazz, "Should be able to load LAL script by hash: " + className);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void assertScopeExists(int scopeId, String context) {
        try {
            DefaultScopeDefine.nameOf(scopeId);
        } catch (Exception e) {
            fail("Invalid scope id=" + scopeId + " referenced by " + context);
        }
    }

    @SuppressWarnings("deprecation")
    private Map<Integer, List<String>> buildScopeDispatcherMap() throws Exception {
        Map<Integer, List<String>> map = new HashMap<>();

        List<String> allDispatchers = new ArrayList<>();
        allDispatchers.addAll(readManifest("META-INF/annotation-scan/SourceDispatcher.txt"));
        allDispatchers.addAll(readManifest("META-INF/oal-dispatcher-classes.txt"));

        for (String className : allDispatchers) {
            Class<?> clazz = Class.forName(className);
            Class<?> sourceType = extractSourceType(clazz);
            if (sourceType != null) {
                ISource source = (ISource) sourceType.newInstance();
                map.computeIfAbsent(source.scope(), k -> new ArrayList<>()).add(className);
            }
        }
        return map;
    }

    private Map<Integer, List<String>> buildScopeStreamMap() throws Exception {
        Map<Integer, List<String>> map = new HashMap<>();

        List<String> allStreams = new ArrayList<>();
        allStreams.addAll(readManifest("META-INF/annotation-scan/Stream.txt"));
        allStreams.addAll(readManifest("META-INF/oal-metrics-classes.txt"));

        for (String className : allStreams) {
            Class<?> clazz = Class.forName(className);
            Stream stream = clazz.getAnnotation(Stream.class);
            if (stream != null) {
                map.computeIfAbsent(stream.scopeId(), k -> new ArrayList<>()).add(className);
            }
        }
        return map;
    }

    private static Class<?> extractSourceType(Class<?> dispatcherClass) {
        for (Type genericInterface : dispatcherClass.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType) {
                ParameterizedType parameterized = (ParameterizedType) genericInterface;
                if (parameterized.getRawType().getTypeName().equals(
                    SourceDispatcher.class.getName())) {
                    Type[] args = parameterized.getActualTypeArguments();
                    if (args.length == 1 && args[0] instanceof Class<?>) {
                        return (Class<?>) args[0];
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> guavaScanMeterFunctions() throws IOException {
        ClassPath classpath = ClassPath.from(
            PrecompiledRegistrationTest.class.getClassLoader());
        ImmutableSet<ClassPath.ClassInfo> classes =
            classpath.getTopLevelClassesRecursive("org.apache.skywalking");
        Map<String, String> result = new HashMap<>();
        for (ClassPath.ClassInfo info : classes) {
            try {
                Class<?> clazz = info.load();
                if (clazz.isAnnotationPresent(MeterFunction.class)
                    && AcceptableValue.class.isAssignableFrom(clazz)) {
                    MeterFunction mf = clazz.getAnnotation(MeterFunction.class);
                    result.put(mf.functionName(), clazz.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        return result;
    }

    private static Set<String> guavaScanAnnotation(
        Class<? extends java.lang.annotation.Annotation> annotationType) throws IOException {
        ClassPath classpath = ClassPath.from(
            PrecompiledRegistrationTest.class.getClassLoader());
        ImmutableSet<ClassPath.ClassInfo> classes =
            classpath.getTopLevelClassesRecursive("org.apache.skywalking");
        Set<String> result = new TreeSet<>();
        for (ClassPath.ClassInfo info : classes) {
            try {
                Class<?> clazz = info.load();
                if (clazz.isAnnotationPresent(annotationType)) {
                    result.add(clazz.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        return result;
    }

    private static Set<String> guavaScanInterface(Class<?> interfaceType) throws IOException {
        ClassPath classpath = ClassPath.from(
            PrecompiledRegistrationTest.class.getClassLoader());
        ImmutableSet<ClassPath.ClassInfo> classes =
            classpath.getTopLevelClassesRecursive("org.apache.skywalking");
        Set<String> result = new TreeSet<>();
        for (ClassPath.ClassInfo info : classes) {
            try {
                Class<?> clazz = info.load();
                if (!clazz.isInterface()
                    && !Modifier.isAbstract(clazz.getModifiers())
                    && interfaceType.isAssignableFrom(clazz)) {
                    result.add(clazz.getName());
                }
            } catch (NoClassDefFoundError | Exception ignored) {
            }
        }
        return result;
    }

    private static Set<String> readManifestAsSet(String resourcePath) throws IOException {
        return new TreeSet<>(readManifest(resourcePath));
    }

    private static List<String> readManifest(String resourcePath) throws IOException {
        List<String> lines = new ArrayList<>();
        ClassLoader cl = PrecompiledRegistrationTest.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Manifest not found: " + resourcePath);
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        }
        return lines;
    }
}
