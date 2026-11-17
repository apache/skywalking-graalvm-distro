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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A string the registry generator writes as a Java literal compiles back to the same string, whatever an operator
 * named a rule or wrote in it: escapes, non-ASCII text, and values past the class-file limit of a string constant. A
 * hierarchy definition missing a section fails the build, as upstream fails the boot on it.
 */
class RegistryGeneratorTest {
    @TempDir
    Path dir;

    @Test
    void aHierarchySectionMustBeThereButMayBeEmpty() {
        final Map<String, Object> definition = Map.of("hierarchy", Map.of(), "auto-matching-rules", Map.of());
        assertEquals(Map.of(), RegistryGenerator.section(definition, "hierarchy"));
        final Exception e = assertThrows(IllegalStateException.class,
            () -> RegistryGenerator.section(definition, "layer-levels"));
        assertEquals("hierarchy-definition.yml has no layer-levels section", e.getMessage());
    }

    @Test
    void literalsCompileBackToTheSameString() throws Exception {
        final StringBuilder long1 = new StringBuilder();
        while (long1.length() < 50_000) {
            long1.append("zählung-café 😀 \"q\" \\ \n");
        }
        final List<String> values = List.of(
            "", "zählung-café", " payments\\edge=x:y\n", "tab\there \"quoted\" \r\n", "\u0001\u001f\u007f",
            "😀 surrogate pair", long1.toString());

        final StringBuilder source = new StringBuilder("public class Literals {\n");
        source.append("    public static String[] values() {\n        return new String[] {\n");
        final List<String> literals = new ArrayList<>();
        for (final String value : values) {
            literals.add("            " + RegistryGenerator.literal(value));
        }
        source.append(String.join(",\n", literals)).append("\n        };\n    }\n}\n");
        final Path file = dir.resolve("Literals.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-d", dir.toString(), file.toString()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()})) {
            final String[] compiled = (String[]) loader.loadClass("Literals").getMethod("values").invoke(null);
            assertEquals(values, List.of(compiled));
        }
    }
}
