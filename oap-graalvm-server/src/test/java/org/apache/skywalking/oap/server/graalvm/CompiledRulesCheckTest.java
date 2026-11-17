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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledRulesCheckTest {
    @TempDir
    Path root;

    @Test
    void runtimeConfigurationIsNoRuleFile() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("application.yml"), "core: {}");
        Files.writeString(config.resolve("alarm-settings.yml"), "rules: {}");
        Files.createDirectories(config.resolve("cilium-rules"));
        Files.writeString(config.resolve("cilium-rules/exclude.yaml"), "namespaces: []");
        Files.createDirectories(config.resolve("otel-rules"));

        assertTrue(CompiledRulesCheck.find(config, root, emptyClassPath(), null).isEmpty());
    }

    @Test
    void ruleFilesAreFoundWhereverTheConfigurationLookupWouldResolveThem() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.createDirectories(config.resolve("otel-rules/vm"));
        Files.writeString(config.resolve("otel-rules/vm/vm.yaml"), "metricsRules: []");
        Files.writeString(config.resolve("hierarchy-definition.yml"), "hierarchy: {}");
        Files.writeString(root.resolve("layer-extensions.yml"), "layers: []");
        final Path classPathConfig = Files.createDirectories(root.resolve("dist/config/lal"));
        Files.writeString(classPathConfig.resolve("custom.yaml"), "rules: []");

        try (URLClassLoader classPath = new URLClassLoader(
            new URL[] {root.resolve("dist/config").toUri().toURL()}, null)) {
            final Set<String> found = CompiledRulesCheck.find(config, root, classPath, null);
            assertEquals(Set.of(
                config.resolve("otel-rules/vm/vm.yaml").toAbsolutePath().normalize().toString(),
                config.resolve("hierarchy-definition.yml").toAbsolutePath().normalize().toString(),
                root.resolve("layer-extensions.yml").toAbsolutePath().normalize().toString(),
                classPathConfig.resolve("custom.yaml").toAbsolutePath().normalize().toString()), found);
        }
    }

    @Test
    void aLinkedRuleDirectoryIsFollowed() throws Exception {
        final Path external = Files.createDirectories(root.resolve("external/lal"));
        Files.writeString(external.resolve("custom.yaml"), "rules: []");
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.createSymbolicLink(config.resolve("lal"), external);

        final Set<String> found = CompiledRulesCheck.find(config, root, emptyClassPath(), null);
        assertEquals(Set.of(config.resolve("lal/custom.yaml").toAbsolutePath().normalize().toString()), found);
    }

    @Test
    void ruleFilesInAJarAreFoundExceptTheCompiledScriptsOfTheRegistryJar() throws Exception {
        final Path registry = jar("registry.jar", "oal/core.oal");
        final Path external = jar("rules.jar", "hierarchy-definition.yml");
        try (URLClassLoader classPath = new URLClassLoader(
            new URL[] {registry.toUri().toURL(), external.toUri().toURL()}, null)) {
            final Set<String> found = CompiledRulesCheck.find(
                Files.createDirectories(root.resolve("config")), root, classPath, registry.toUri().toURL().getPath());
            assertEquals(1, found.size(), found.toString());
            assertTrue(found.iterator().next().endsWith("rules.jar!/hierarchy-definition.yml"), found.toString());
        }
    }

    private Path jar(final String name, final String entry) throws Exception {
        final Path jar = root.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (entry.contains("/")) {
                out.putNextEntry(new JarEntry(entry.substring(0, entry.indexOf('/') + 1)));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(entry));
            out.write("x: 1".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static ClassLoader emptyClassPath() {
        return new URLClassLoader(new URL[0], null);
    }
}
