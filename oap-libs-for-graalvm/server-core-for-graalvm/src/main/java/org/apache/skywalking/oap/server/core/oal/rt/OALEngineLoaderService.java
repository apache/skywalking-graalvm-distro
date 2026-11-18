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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * GraalVM replacement for upstream OALEngineLoaderService.
 * Original: skywalking/oap-server/server-core/src/main/java/.../oal/rt/OALEngineLoaderService.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Registers the OAL metrics and dispatcher classes the precompiler generated, listed by
 * the generated {@link CompiledRules} registry, instead of running ANTLR4 + FreeMarker + Javassist at runtime.
 * Why: Javassist runtime code generation is incompatible with GraalVM native image.
 */
@Slf4j
public class OALEngineLoaderService implements Service {

    private final Set<OALDefine> oalDefineSet = new HashSet<>();
    private final Set<OALDefine> compiledDefines = new HashSet<>();
    private final ModuleManager moduleManager;
    private boolean loaded;

    public OALEngineLoaderService(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public Set<OALDefine> getLoadedDefines() {
        return oalDefineSet;
    }

    public void load(OALDefine define) throws ModuleStartException {
        if (oalDefineSet.contains(define)) {
            return;
        }

        if (!loaded) {
            loadAllPrecompiledClasses();
            // Every compiled script runs, whichever provider loads it: the read-only OAL catalog lists them all.
            compiledDefines.addAll(CompiledRules.oalDefines());
            oalDefineSet.addAll(compiledDefines);
            loaded = true;
        }

        if (!compiledDefines.contains(define)) {
            // The rules overlay excluded the script at build time: nothing of it runs, and it is not listed.
            log.info("OAL script {} was excluded from this image at build time", define.getConfigFile());
            return;
        }
        oalDefineSet.add(define);
    }

    private void loadAllPrecompiledClasses() throws ModuleStartException {
        try {
            // 1. Register disabled sources before processing metrics
            final List<String> disabledSources = CompiledRules.oalDisabledSources();
            for (String name : disabledSources) {
                DisableRegister.INSTANCE.add(name);
            }
            log.info("Loaded {} disabled sources", disabledSources.size());

            // 2. Register the pre-compiled metrics classes
            StreamAnnotationListener streamListener = new StreamAnnotationListener(moduleManager);
            final List<Class<?>> metricsClasses = CompiledRules.oalMetrics();
            for (Class<?> metricsClass : metricsClasses) {
                streamListener.notify(metricsClass);
            }
            log.info("Registered {} pre-compiled OAL metrics classes", metricsClasses.size());

            // 3. Register the pre-compiled dispatcher classes
            var dispatcherListener = moduleManager.find(CoreModule.NAME)
                                                  .provider()
                                                  .getService(SourceReceiver.class)
                                                  .getDispatcherDetectorListener();
            final List<Class<?>> dispatcherClasses = CompiledRules.oalDispatchers();
            for (Class<?> dispatcherClass : dispatcherClasses) {
                dispatcherListener.addIfAsSourceDispatcher(dispatcherClass);
            }
            log.info("Registered {} pre-compiled OAL dispatcher classes", dispatcherClasses.size());

        } catch (Exception e) {
            throw new ModuleStartException("Failed to load pre-compiled OAL classes", e);
        }
    }
}
