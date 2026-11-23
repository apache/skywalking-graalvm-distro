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

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hierarchy matching rules compile under any name upstream accepts, and the build records each name as written.
 */
class HierarchyPrecompileTest {
    @TempDir
    Path out;

    @Test
    void aRuleNameNeedingEscapesCompilesAndReadsBackAsWritten() throws Exception {
        Files.createDirectories(out.resolve("META-INF"));
        HierarchyPass.compileHierarchy(out.toString(), "hierarchy-fixture-rule-names.yml");
        final Properties rules = new Properties();
        try (Reader reader = Files.newBufferedReader(
            out.resolve("META-INF/hierarchy-v2-rules.txt"), StandardCharsets.UTF_8)) {
            rules.load(reader);
        }
        assertEquals(Set.of("fqdn:k8s", "short name", "a=b#c", "#first", "zählung"), rules.stringPropertyNames());
        for (final String name : rules.stringPropertyNames()) {
            final Path classFile = out.resolve(rules.getProperty(name).replace('.', '/') + ".class");
            assertTrue(Files.isRegularFile(classFile), name + " has no class at " + classFile);
        }
    }
}
