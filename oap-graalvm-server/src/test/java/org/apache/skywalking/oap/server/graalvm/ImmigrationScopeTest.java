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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Immigration scope detector — catches new upstream providers and rule files
 * after {@code skywalking/} submodule updates.
 *
 * <p>Scans the upstream source tree for ModuleProvider implementations and
 * OAL/MAL/LAL rule files, comparing them against explicit inventories. Any
 * unrecognized addition causes a test failure, forcing the developer to
 * categorize new providers (ACCEPTED/NOT_ACCEPTED) and add new rule files
 * to the precompiler.
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
        String[] ruleDirectories = {
            "oal", "meter-analyzer-config", "otel-rules",
            "log-mal-rules", "lal", "envoy-metrics-rules",
            "telegraf-rules", "zabbix-rules"
        };
        for (String dir : ruleDirectories) {
            Path ruleDir = resources.resolve(dir);
            if (!Files.isDirectory(ruleDir)) {
                continue;
            }
            Files.walkFileTree(ruleDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".oal") || name.endsWith(".yaml")) {
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
            msg.append("New upstream rule files not in inventory — add to precompiler and inventory:\n");
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

    // ─── Helpers ────────────────────────────────────────────────────────

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
