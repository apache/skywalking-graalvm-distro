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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A rule file in the directory OAP reads its configuration from fails the boot: the native image's {@code config/}, or
 * on the JVM the directory {@code application.yml} is read from. Only a rule file counts, by its rule directory's rule.
 */
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

        assertEquals(Set.of(), CompiledRulesCheck.find(config));
    }

    /**
     * The native image's {@code config/}: a rule file of a rule directory, nested where its loader walks, and a rule file
     * at the root.
     */
    @Test
    void aRuleFileInTheNativeImagesConfigDirectoryIsFound() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.createDirectories(config.resolve("otel-rules/vm"));
        Files.writeString(config.resolve("otel-rules/vm/vm.yaml"), "metricsRules: []");
        Files.writeString(config.resolve("hierarchy-definition.yml"), "hierarchy: {}");

        assertEquals(config, CompiledRulesCheck.configDirectory(config, emptyClassPath()));
        assertEquals(Set.of(
            config.resolve("otel-rules/vm/vm.yaml").toAbsolutePath().normalize().toString(),
            config.resolve("hierarchy-definition.yml").toAbsolutePath().normalize().toString()),
            CompiledRulesCheck.find(config));
    }

    /**
     * On the JVM the configuration directory is the one {@code application.yml} is read from, as the JVM distribution's
     * {@code config/} on the class path holds it.
     */
    @Test
    void aRuleFileWhereTheJvmReadsApplicationYmlIsFound() throws Exception {
        final Path config = Files.createDirectories(root.resolve("dist/config/lal"));
        Files.writeString(config.resolve("custom.yaml"), "rules: []");
        Files.writeString(root.resolve("dist/config/application.yml"), "core: {}");

        try (URLClassLoader classPath = new URLClassLoader(
            new URL[] {root.resolve("dist/config").toUri().toURL()}, null)) {
            final Path configDir = CompiledRulesCheck.configDirectory(null, classPath);
            assertEquals(root.resolve("dist/config"), configDir);
            assertEquals(Set.of(config.resolve("custom.yaml").toAbsolutePath().normalize().toString()),
                CompiledRulesCheck.find(configDir));
        }
    }

    /**
     * Without an {@code application.yml} in a directory (none, or one packed in a jar) there is no configuration
     * directory, and nothing to check.
     */
    @Test
    void withoutAConfigurationDirectoryNothingIsChecked() throws Exception {
        assertNull(CompiledRulesCheck.configDirectory(null, emptyClassPath()));
        final Path jar = root.resolve("config.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("application.yml"));
            out.write("core: {}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader classPath = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertNull(CompiledRulesCheck.configDirectory(null, classPath));
        }
        assertEquals(Set.of(), CompiledRulesCheck.find(null));
    }

    @Test
    void aLinkedRuleDirectoryIsFollowed() throws Exception {
        final Path external = Files.createDirectories(root.resolve("external/lal"));
        Files.writeString(external.resolve("custom.yaml"), "rules: []");
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.createSymbolicLink(config.resolve("lal"), external);

        assertEquals(Set.of(config.resolve("lal/custom.yaml").toAbsolutePath().normalize().toString()),
            CompiledRulesCheck.find(config));
    }

    /**
     * A directory is read as far as its loader reads it: a flat loader's directory itself, so a link loop below it is
     * never walked; the MAL loader's directory and below, not following a link to a directory, as {@code Files.walk}.
     */
    @Test
    void aDirectoryIsReadAsFarAsItsLoaderReadsIt() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config"));
        final Path archive = Files.createDirectories(config.resolve("lal/archive"));
        Files.createSymbolicLink(archive.resolve("loop"), config.resolve("lal"));
        Files.writeString(archive.resolve("old.yaml"), "rules: []");
        final Path k8s = Files.createDirectories(config.resolve("otel-rules/k8s"));
        Files.createSymbolicLink(k8s.resolve("loop"), config.resolve("otel-rules"));
        Files.writeString(k8s.resolve("node.yaml"), "metricsRules: []");
        final Path external = Files.createDirectories(root.resolve("external"));
        Files.writeString(external.resolve("linked.yaml"), "metricsRules: []");
        Files.createSymbolicLink(config.resolve("otel-rules/linked"), external);

        assertEquals(Set.of(k8s.resolve("node.yaml").toAbsolutePath().normalize().toString()),
            CompiledRulesCheck.find(config));
    }

    /**
     * Only a rule file stops the boot, by the catalog's rule: a {@code .gitkeep}, a file of another extension, a hidden
     * one, one nested in a directory its loader reads flat, or an empty rule directory is none.
     */
    @Test
    void aFileOrDirectoryThatIsNoRuleFileDoesNotStopTheBoot() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config"));
        Files.createDirectories(config.resolve("lal/sub"));
        Files.writeString(config.resolve("lal/.gitkeep"), "");
        Files.writeString(config.resolve("lal/README.md"), "notes");
        Files.writeString(config.resolve("lal/.hidden.yaml"), "rules: []");
        Files.writeString(config.resolve("lal/sub/nested.yaml"), "rules: []");
        Files.createDirectories(config.resolve("oal"));
        Files.writeString(config.resolve("oal/notes.yaml"), "x: 1");
        Files.createDirectories(config.resolve("tal"));
        assertEquals(Set.of(), CompiledRulesCheck.find(config));

        // A rule file below a directory its loader walks is one
        Files.createDirectories(config.resolve("otel-rules/k8s"));
        Files.writeString(config.resolve("otel-rules/k8s/node.yml"), "metricsRules: []");
        assertEquals(Set.of(config.resolve("otel-rules/k8s/node.yml").toAbsolutePath().normalize().toString()),
            CompiledRulesCheck.find(config));
    }

    private static ClassLoader emptyClassPath() {
        return new URLClassLoader(new URL[0], null);
    }
}
