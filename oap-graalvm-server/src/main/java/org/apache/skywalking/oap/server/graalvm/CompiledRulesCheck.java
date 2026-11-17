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

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * Fails the boot when a rule file sits where upstream's loaders would read it. This image runs the rules compiled
 * into it at build time and reads no rule file, so such a file would be silently ignored. Runtime configuration
 * ({@code application.yml}, {@code alarm-settings.yml} ...) is not a rule file and stays editable.
 */
final class CompiledRulesCheck {
    static final List<String> RULE_DIRECTORIES = List.of(
        "oal", "otel-rules", "meter-analyzer-config", "log-mal-rules", "envoy-metrics-rules", "telegraf-rules",
        "zabbix-rules", "lal", "tal");
    static final List<String> RULE_FILES = List.of("hierarchy-definition.yml", "layer-extensions.yml");

    private CompiledRulesCheck() {
    }

    static void verify() throws IOException, URISyntaxException {
        final Set<String> found = find(
            Path.of("config"), Path.of(""), CompiledRulesCheck.class.getClassLoader(), registryJar());
        if (!found.isEmpty()) {
            throw new IllegalStateException(
                "The rules are compiled into this image and no rule file is read, but rule files were found where "
                    + "OAP reads configuration, which would be ignored: " + found + ". Remove them; to change the "
                    + "rules, build the image with them.");
        }
    }

    /**
     * @return the jar the rule registry is in, whose {@code oal/*.oal} are the compiled scripts' display copies; null
     * in the native image, which has no jars
     */
    private static String registryJar() {
        final URL location = CompiledRules.class.getProtectionDomain().getCodeSource() == null
            ? null : CompiledRules.class.getProtectionDomain().getCodeSource().getLocation();
        return location == null ? null : location.getPath();
    }

    /**
     * @return every rule file the configuration lookup of the distro's ResourceUtils would resolve: under
     * {@code configDir}, the single-file rules in {@code workingDir}, and files, directories and jar entries on the
     * class path (the JVM distribution puts its {@code config/} there), except the entries of the rule registry's jar
     * {@code registryJar}. Native-image resources are what the build packed.
     */
    static Set<String> find(final Path configDir, final Path workingDir, final ClassLoader classLoader,
                            final String registryJar) throws IOException, URISyntaxException {
        final Set<String> found = new TreeSet<>();
        for (final String directory : RULE_DIRECTORIES) {
            found.addAll(files(configDir.resolve(directory)));
        }
        for (final String file : RULE_FILES) {
            found.addAll(files(configDir.resolve(file)));
            found.addAll(files(workingDir.resolve(file)));
        }
        final List<String> names = new ArrayList<>(RULE_DIRECTORIES);
        names.addAll(RULE_FILES);
        for (final String name : names) {
            final Enumeration<URL> urls = classLoader.getResources(name);
            for (final URL url : Collections.list(urls)) {
                if ("file".equals(url.getProtocol())) {
                    found.addAll(files(Path.of(url.toURI())));
                } else if ("jar".equals(url.getProtocol())) {
                    final JarURLConnection jar = (JarURLConnection) url.openConnection();
                    if (!jar.getJarFileURL().getPath().equals(registryJar)) {
                        found.add(url.toString());
                    }
                }
            }
        }
        return found;
    }

    private static List<String> files(final Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return List.of(path.toAbsolutePath().normalize().toString());
        }
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        // Following links, as File.listFiles does: a mounted ConfigMap is a directory of links.
        try (Stream<Path> walk = Files.walk(path, FileVisitOption.FOLLOW_LINKS)) {
            return walk.filter(Files::isRegularFile)
                       .map(p -> p.toAbsolutePath().normalize().toString())
                       .collect(Collectors.toList());
        }
    }
}
