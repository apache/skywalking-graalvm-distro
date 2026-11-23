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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;

/**
 * The rule files the build's rules overlay added or overrode, from the inventory the precompiler's {@code RulesStager}
 * wrote ({@code action<TAB>path<TAB>sha256}); none without an overlay.
 */
public final class OverlayFiles {
    private static final Path DISTRO = Path.of(System.getProperty("user.dir")).getParent();

    private OverlayFiles() {
    }

    /**
     * @return the rule files of each catalog in the staged rule tree, read from it directly (not from what a loader
     *     recorded), by name: the path in the catalog without extension, a file yielding no rule too
     */
    public static Map<String, List<String>> staged(final Collection<RuleCatalog> catalogs) throws IOException {
        final Path root = Path.of(System.getProperty("rules.staging.dir",
            DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString()));
        final Map<String, List<String>> staged = new TreeMap<>();
        for (final RuleCatalog catalog : catalogs) {
            final Path directory = root.resolve(catalog.getDirectory());
            final List<String> names = new ArrayList<>();
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = catalog.isRecursive() ? Files.walk(directory) : Files.list(directory)) {
                    files.filter(Files::isRegularFile)
                         .map(file -> directory.relativize(file).toString().replace(File.separatorChar, '/'))
                         .filter(catalog::isRuleFile)
                         .map(path -> path.substring(0, path.lastIndexOf('.')))
                         .sorted()
                         .forEach(names::add);
                }
            }
            staged.put(catalog.getDirectory(), names);
        }
        return staged;
    }

    /**
     * @param file a rule file by its path from the rule tree root
     * @return whether the overlay added or overrode it with the repository's own {@code test/rules-overlay-fixture}
     *     copy, byte for byte: what a test of that fixture's content may assume, whatever overlay the build used
     */
    public static boolean isTheRepositoryFixture(final String file) throws IOException {
        if (!under(file.substring(0, file.indexOf('/'))).contains(file)) {
            return false;
        }
        final Path staged = Path.of(System.getProperty("rules.staging.dir",
            DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString())).resolve(file);
        final Path fixture = DISTRO.resolve("test/rules-overlay-fixture").resolve(file);
        return Files.isRegularFile(fixture)
            && Arrays.equals(Files.readAllBytes(staged), Files.readAllBytes(fixture));
    }

    /**
     * @param directory a rule directory, {@code otel-rules} for one
     * @return the added and overridden files under it, by their path from the rule tree root
     */
    public static List<String> under(final String directory) throws IOException {
        final String inventory = System.getProperty("rules.overlay.inventory");
        if (inventory == null) {
            return List.of();
        }
        // The stager writes it on every build, empty without an overlay: a missing one is a broken build.
        if (!Files.isRegularFile(Path.of(inventory))) {
            throw new IllegalStateException("No overlay inventory at " + inventory);
        }
        return Files.readAllLines(Path.of(inventory), StandardCharsets.UTF_8).stream()
                    .map(line -> line.split("\t"))
                    .filter(parts -> parts.length >= 2 && !"exclude".equals(parts[0])
                        && parts[1].startsWith(directory + "/"))
                    .map(parts -> parts[1])
                    .collect(Collectors.toList());
    }
}
