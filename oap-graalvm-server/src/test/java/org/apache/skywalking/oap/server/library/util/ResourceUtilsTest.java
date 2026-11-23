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

package org.apache.skywalking.oap.server.library.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceUtilsTest {
    private static final String CLASS_PATH_RESOURCE = "provider-inventory.properties";

    @TempDir
    Path home;

    @Test
    void theJvmReadsTheClassPathAsUpstream() throws Exception {
        assertNull(ResourceUtils.configDirectory());
        try (InputStream in = ResourceUtils.readToStream(CLASS_PATH_RESOURCE)) {
            assertTrue(in.readAllBytes().length > 0);
        }
    }

    @Test
    void theNativeImageReadsTheConfigDirectoryNextToItsExecutable() throws Exception {
        final Path config = Files.createDirectories(home.resolve("config"));
        Files.writeString(config.resolve("application.yml"), "core: {}");
        final Path dir = ResourceUtils.configDirectory(home.resolve("oap-server"));

        assertEquals(config.toAbsolutePath(), dir);
        try (InputStream in = ResourceUtils.readToStream(dir, "application.yml")) {
            assertEquals("core: {}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void theNativeImageFailsWithoutAConfigDirectory() {
        final IllegalStateException e = assertThrows(
            IllegalStateException.class, () -> ResourceUtils.configDirectory(home.resolve("oap-server")));
        assertTrue(e.getMessage().contains(home.resolve("config").toString()), e.getMessage());
    }

    @Test
    void aFileMissingFromTheConfigDirectoryIsNotReadFromAnywhereElse() throws Exception {
        final Path config = Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("application.yml"), "core: {}");

        assertThrows(FileNotFoundException.class, () -> ResourceUtils.readToStream(config, CLASS_PATH_RESOURCE));
        assertThrows(FileNotFoundException.class, () -> ResourceUtils.readToStream(config, "application.yml"));
        assertThrows(FileNotFoundException.class, () -> ResourceUtils.readToStream(config, "../application.yml"));
        assertThrows(FileNotFoundException.class,
            () -> ResourceUtils.readToStream(config, home.resolve("application.yml").toString()));
    }

    @Test
    void directoriesAreListedFromTheConfigDirectory() throws Exception {
        final Path config = Files.createDirectories(home.resolve("config"));
        final Path rules = Files.createDirectories(config.resolve("cilium-rules/nested"));
        Files.writeString(config.resolve("cilium-rules/exclude.yaml"), "namespaces: []");
        Files.writeString(rules.resolve("deep.yaml"), "x: 1");

        assertEquals(Set.of("exclude.yaml"), names(ResourceUtils.getPathFiles(config, "cilium-rules")));
        assertEquals(Set.of("exclude.yaml", "deep.yaml"),
            names(ResourceUtils.getDirectoryFilesRecursive(config, "cilium-rules", 1).toArray(new File[0])));
        assertEquals(config.resolve("cilium-rules").toFile().toPath(), ResourceUtils.getPath(config, "cilium-rules"));
        assertThrows(FileNotFoundException.class, () -> ResourceUtils.getPathFiles(config, "openapi-definitions"));
        assertThrows(NullPointerException.class, () -> ResourceUtils.getPath(config, "openapi-definitions"));
    }

    private static Set<String> names(final File[] files) {
        return Arrays.stream(files).map(File::getName).collect(Collectors.toSet());
    }
}
