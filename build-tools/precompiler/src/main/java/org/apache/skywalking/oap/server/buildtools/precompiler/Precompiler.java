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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.core.dsl.debug.DSLDebugCodegenSwitch;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.annotation.AnnotationScan;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;

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

        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        OALDefine[] oalDefines = OalPass.effectiveOALDefines(OalPass.discoverOALDefines(), overlay);
        OalPass.validateOALScripts(oalDefines);
        OalPass.requireEveryScriptCompiled(oalDefines, OalPass.stagedOalScripts());

        declareScopes();
        log.info("Initialized DefaultScopeDefine scope registry");

        // Same switch upstream's DSLDebuggingModuleProvider.prepare() flips at boot: every OAL / MAL /
        // LAL generator then emits the GateHolder field and probe call sites, so the pre-compiled
        // classes are debuggable by the dsl-debugging module at runtime (upstream has it on by default).
        DSLDebugCodegenSwitch.enableInjection();

        OalPass.compileOAL(outputDir, oalDefines, overlay);

        final ClasspathScans.Result scans = ClasspathScans.writeManifests(Path.of(outputDir, "META-INF"));

        // layer-extensions.yml and the LayerExtension SPI, as Core registers them before any rule loads
        LayerExtensionLoader.load();
        // Core's hierarchy service checks the hierarchy now, before any rule file's own layers register.
        HierarchyPass.checkHierarchy(RuleCatalog.HIERARCHY_DEFINITION);

        MeterSystem meterSystem = MalPass.compileMAL(outputDir, scans.meterFunctions());

        // Every TAL file's inline layers before LAL checks rules against the registered layers: at boot the
        // module start order decides which rule file's layers a LAL rule sees, so the image takes them all. A file
        // requiring a module the image does not ship is skipped first, as upstream skips it, its layers unregistered
        ModuleManager shipped = TalPass.shippedModules();
        final String talPath = RuleCatalog.TAL.getDirectory();
        TalPass.registerLayers(talPath, shipped);

        LalPass.compileLAL(outputDir, RuleCatalog.LAL.getDirectory());
        RuleSourceExport.exportOalScripts(outputDir, oalDefines);
        RuleSourceExport.exportRuleSources(outputDir, RuleCatalog.mergedByLoader().stream()
            .filter(c -> c != RuleCatalog.TAL).collect(Collectors.toList()));

        // TAL's metrics share MAL's MeterSystem
        TalPass.compileTAL(outputDir, meterSystem, talPath, shipped);
        RuleSourceExport.exportRuleSources(outputDir, List.of(RuleCatalog.TAL));

        HierarchyPass.compileHierarchy(outputDir, RuleCatalog.HIERARCHY_DEFINITION);

        // The static rule registry the runtime reads instead of rule files and the manifests above
        RegistryGenerator.generate(Path.of(outputDir), registrySources);

        NativeImageMetadata.generateNativeImageConfig(outputDir, scans.scannedClassManifests());

        log.info("Precompiler: done");
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
}
