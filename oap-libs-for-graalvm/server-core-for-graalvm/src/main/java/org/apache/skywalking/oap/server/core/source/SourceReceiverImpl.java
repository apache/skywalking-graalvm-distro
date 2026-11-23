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

package org.apache.skywalking.oap.server.core.source;

import java.io.IOException;
import lombok.Getter;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.DispatcherManager;
import org.apache.skywalking.oap.server.core.analysis.SourceDecoratorManager;
import org.apache.skywalking.oap.server.graalvm.rules.BuildManifest;

/**
 * GraalVM replacement for upstream SourceReceiverImpl.
 * Original: skywalking/oap-server/server-core/src/main/java/.../source/SourceReceiverImpl.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: the decorators and dispatchers come from the manifests the precompiler's class path scan wrote, instead of a
 * Guava ClassPath scan.
 * Why: Guava ClassPath scanning is incompatible with GraalVM native image.
 */
public class SourceReceiverImpl implements SourceReceiver {

    private static final String DECORATOR_MANIFEST = "META-INF/annotation-scan/ISourceDecorator.txt";
    private static final String DISPATCHER_MANIFEST = "META-INF/annotation-scan/SourceDispatcher.txt";

    @Getter
    private final DispatcherManager dispatcherManager;

    @Getter
    private final SourceDecoratorManager sourceDecoratorManager;

    public SourceReceiverImpl() {
        this.dispatcherManager = new DispatcherManager();
        this.sourceDecoratorManager = new SourceDecoratorManager();
    }

    @Override
    public void receive(ISource source) {
        dispatcherManager.forward(source);
    }

    @Override
    public DispatcherDetectorListener getDispatcherDetectorListener() {
        return getDispatcherManager();
    }

    public void scan() throws IOException, InstantiationException, IllegalAccessException {
        for (Class<?> aClass : BuildManifest.classes(DECORATOR_MANIFEST)) {
            sourceDecoratorManager.addIfAsSourceDecorator(aClass);
        }
        for (Class<?> aClass : BuildManifest.classes(DISPATCHER_MANIFEST)) {
            dispatcherManager.addIfAsSourceDispatcher(aClass);
        }
    }
}
