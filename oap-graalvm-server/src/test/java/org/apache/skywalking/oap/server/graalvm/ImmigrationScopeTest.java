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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Immigration scope detector — catches new upstream providers and rule files
 * after {@code skywalking/} submodule updates.
 *
 * <p>Scans the upstream source tree for ModuleProvider implementations and
 * OAL/MAL/LAL/TAL rule files, comparing them against explicit inventories. Any
 * unrecognized addition causes a test failure, forcing the developer to
 * categorize new providers (ACCEPTED/NOT_ACCEPTED) and to review new rule files,
 * which the precompiler compiles as they are.
 */
class ImmigrationScopeTest {

    private static final String PROVIDER_INVENTORY = "provider-inventory.properties";
    private static final String RULE_FILE_INVENTORY = "rule-file-inventory.properties";

    /**
     * Patterns that indicate a class is a ModuleProvider implementation.
     * Covers direct subclasses and {@code AbstractConfigurationProvider}
     * subclasses. Sub-providers that extend other tracked providers
     * (e.g. MySQL/PostgreSQL extending JDBCStorageProvider) are not
     * scanned separately — the parent provider entry covers them.
     */
    private static final Pattern EXTENDS_PROVIDER = Pattern.compile(
        "\\bextends\\s+(ModuleProvider|AbstractConfigurationProvider)\\b"
    );

    // ─── Provider inventory ─────────────────────────────────────────────

    @Test
    void allUpstreamProvidersAreCategorized() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path oapServer = projectRoot.resolve("skywalking/oap-server");
        assertTrue(Files.isDirectory(oapServer),
            "skywalking/oap-server not found — is the submodule initialized?");

        // Scan upstream for provider implementations
        Set<String> upstreamProviders = scanProviders(oapServer);

        // Load inventory
        Properties inventory = loadProperties(PROVIDER_INVENTORY);
        Set<String> inventoryProviders = new HashSet<>();
        for (String key : inventory.stringPropertyNames()) {
            if (key.startsWith("provider.")) {
                inventoryProviders.add(key.substring("provider.".length()));
            }
        }

        // Find providers in upstream but not in inventory
        Set<String> unknown = new TreeSet<>(upstreamProviders);
        unknown.removeAll(inventoryProviders);

        // Find providers in inventory but not in upstream (stale entries)
        Set<String> stale = new TreeSet<>(inventoryProviders);
        stale.removeAll(upstreamProviders);

        StringBuilder msg = new StringBuilder();
        if (!unknown.isEmpty()) {
            msg.append("New upstream providers not in inventory — add as ACCEPTED or NOT_ACCEPTED:\n");
            for (String fqcn : unknown) {
                msg.append("  provider.").append(fqcn).append(" = ???\n");
            }
        }
        if (!stale.isEmpty()) {
            msg.append("Providers in inventory but not found upstream (removed?):\n");
            for (String fqcn : stale) {
                msg.append("  provider.").append(fqcn).append('\n');
            }
        }

        if (msg.length() > 0) {
            fail(msg.toString());
        }
    }

    /**
     * Scans all {@code src/main/java/} directories under oap-server for classes
     * that extend {@code ModuleProvider} or {@code AbstractConfigurationProvider}.
     * Excludes Mock* classes (test tools) and ignores {@code target/} directories.
     */
    private Set<String> scanProviders(Path oapServer) throws IOException {
        Set<String> providers = new HashSet<>();

        Files.walkFileTree(oapServer, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                // Skip target/ and test/ directories
                if ("target".equals(name) || "test".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                // Only scan src/main/java files
                if (!file.toString().contains("/src/main/java/")) {
                    return FileVisitResult.CONTINUE;
                }
                String className = file.getFileName().toString()
                    .replace(".java", "");
                // Skip Mock* classes (test tools)
                if (className.startsWith("Mock")) {
                    return FileVisitResult.CONTINUE;
                }

                // Check if file contains extends ModuleProvider or
                // extends AbstractConfigurationProvider
                boolean isProvider = false;
                String packageName = null;
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (packageName == null && line.startsWith("package ")) {
                            packageName = line.replace("package ", "")
                                .replace(";", "").trim();
                        }
                        if (EXTENDS_PROVIDER.matcher(line).find()) {
                            isProvider = true;
                        }
                        if (packageName != null && isProvider) {
                            break;
                        }
                    }
                }

                if (isProvider && packageName != null) {
                    providers.add(packageName + "." + className);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return providers;
    }

    // ─── Rule file inventory ────────────────────────────────────────────

    @Test
    void allUpstreamRuleFilesAreTracked() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path resources = projectRoot.resolve(
            "skywalking/oap-server/server-starter/src/main/resources");
        assertTrue(Files.isDirectory(resources),
            "server-starter resources not found — is the submodule initialized?");

        // Scan upstream rule files
        Set<String> upstreamFiles = new TreeSet<>();
        for (String dir : RuleCatalog.directories()) {
            Path ruleDir = resources.resolve(dir);
            if (!Files.isDirectory(ruleDir)) {
                continue;
            }
            Files.walkFileTree(ruleDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".oal") || name.endsWith(".yaml") || name.endsWith(".yml")) {
                        upstreamFiles.add(
                            resources.relativize(file).toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Load inventory
        Properties inventory = loadProperties(RULE_FILE_INVENTORY);
        Set<String> inventoryFiles = new TreeSet<>();
        for (String key : inventory.stringPropertyNames()) {
            inventoryFiles.add(key.trim());
        }

        // Find files in upstream but not in inventory
        Set<String> unknown = new TreeSet<>(upstreamFiles);
        unknown.removeAll(inventoryFiles);

        // Find files in inventory but not in upstream (removed?)
        Set<String> stale = new TreeSet<>(inventoryFiles);
        stale.removeAll(upstreamFiles);

        StringBuilder msg = new StringBuilder();
        if (!unknown.isEmpty()) {
            msg.append("New upstream rule files not in inventory — review them (the precompiler compiles them) and "
                + "add them to the inventory:\n");
            for (String f : unknown) {
                msg.append("  ").append(f).append(" = ACCEPTED\n");
            }
        }
        if (!stale.isEmpty()) {
            msg.append("Rule files in inventory but not found upstream (removed?):\n");
            for (String f : stale) {
                msg.append("  ").append(f).append('\n');
            }
        }

        if (msg.length() > 0) {
            fail(msg.toString());
        }
    }

    // ─── Upstream starter resources ─────────────────────────────────────

    /**
     * Every top-level entry of upstream's server-starter resources is accounted for: compiled at build time, supplied
     * by the distro, or a runtime file both distributions package. A new rule kind (a directory or file upstream
     * starts reading at boot) would otherwise be missing from the image, and its loader would find nothing.
     */
    @Test
    void everyUpstreamStarterResourceIsAccountedFor() throws Exception {
        final Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        final Path resources = projectRoot.resolve("skywalking/oap-server/server-starter/src/main/resources");
        final Set<String> compiled = new TreeSet<>(RuleCatalog.directories());
        compiled.addAll(RuleCatalog.RULE_FILES);
        // application.yml is the distro's own; log4j2.xml and alarm-settings.yml come from dist-material.
        final Set<String> distroOwn = Set.of("application.yml", "log4j2.xml", "alarm-settings.yml", "version.properties");
        final Path jvm = projectRoot.resolve("oap-graalvm-server/src/main/assembly/distribution.xml");
        final Path nativeDist = projectRoot.resolve("oap-graalvm-native/src/main/assembly/native-distribution.xml");

        final List<String> unaccounted = new ArrayList<>();
        try (Stream<Path> entries = Files.list(resources)) {
            for (final Path entry : entries.sorted().collect(Collectors.toList())) {
                final String name = entry.getFileName().toString();
                // Not tracked: a hidden file or an editor backup in a local checkout
                if (name.startsWith(".") || name.endsWith("~")
                    || compiled.contains(name) || distroOwn.contains(name)) {
                    continue;
                }
                if (!packaged(jvm, resources, entry) || !packaged(nativeDist, resources, entry)) {
                    unaccounted.add(name);
                }
            }
        }
        if (!unaccounted.isEmpty()) {
            fail("Upstream server-starter resources the distro neither compiles nor packages: " + unaccounted
                + ". A rule kind needs a precompiler pass and the rule registry; a runtime config file needs an include "
                + "in distribution.xml and native-distribution.xml (and a resource glob).");
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * Whether an assembly packages every file of a top-level server-starter resource into {@code config/}: some fileSet
     * over that directory into {@code config} includes each file's path and does not exclude it, as Ant patterns.
     */
    private static boolean packaged(final Path assembly, final Path resources, final Path entry) throws Exception {
        final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(assembly.toFile());
        final List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(entry)) {
            walk.filter(Files::isRegularFile)
                .forEach(f -> files.add(resources.relativize(f).toString().replace(File.separatorChar, '/')));
        }
        final NodeList fileSets = document.getElementsByTagName("fileSet");
        for (final String file : files) {
            boolean found = false;
            for (int i = 0; i < fileSets.getLength() && !found; i++) {
                final Element fileSet = (Element) fileSets.item(i);
                if (!text(fileSet, "directory").endsWith("server-starter/src/main/resources")
                    || !text(fileSet, "outputDirectory").replaceAll("^/|/$", "").equals("config")) {
                    continue;
                }
                found = matchesAny(fileSet, "include", file) && !matchesAny(fileSet, "exclude", file);
            }
            if (!found) {
                return false;
            }
        }
        return !files.isEmpty();
    }

    private static String text(final Element element, final String tag) {
        final NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static boolean matchesAny(final Element fileSet, final String tag, final String path) {
        final NodeList patterns = fileSet.getElementsByTagName(tag);
        for (int i = 0; i < patterns.getLength(); i++) {
            if (antMatches(patterns.item(i).getTextContent().trim(), path)) {
                return true;
            }
        }
        return false;
    }

    /** Ant path matching, as the assembly plugin applies includes and excludes: {@code **}, {@code *}, {@code ?}. */
    static boolean antMatches(final String pattern, final String path) {
        final StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                final boolean slash = i + 2 < pattern.length() && pattern.charAt(i + 2) == '/';
                regex.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return path.matches(regex.toString());
    }

    private Properties loadProperties(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(resource)) {
            if (is != null) {
                props.load(is);
            }
        }
        return props;
    }
}
