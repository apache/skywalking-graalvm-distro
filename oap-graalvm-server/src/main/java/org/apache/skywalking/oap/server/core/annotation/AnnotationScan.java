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

package org.apache.skywalking.oap.server.core.annotation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.storage.StorageException;

/**
 * GraalVM replacement for upstream AnnotationScan.
 * Same FQCN — shadows the upstream class via Maven classpath precedence.
 *
 * Instead of Guava ClassPath scanning, reads pre-built manifest files
 * produced by OALClassExporter at build time.
 * Manifest path: META-INF/annotation-scan/{AnnotationSimpleName}.txt
 */
@Slf4j
public class AnnotationScan {

    private static final String MANIFEST_PREFIX = "META-INF/annotation-scan/";

    private final List<AnnotationListenerCache> listeners;

    public AnnotationScan() {
        this.listeners = new LinkedList<>();
    }

    public void registerListener(AnnotationListener listener) {
        listeners.add(new AnnotationListenerCache(listener));
    }

    public void scan() throws IOException, StorageException {
        for (AnnotationListenerCache listener : listeners) {
            String annotationName = listener.annotation().getSimpleName();
            String manifestPath = MANIFEST_PREFIX + annotationName + ".txt";

            List<String> classNames = readManifest(manifestPath);
            for (String className : classNames) {
                try {
                    Class<?> aClass = Class.forName(className);
                    if (aClass.isAnnotationPresent(listener.annotation())) {
                        listener.addMatch(aClass);
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Class not found from manifest {}: {}", manifestPath, className);
                }
            }
        }

        for (AnnotationListenerCache listener : listeners) {
            listener.complete();
        }
    }

    private static List<String> readManifest(String resourcePath) throws IOException {
        List<String> lines = new ArrayList<>();
        ClassLoader cl = AnnotationScan.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Annotation manifest not found: {}", resourcePath);
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

    private static class AnnotationListenerCache {
        private final AnnotationListener listener;
        private final List<Class<?>> matchedClass;

        private AnnotationListenerCache(AnnotationListener listener) {
            this.listener = listener;
            this.matchedClass = new LinkedList<>();
        }

        private Class<? extends Annotation> annotation() {
            return this.listener.annotation();
        }

        private void addMatch(Class<?> aClass) {
            matchedClass.add(aClass);
        }

        private void complete() throws StorageException {
            matchedClass.sort(Comparator.comparing(Class::getName));
            for (Class<?> aClass : matchedClass) {
                listener.notify(aClass);
            }
        }
    }
}
