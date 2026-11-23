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

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.DisableRegister;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.OalScript;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * GraalVM replacement for upstream OALEngineLoaderService.
 * Original: skywalking/oap-server/server-core/src/main/java/.../oal/rt/OALEngineLoaderService.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: {@link #load(OALDefine)} registers the disables, then the metrics and dispatcher classes, the precompiler
 * compiled for the define's script, listed by the generated {@link CompiledRules} registry, as upstream's
 * {@code OALClassGeneratorV2} registers a script's disables while it generates the classes, which
 * {@code OALEngineV2#notifyAllListeners} then registers. The scripts the rules overlay added, which no provider loads,
 * register at the first load, after the define it loads (upstream's core loads {@code oal/disable.oal} first, so its
 * disables reach every stream).
 * Why: Javassist runtime code generation is incompatible with GraalVM native image.
 */
@Slf4j
public class OALEngineLoaderService implements Service {

    private final Set<OALDefine> oalDefineSet = new HashSet<>();
    private final ModuleManager moduleManager;
    private Map<OALDefine, OalScript> compiledScripts;

    public OALEngineLoaderService(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public Set<OALDefine> getLoadedDefines() {
        return Collections.unmodifiableSet(oalDefineSet);
    }

    public void load(OALDefine define) throws ModuleStartException {
        if (oalDefineSet.contains(define)) {
            return;
        }
        final boolean firstLoad = compiledScripts == null;
        if (firstLoad) {
            compiledScripts = new LinkedHashMap<>();
            for (final OalScript script : CompiledRules.oalScripts()) {
                compiledScripts.put(script.getDefine(), script);
            }
        }
        final OalScript script = compiledScripts.get(define);
        if (script != null) {
            register(script);
        } else if (CompiledRules.oalExcludedScripts().contains(define.getConfigFile())) {
            log.info("OAL script {} was excluded from this image by the rules overlay", define.getConfigFile());
        } else {
            throw new ModuleStartException("OAL script " + define.getConfigFile() + " of " + define.getClass().getName()
                + " is not compiled into this image");
        }
        if (firstLoad) {
            // After the define the boot loads first (oal/disable.oal), so its disables reach the overlay's streams too
            for (final OalScript added : compiledScripts.values()) {
                if (added.isOverlayAdded() && !oalDefineSet.contains(added.getDefine())) {
                    register(added);
                }
            }
        }
    }

    private void register(final OalScript script) throws ModuleStartException {
        // Same guard and message as upstream's OALClassGeneratorV2, which registers them before the streams.
        for (final String disabledSource : script.getDisabledSources()) {
            if (DisableRegister.INSTANCE.isStreamRunning(disabledSource)) {
                log.warn("{}: disable({}) comes after the stream is defined, so it has no effect; put it in "
                             + "oal/disable.oal, which loads first",
                         script.getDefine().getConfigFile(), disabledSource);
                continue;
            }
            DisableRegister.INSTANCE.addFromScript(disabledSource);
        }
        final StreamAnnotationListener streamAnnotationListener = new StreamAnnotationListener(moduleManager);
        final DispatcherDetectorListener dispatcherDetectorListener = moduleManager.find(CoreModule.NAME)
                                                                                   .provider()
                                                                                   .getService(SourceReceiver.class)
                                                                                   .getDispatcherDetectorListener();
        for (Class<?> metricsClass : script.getMetrics()) {
            try {
                streamAnnotationListener.notify(metricsClass);
            } catch (StorageException e) {
                throw new ModuleStartException(e.getMessage(), e);
            }
        }
        for (Class<?> dispatcherClass : script.getDispatchers()) {
            try {
                dispatcherDetectorListener.addIfAsSourceDispatcher(dispatcherClass);
            } catch (Exception e) {
                throw new ModuleStartException(e.getMessage(), e);
            }
        }
        oalDefineSet.add(script.getDefine());
    }
}
