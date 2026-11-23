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

package org.apache.skywalking.oap.server.graalvm.rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A manifest the precompiler packed into the image: the classes a class path scan found at build time, or the index of
 * the bundled rule sources. The build writes every manifest the runtime reads, so a missing one, or a class it lists
 * that does not load, fails the boot, as upstream's scan fails on a class it cannot load. So does a second copy of a
 * packed resource on the class path (a stale one under {@code config/}, which the JVM distribution puts first), which
 * would be read instead of the one the image was built with.
 */
public final class BuildManifest {
    private BuildManifest() {
    }

    /**
     * @return the non-blank lines of the manifest, trimmed
     */
    public static List<String> lines(final String resource) {
        return lines(BuildManifest.class.getClassLoader(), resource);
    }

    static List<String> lines(final ClassLoader classLoader, final String resource) {
        final List<String> lines = new ArrayList<>();
        try (InputStream in = open(classLoader, resource)) {
            if (in == null) {
                throw new IllegalStateException("The build-time manifest " + resource + " is not in the image");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the build-time manifest " + resource, e);
        }
        return lines;
    }

    /**
     * @return the one copy of a resource the precompiler packed, null when there is none
     * @throws IllegalStateException when the class path holds more than one copy of it
     */
    public static InputStream open(final ClassLoader classLoader, final String resource) throws IOException {
        final List<URL> copies = Collections.list(classLoader.getResources(resource));
        if (copies.size() > 1) {
            throw new IllegalStateException("The build-time resource " + resource + " is on the class path "
                + copies.size() + " times " + copies + "; the image reads the one it was built with only, remove the "
                + "others");
        }
        return copies.isEmpty() ? null : copies.get(0).openStream();
    }

    /**
     * @return the classes the manifest lists, in its order
     */
    public static List<Class<?>> classes(final String resource) {
        final List<Class<?>> classes = new ArrayList<>();
        for (final String name : lines(resource)) {
            try {
                classes.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("The build-time manifest " + resource + " lists " + name
                    + ", which is not in the image", e);
            }
        }
        return classes;
    }
}
