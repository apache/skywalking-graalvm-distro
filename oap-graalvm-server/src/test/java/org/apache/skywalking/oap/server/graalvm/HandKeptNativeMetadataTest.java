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

package org.apache.skywalking.oap.server.graalvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The hand-kept native-image metadata names only classes of this project that exist: an entry for a class upstream
 * removed or renamed registers nothing, and hides that the class it stood for may need an entry of its own.
 */
class HandKeptNativeMetadataTest {
    private static final Path METADATA = Path.of(System.getProperty("user.dir")).getParent().resolve(
        "oap-graalvm-native/src/main/resources/META-INF/native-image/org.apache.skywalking/oap-graalvm-native");

    @Test
    void everyHandKeptEntryOfThisProjectNamesAClassOfTheImage() throws IOException {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(METADATA)) {
            list.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }
        assertFalse(files.isEmpty(), "no metadata under " + METADATA);
        final TreeSet<String> missing = new TreeSet<>();
        final ObjectMapper mapper = new ObjectMapper();
        // Resolved as the distribution's class path resolves them: a class only the tests see is none of the image
        try (URLClassLoader distribution = ApplicationYmlModulesTest.runtimeClassLoader()) {
            for (final Path file : files) {
                final JsonNode root = mapper.readTree(file.toFile());
                for (final JsonNode entry : root.isArray() ? root : root.path("reflection")) {
                    // reachability-metadata.json names the class by "type", a *-reflect-config.json by "name"
                    final JsonNode name = entry.has("type") ? entry.get("type") : entry.get("name");
                    if (name == null || !name.isTextual() || !name.asText().startsWith("org.apache.skywalking.")) {
                        continue;
                    }
                    try {
                        Class.forName(name.asText(), false, distribution);
                    } catch (ClassNotFoundException | LinkageError e) {
                        missing.add(file.getFileName() + ": " + name.asText());
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(), missing, "hand-kept entries naming classes that do not exist");
    }
}
