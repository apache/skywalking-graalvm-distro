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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.graalvm.rules.BuildManifest;

/**
 * GraalVM replacement for upstream AnnotationScan.
 * Original: skywalking/oap-server/server-core/src/main/java/.../annotation/AnnotationScan.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: the classes come from the manifest META-INF/annotation-scan/{AnnotationSimpleName}.txt, which the
 * precompiler writes for every AnnotationListener's annotation, instead of a Guava ClassPath scan.
 * Why: Guava ClassPath scanning is incompatible with GraalVM native image.
 */
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
            String manifest = MANIFEST_PREFIX + listener.annotation().getSimpleName() + ".txt";
            for (Class<?> aClass : BuildManifest.classes(manifest)) {
                if (aClass.isAnnotationPresent(listener.annotation())) {
                    listener.addMatch(aClass);
                }
            }
        }

        for (AnnotationListenerCache listener : listeners) {
            listener.complete();
        }
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
