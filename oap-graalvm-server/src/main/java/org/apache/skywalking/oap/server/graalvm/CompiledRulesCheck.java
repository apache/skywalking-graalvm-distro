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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;

/**
 * Fails the boot when a rule file sits in the directory OAP reads its configuration from, where upstream keeps its rule
 * files. Rule files enter this image only at build time (upstream's or a rules overlay's); at runtime it reads none, so
 * such a file would be silently ignored. Runtime configuration ({@code application.yml}, {@code alarm-settings.yml}
 * ...) is not a rule file and stays editable.
 */
final class CompiledRulesCheck {
    private CompiledRulesCheck() {
    }

    static void verify() throws IOException, URISyntaxException {
        final Set<String> found = find(
            configDirectory(ResourceUtils.configDirectory(), CompiledRulesCheck.class.getClassLoader()));
        if (!found.isEmpty()) {
            throw new IllegalStateException(
                "The rules are compiled into this image and no rule file is read, but rule files were found where "
                    + "OAP reads configuration, which would be ignored: " + found + ". Remove them; to change the "
                    + "rules, build the image with them.");
        }
    }

    /**
     * @param nativeConfigDir the native image's {@code config/} next to its executable, null on the JVM
     * @return the directory OAP reads its configuration from: the native image's, or on the JVM the directory
     *     {@code application.yml} is read from (the class loader's resource, a file); null when there is none
     */
    static Path configDirectory(final Path nativeConfigDir, final ClassLoader classLoader) throws URISyntaxException {
        if (nativeConfigDir != null) {
            return nativeConfigDir;
        }
        final URL applicationYml = classLoader.getResource("application.yml");
        if (applicationYml == null || !"file".equals(applicationYml.getProtocol())) {
            return null;
        }
        final Path directory = Path.of(applicationYml.toURI()).getParent();
        return directory != null && Files.isDirectory(directory) ? directory : null;
    }

    /**
     * @return every rule file of the configuration directory (none without one): a file of a rule directory by the
     *     catalog's rule ({@link RuleCatalog#isRuleFile}), so a {@code .gitkeep} or an empty directory is none, and
     *     {@code hierarchy-definition.yml} / {@code layer-extensions.yml} at its root
     */
    static Set<String> find(final Path configDir) throws IOException {
        final Set<String> found = new TreeSet<>();
        if (configDir == null) {
            return found;
        }
        for (final RuleCatalog catalog : RuleCatalog.values()) {
            found.addAll(ruleFiles(configDir.resolve(catalog.getDirectory()), catalog));
        }
        for (final String file : RuleCatalog.RULE_FILES) {
            if (Files.isRegularFile(configDir.resolve(file))) {
                found.add(configDir.resolve(file).toAbsolutePath().normalize().toString());
            }
        }
        return found;
    }

    /**
     * @return the rule files of the directory, as its loader reads it: a flat loader lists the directory, following
     * links as {@code File.listFiles} does (a mounted ConfigMap is a directory of links); the MAL loader walks it as
     * {@code Files.walk} does, not following a link to a directory
     */
    private static List<String> ruleFiles(final Path directory, final RuleCatalog catalog) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = catalog.isRecursive() ? Files.walk(directory) : Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                        .filter(p -> catalog.isRuleFile(directory.relativize(p).toString().replace(File.separatorChar, '/')))
                        .map(p -> p.toAbsolutePath().normalize().toString())
                        .collect(Collectors.toList());
        }
    }
}
