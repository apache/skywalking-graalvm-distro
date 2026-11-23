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

import com.google.common.reflect.ClassPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecompilerTest {

    @Test
    void everyAnnotationAnUpstreamAnnotationListenerListensForGetsAManifest() throws Exception {
        final Set<String> annotations = Precompiler.listenedAnnotations(
            ClassPath.from(Precompiler.class.getClassLoader()).getTopLevelClassesRecursive("org.apache.skywalking"))
            .stream().map(Class::getName).collect(Collectors.toSet());
        // DefaultScopeDefine.Listener, StreamAnnotationListener, DisableRegister and its SingleDisableScanListener
        assertEquals(Set.of(
            "org.apache.skywalking.oap.server.core.source.ScopeDeclaration",
            "org.apache.skywalking.oap.server.core.analysis.Stream",
            "org.apache.skywalking.oap.server.core.analysis.MultipleDisable",
            "org.apache.skywalking.oap.server.core.analysis.Disable"), annotations);
    }

    @Test
    void discoverOALDefinesShouldFindAllExpectedDefines() throws Exception {
        OALDefine[] defines = Precompiler.discoverOALDefines();
        Set<String> actualScripts = Arrays.stream(defines)
            .map(OALDefine::getConfigFile)
            .collect(Collectors.toSet());

        // Verify all known OAL scripts are discovered
        assertTrue(actualScripts.contains("oal/core.oal"), "Should discover core.oal");
        assertTrue(actualScripts.contains("oal/disable.oal"), "Should discover disable.oal");
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        assertDoesNotThrow(() -> Precompiler.requireEveryScriptCompiled(
            Precompiler.effectiveOALDefines(defines, overlay), Precompiler.stagedOalScripts()));
    }

    @Test
    void aStagedOalScriptNoDefineNamesFailsTheBuild() {
        final OALDefine[] defines = {new Precompiler.OverlayOALDefine("oal/fleet.oal")};
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Precompiler.requireEveryScriptCompiled(defines, List.of("oal/fleet.oal", "oal/orphan.oal")));
        assertTrue(e.getMessage().contains("[oal/orphan.oal]"), e.getMessage());
    }

    @Test
    void anOalDefineWithoutItsOwnStaticInstanceFailsTheBuild() {
        assertSame(Singleton.INSTANCE, Precompiler.defineOf(Singleton.class));
        for (final Class<?> define : List.of(NoInstance.class, ForeignInstance.class)) {
            final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Precompiler.defineOf(define));
            assertTrue(e.getMessage().contains(define.getName()), e.getMessage());
        }
    }

    // Nested, so the classpath scan for OALDefine subclasses does not find them.

    public static final class Singleton extends OALDefine {
        public static final Singleton INSTANCE = new Singleton();

        private Singleton() {
            super("oal/singleton.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    public static final class NoInstance extends OALDefine {
        public NoInstance() {
            super("oal/no-instance.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    public static final class ForeignInstance extends OALDefine {
        public static final OALDefine INSTANCE = Singleton.INSTANCE;

        private ForeignInstance() {
            super("oal/foreign-instance.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    @Test
    void allDiscoveredOALScriptsShouldBeOnClasspath() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        // A script the rules overlay excluded is not compiled, so it is not there.
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        for (OALDefine define : Precompiler.effectiveOALDefines(Precompiler.discoverOALDefines(), overlay)) {
            assertNotNull(
                cl.getResource(define.getConfigFile()),
                "OAL script not found on classpath: " + define.getConfigFile()
                    + " (" + define.getClass().getSimpleName() + ")"
            );
        }
    }

    @Test
    void exportShouldGenerateClassesForEachNonDisableDefine(@TempDir Path tempDir)
        throws Exception {
        String outputDir = tempDir.toString();

        // Run the full export
        Precompiler.main(new String[] {outputDir});

        // The manifests reflect-config.json is generated from list every class generated, builders included, so an
        // overlay's script has them. The bundled scripts generate some; an overlay's may generate none.
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        final boolean bundledOal = Stream.of(overlay.added(), overlay.overridden(), overlay.excluded())
                                         .flatMap(Set::stream).noneMatch(f -> f.startsWith("oal/"));
        final Path oalRt = tempDir.resolve("org/apache/skywalking/oap/server/core/source/oal/rt");
        final Path metaInf = tempDir.resolve("META-INF");
        for (final String[] kind : new String[][] {
            {"metrics", "oal-metrics-classes.txt"},
            {"dispatcher", "oal-dispatcher-classes.txt"},
            {"metrics/builder", "oal-builder-classes.txt"}}) {
            final long generated = countClasses(oalRt.resolve(kind[0]));
            assertEquals(generated, Files.readAllLines(metaInf.resolve(kind[1])).size(), kind[1]);
            assertTrue(!bundledOal || generated > 0, "no " + kind[0] + " class generated");
        }

        // Verify disabled sources manifest exists (may be empty when all disable() calls are commented out)
        Path disabledManifest = tempDir.resolve("META-INF/oal-disabled-sources.txt");
        assertTrue(Files.exists(disabledManifest), "disabled sources manifest should exist");

        // Verify annotation scan manifests
        Path annotationScanDir = tempDir.resolve("META-INF/annotation-scan");
        assertTrue(Files.isDirectory(annotationScanDir), "annotation-scan directory should exist");

        assertManifestNotEmpty(annotationScanDir, "ScopeDeclaration.txt");
        assertManifestNotEmpty(annotationScanDir, "Stream.txt");
        assertManifestNotEmpty(annotationScanDir, "SourceDispatcher.txt");
        assertManifestNotEmpty(annotationScanDir, "ISourceDecorator.txt");

        // Disable/MultipleDisable manifests exist but may be empty (no classes use these annotations currently)
        assertTrue(
            Files.exists(annotationScanDir.resolve("Disable.txt")),
            "Disable manifest should exist"
        );
        assertTrue(
            Files.exists(annotationScanDir.resolve("MultipleDisable.txt")),
            "MultipleDisable manifest should exist"
        );

        // Verify MeterFunction manifest: should have 16 entries in functionName=FQCN format
        assertManifestNotEmpty(annotationScanDir, "MeterFunction.txt");
        List<String> meterFunctionLines = Files.readAllLines(
            annotationScanDir.resolve("MeterFunction.txt"));
        assertTrue(meterFunctionLines.size() >= 16,
            "MeterFunction manifest should have at least 16 entries, got " + meterFunctionLines.size());
        for (String line : meterFunctionLines) {
            assertTrue(line.contains("="),
                "MeterFunction manifest line should be in functionName=FQCN format: " + line);
            String[] parts = line.split("=", 2);
            assertFalse(parts[0].isEmpty(), "functionName should not be empty: " + line);
            assertFalse(parts[1].isEmpty(), "FQCN should not be empty: " + line);
        }
    }

    private static long countClasses(final Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".class")).count();
        }
    }

    private void assertManifestNotEmpty(Path dir, String fileName) throws Exception {
        Path manifest = dir.resolve(fileName);
        assertTrue(Files.exists(manifest), fileName + " should exist");
        List<String> lines = Files.readAllLines(manifest);
        assertFalse(lines.isEmpty(), fileName + " should not be empty");
    }
}
