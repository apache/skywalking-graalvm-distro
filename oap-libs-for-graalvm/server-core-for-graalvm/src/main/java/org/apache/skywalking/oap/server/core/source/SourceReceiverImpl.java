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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.DispatcherManager;
import org.apache.skywalking.oap.server.core.analysis.SourceDecoratorManager;

/**
 * GraalVM replacement for upstream SourceReceiverImpl.
 * Original: skywalking/oap-server/server-core/src/main/java/.../source/SourceReceiverImpl.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Reads dispatcher/decorator manifest files instead of Guava ClassPath.from() scanning.
 * Why: Guava ClassPath scanning is incompatible with GraalVM native image.
 */
@Slf4j
public class SourceReceiverImpl implements SourceReceiver {

    private static final String DISPATCHER_MANIFEST = "META-INF/annotation-scan/SourceDispatcher.txt";
    private static final String DECORATOR_MANIFEST = "META-INF/annotation-scan/ISourceDecorator.txt";

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
        List<String> dispatcherNames = readManifest(DISPATCHER_MANIFEST);
        for (String className : dispatcherNames) {
            try {
                Class<?> aClass = Class.forName(className);
                dispatcherManager.addIfAsSourceDispatcher(aClass);
            } catch (ClassNotFoundException e) {
                log.warn("Dispatcher class not found: {}", className);
            }
        }
        log.info("Registered {} source dispatchers from manifest", dispatcherNames.size());

        List<String> decoratorNames = readManifest(DECORATOR_MANIFEST);
        for (String className : decoratorNames) {
            try {
                Class<?> aClass = Class.forName(className);
                sourceDecoratorManager.addIfAsSourceDecorator(aClass);
            } catch (ClassNotFoundException e) {
                log.warn("Decorator class not found: {}", className);
            }
        }
        log.info("Registered {} source decorators from manifest", decoratorNames.size());
    }

    private static List<String> readManifest(String resourcePath) throws IOException {
        List<String> lines = new ArrayList<>();
        ClassLoader cl = SourceReceiverImpl.class.getClassLoader();
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
