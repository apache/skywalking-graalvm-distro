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

package org.apache.skywalking.oap.server.core.oal.rt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * GraalVM replacement for upstream OALEngineLoaderService.
 * Original: skywalking/oap-server/server-core/src/main/java/.../oal/rt/OALEngineLoaderService.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled OAL metrics/builder/dispatcher classes from
 * build-time manifests instead of running ANTLR4 + FreeMarker + Javassist at runtime.
 * Why: Javassist runtime code generation is incompatible with GraalVM native image.
 *
 * Instead of running the OAL engine (Javassist code generation) at runtime,
 * loads pre-compiled classes from build-time manifests produced by OALClassExporter.
 */
@Slf4j
public class OALEngineLoaderService implements Service {

    private static final String METRICS_MANIFEST = "META-INF/oal-metrics-classes.txt";
    private static final String DISPATCHER_MANIFEST = "META-INF/oal-dispatcher-classes.txt";
    private static final String DISABLED_MANIFEST = "META-INF/oal-disabled-sources.txt";

    private final Set<OALDefine> oalDefineSet = new HashSet<>();
    private final ModuleManager moduleManager;
    private boolean loaded;

    public OALEngineLoaderService(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public void load(OALDefine define) throws ModuleStartException {
        if (oalDefineSet.contains(define)) {
            return;
        }

        if (!loaded) {
            loadAllPrecompiledClasses();
            loaded = true;
        }

        oalDefineSet.add(define);
    }

    private void loadAllPrecompiledClasses() throws ModuleStartException {
        try {
            // 1. Register disabled sources before processing metrics
            List<String> disabledSources = readManifest(DISABLED_MANIFEST);
            for (String name : disabledSources) {
                DisableRegister.INSTANCE.add(name);
            }
            log.info("Loaded {} disabled sources from manifest", disabledSources.size());

            // 2. Load and register pre-compiled metrics classes
            StreamAnnotationListener streamListener = new StreamAnnotationListener(moduleManager);
            List<String> metricsClassNames = readManifest(METRICS_MANIFEST);
            for (String className : metricsClassNames) {
                Class<?> metricsClass = Class.forName(className);
                streamListener.notify(metricsClass);
            }
            log.info("Registered {} pre-compiled OAL metrics classes", metricsClassNames.size());

            // 3. Load and register pre-compiled dispatcher classes
            var dispatcherListener = moduleManager.find(CoreModule.NAME)
                                                  .provider()
                                                  .getService(SourceReceiver.class)
                                                  .getDispatcherDetectorListener();
            List<String> dispatcherClassNames = readManifest(DISPATCHER_MANIFEST);
            for (String className : dispatcherClassNames) {
                Class<?> dispatcherClass = Class.forName(className);
                dispatcherListener.addIfAsSourceDispatcher(dispatcherClass);
            }
            log.info("Registered {} pre-compiled OAL dispatcher classes", dispatcherClassNames.size());

        } catch (Exception e) {
            throw new ModuleStartException("Failed to load pre-compiled OAL classes", e);
        }
    }

    private static List<String> readManifest(String resourcePath) throws IOException {
        List<String> lines = new ArrayList<>();
        ClassLoader cl = OALEngineLoaderService.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Manifest not found: {}", resourcePath);
                return lines;
            }
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
