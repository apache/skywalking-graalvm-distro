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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecompilerTest {

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
