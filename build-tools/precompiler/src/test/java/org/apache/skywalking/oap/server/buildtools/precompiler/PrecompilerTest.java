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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecompilerTest {

    /**
     * All expected OAL script files. If a new OALDefine is added upstream,
     * this test will fail — reminding us to update ALL_DEFINES.
     */
    private static final Set<String> EXPECTED_OAL_SCRIPTS = Set.of(
        "oal/disable.oal",
        "oal/core.oal",
        "oal/java-agent.oal",
        "oal/dotnet-agent.oal",
        "oal/browser.oal",
        "oal/mesh.oal",
        "oal/ebpf.oal",
        "oal/tcp.oal",
        "oal/cilium.oal"
    );

    @Test
    void allDefinesShouldCoverExpectedOALScripts() {
        Set<String> actualScripts = Arrays.stream(Precompiler.ALL_DEFINES)
            .map(OALDefine::getConfigFile)
            .collect(Collectors.toSet());

        assertEquals(
            EXPECTED_OAL_SCRIPTS, actualScripts,
            "ALL_DEFINES does not match expected OAL scripts"
        );
    }

    @Test
    void allOALScriptsShouldBeOnClasspath() {
        ClassLoader cl = getClass().getClassLoader();
        for (OALDefine define : Precompiler.ALL_DEFINES) {
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

        // Verify metrics classes generated
        Path metricsDir = tempDir.resolve(
            "org/apache/skywalking/oap/server/core/source/oal/rt/metrics");
        assertTrue(Files.isDirectory(metricsDir), "metrics directory should exist");

        long metricsCount = Files.list(metricsDir)
            .filter(p -> p.toString().endsWith(".class"))
            .count();
        assertTrue(metricsCount > 0, "Should generate metrics classes, got 0");

        // Verify dispatcher classes generated
        Path dispatcherDir = tempDir.resolve(
            "org/apache/skywalking/oap/server/core/source/oal/rt/dispatcher");
        assertTrue(Files.isDirectory(dispatcherDir), "dispatcher directory should exist");

        long dispatcherCount = Files.list(dispatcherDir)
            .filter(p -> p.toString().endsWith(".class"))
            .count();
        assertTrue(dispatcherCount > 0, "Should generate dispatcher classes, got 0");

        // Verify builder classes generated
        Path builderDir = tempDir.resolve(
            "org/apache/skywalking/oap/server/core/source/oal/rt/metrics/builder");
        assertTrue(Files.isDirectory(builderDir), "builder directory should exist");

        long builderCount = Files.list(builderDir)
            .filter(p -> p.toString().endsWith(".class"))
            .count();
        assertTrue(builderCount > 0, "Should generate builder classes, got 0");

        // Verify manifest files
        Path metricsManifest = tempDir.resolve("META-INF/oal-metrics-classes.txt");
        assertTrue(Files.exists(metricsManifest), "metrics manifest should exist");
        List<String> metricsLines = Files.readAllLines(metricsManifest);
        assertFalse(metricsLines.isEmpty(), "metrics manifest should not be empty");

        Path dispatcherManifest = tempDir.resolve("META-INF/oal-dispatcher-classes.txt");
        assertTrue(Files.exists(dispatcherManifest), "dispatcher manifest should exist");
        List<String> dispatcherLines = Files.readAllLines(dispatcherManifest);
        assertFalse(dispatcherLines.isEmpty(), "dispatcher manifest should not be empty");

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

    private void assertManifestNotEmpty(Path dir, String fileName) throws Exception {
        Path manifest = dir.resolve(fileName);
        assertTrue(Files.exists(manifest), fileName + " should exist");
        List<String> lines = Files.readAllLines(manifest);
        assertFalse(lines.isEmpty(), fileName + " should not be empty");
    }
}
