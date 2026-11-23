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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource the precompiler packed is read from its one copy: a second copy on the class path, such as a stale
 * {@code config/META-INF/rule-source/index.txt} the JVM distribution would read first, fails the boot.
 */
class BuildManifestTest {
    @TempDir
    Path root;

    @Test
    void aPackedResourceIsReadFromItsOneCopy() throws Exception {
        final Path registry = write("registry", "lal|default.yaml\nlal|nginx.yaml\n");
        try (URLClassLoader classPath = new URLClassLoader(new URL[] {registry.toUri().toURL()}, null)) {
            assertEquals(List.of("lal|default.yaml", "lal|nginx.yaml"),
                BuildManifest.lines(classPath, RuleSources.INDEX));
            assertNull(BuildManifest.open(classPath, "META-INF/rule-source/none.txt"));
        }
    }

    @Test
    void aSecondCopyOfAPackedResourceFailsTheBoot() throws Exception {
        final Path stale = write("config", "lal|nginx.yaml\n");
        final Path registry = write("registry", "lal|default.yaml\nlal|nginx.yaml\n");
        try (URLClassLoader classPath = new URLClassLoader(
            new URL[] {stale.toUri().toURL(), registry.toUri().toURL()}, null)) {
            final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BuildManifest.lines(classPath, RuleSources.INDEX));
            assertTrue(e.getMessage().contains(RuleSources.INDEX + " is on the class path 2 times"), e.getMessage());
        }
    }

    private Path write(final String directory, final String index) throws Exception {
        final Path file = root.resolve(directory).resolve(RuleSources.INDEX);
        Files.createDirectories(file.getParent());
        Files.writeString(file, index);
        return root.resolve(directory);
    }
}
