/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.skywalking.oap.server.core.oal.rt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.Service;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Changed the way of register scpopes due to the Graal limitation on scanning classpath.
 * Now, A class will be generated during compile time, it contains all the scopes that need to be registered, see (@link org.apache.skywalking.graal.OALGenerator) and register at runtime.
 */
@Slf4j
@RequiredArgsConstructor
public class OALEngineLoaderService implements Service {

    private final Set<OALDefine> oalDefineSet = new HashSet<>();
    private final ModuleManager moduleManager;

    private static boolean SCOPE_REGISTERED = false;

    public void load(OALDefine define) throws ModuleStartException {

        if (oalDefineSet.contains(define)) {
            return;
        }
        try {
            OALEngine engine = loadOALEngine(define);
            DispatcherDetectorListener dispatcherDetectorListener = moduleManager.find(CoreModule.NAME)
                    .provider()
                    .getService(SourceReceiver.class)
                    .getDispatcherDetectorListener();
            engine.setDispatcherListener(dispatcherDetectorListener);
            if (!SCOPE_REGISTERED && Objects.equals(System.getProperty("org.graalvm.nativeimage.imagecode"), "runtime")) {
                registerAllScope(dispatcherDetectorListener);
                SCOPE_REGISTERED = true;
            }

            StreamAnnotationListener streamAnnotationListener = new StreamAnnotationListener(moduleManager);
            engine.setStreamListener(streamAnnotationListener);

            engine.setStorageBuilderFactory(moduleManager.find(StorageModule.NAME)
                                                         .provider()
                                                         .getService(StorageBuilderFactory.class));

            engine.start(OALEngineLoaderService.class.getClassLoader());
            engine.notifyAllListeners();

            oalDefineSet.add(define);
        } catch (ReflectiveOperationException | OALCompileException e) {
            throw new ModuleStartException(e.getMessage(), e);
        }
    }

    private void registerAllScope(DispatcherDetectorListener dispatcherDetectorListener) {
        try {
            Class<?> scannedClasses = Class.forName("org.apache.skywalking.oap.graal.ScannedClasses");
            List<Class> streamClasses = (List<Class>) scannedClasses.getDeclaredField("streamClasses").get(null);
            List<Class> scopeDeclareClasses = (List<Class>) scannedClasses.getDeclaredField("scopeDeclarationClasses").get(null);
            List<Class> dispatcherClasses = (List<Class>) scannedClasses.getDeclaredField("dispatcherClasses").get(null);
            DefaultScopeDefine.Listener listener = new DefaultScopeDefine.Listener();
            StreamAnnotationListener streamAnnotationListener = new StreamAnnotationListener(moduleManager);

            dispatcherClasses.forEach(clazz -> {
                try {
                    dispatcherDetectorListener.addIfAsSourceDispatcher(clazz);
                } catch (IllegalAccessException | InstantiationException e) {
                    log.error("add dispatcher:" + clazz + "failed", e);
                }
            });
            scopeDeclareClasses.forEach(
                    listener::notify
            );
            streamClasses.forEach(clazz -> {
                try {
                     streamAnnotationListener.notify(clazz);
                } catch (StorageException e) {
                    log.error("notify class:" + clazz + "failed", e);
                }
            });
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            log.error("register scopes failed");
        }
    }

    private static OALEngine loadOALEngine(OALDefine define) throws ReflectiveOperationException {
        Class<?> engineRTClass = Class.forName("org.apache.skywalking.oal.rt.OALRuntime");
        Constructor<?> engineRTConstructor = engineRTClass.getConstructor(OALDefine.class);
        return (OALEngine) engineRTConstructor.newInstance(define);
    }
}
