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

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;

/**
 * The staged rule tree ({@link RulesStager}) the passes read the rule files from, on the class path.
 */
final class RuleTree {
    /**
     * Every rule file of a catalog by name (its path in the catalog without extension), enumerated so that a catalog
     * an overlay emptied compiles to nothing: upstream's loader fails on a pattern that matches no file. A file that
     * yields no rule is one too: upstream's loader reads it, and its setting lists it.
     */
    static List<String> ruleNames(final String catalog) throws IOException {
        return rulePaths(catalog).stream().map(rel -> rel.substring(0, rel.lastIndexOf('.'))).collect(Collectors.toList());
    }

    /**
     * @return every rule file of a catalog, by its path in the catalog, as the rules stager staged them (YAML files,
     *     below the directory where its loader walks it)
     */
    static List<String> rulePaths(final String catalog) throws IOException {
        final Path dir = existingDirectory(catalog);
        if (dir == null) {
            return new ArrayList<>();
        }
        final boolean recursive = RuleCatalog.ofDirectory(catalog)
            .orElseThrow(() -> new IllegalArgumentException("Not a rule directory: " + catalog)).isRecursive();
        try (Stream<Path> files = Files.walk(dir, recursive ? Integer.MAX_VALUE : 1)) {
            return files.filter(Files::isRegularFile)
                        .map(p -> dir.relativize(p).toString().replace(File.separatorChar, '/'))
                        .filter(rel -> !rel.startsWith(".") && (rel.endsWith(".yaml") || rel.endsWith(".yml")))
                        .sorted()
                        .collect(Collectors.toList());
        }
    }

    /**
     * @return the rule directory on the classpath, or null when there is none (an overlay excluded every file of it)
     */
    static Path existingDirectory(final String path) throws IOException {
        final URL url = RuleTree.class.getClassLoader().getResource(path);
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            final Path dir = Path.of(url.toURI());
            return Files.isDirectory(dir) ? dir : null;
        } catch (URISyntaxException e) {
            throw new IOException("Unreadable rule directory " + url, e);
        }
    }

    static File[] filesOf(final String path) throws IOException {
        return existingDirectory(path) == null ? new File[0] : ResourceUtils.getPathFiles(path);
    }
}
