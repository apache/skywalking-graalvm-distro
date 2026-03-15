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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Staleness detector for same-FQCN replacement classes.
 *
 * <p>Two verification passes:
 * <ol>
 *   <li><b>SHA-256 check</b>: For every entry in
 *       {@code replacement-source-sha256.properties}, verify the upstream source
 *       file still exists at the recorded path and its content hash matches.
 *       A mismatch means the replacement needs review and update.</li>
 *   <li><b>Coverage check</b>: Scan all {@code .java} files under
 *       {@code oap-libs-for-graalvm/} with {@code org.apache.skywalking} package,
 *       map each to its upstream counterpart in {@code skywalking/}, and verify
 *       it is tracked in the properties file. An untracked replacement means
 *       someone added a same-FQCN override without recording its upstream SHA.</li>
 * </ol>
 *
 * <p>SHA-256 hashes are recorded in {@code replacement-source-sha256.properties}
 * (same pattern as {@code precompiled-yaml-sha256.properties}).
 */
class ReplacementClassStalenessTest {

    private static final String PROPS_RESOURCE = "replacement-source-sha256.properties";

    /**
     * Base package prefix for same-FQCN replacements. Only classes under this
     * package in oap-libs-for-graalvm are expected to have upstream counterparts.
     */
    private static final String SKYWALKING_PACKAGE = "org/apache/skywalking/";

    @Test
    void allReplacementSourcesMatchRecordedSha256() throws Exception {
        Properties props = loadProperties();
        assertTrue(!props.isEmpty(),
            PROPS_RESOURCE + " is empty or not found");

        // Resolve paths relative to project root (oap-graalvm-server -> parent)
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();

        List<String> mismatches = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String relativePath : props.stringPropertyNames()) {
            String expectedSha = props.getProperty(relativePath).trim();
            Path upstreamPath = projectRoot.resolve(relativePath);

            if (!Files.exists(upstreamPath)) {
                missing.add(relativePath);
            } else {
                String actualSha = computeFileSha256(upstreamPath);
                if (!expectedSha.equals(actualSha)) {
                    mismatches.add(String.format(
                        "  %s%n    expected: %s%n    actual:   %s",
                        relativePath, expectedSha, actualSha));
                }
            }
        }

        StringBuilder msg = new StringBuilder();
        if (!missing.isEmpty()) {
            msg.append("Upstream source files no longer exist — replacement is orphaned:\n");
            for (String m : missing) {
                msg.append("  ").append(m).append('\n');
            }
            msg.append("  Action: check if upstream renamed/moved/deleted the class,\n");
            msg.append("  then update or remove the replacement and its properties entry.\n\n");
        }
        if (!mismatches.isEmpty()) {
            msg.append("Upstream source files changed — review and update these replacements:\n");
            for (String m : mismatches) {
                msg.append(m).append('\n');
            }
            msg.append("  Action: diff the upstream change, update the replacement class,\n");
            msg.append("  then run: shasum -a 256 <path> to update the properties file.\n\n");
        }

        if (msg.length() > 0) {
            fail(msg.toString());
        }
    }

    /**
     * Scan all replacement .java files under oap-libs-for-graalvm/ with
     * org.apache.skywalking package and verify each has a corresponding entry
     * in replacement-source-sha256.properties. This catches new replacements
     * that were added without recording the upstream SHA.
     */
    @Test
    void allReplacementClassesAreTracked() throws Exception {
        Properties props = loadProperties();
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path libsDir = projectRoot.resolve("oap-libs-for-graalvm");

        if (!Files.isDirectory(libsDir)) {
            return; // skip if directory doesn't exist (e.g. clean checkout)
        }

        // Collect all tracked upstream paths for quick lookup
        Set<String> trackedUpstreamPaths = new HashSet<>();
        for (String key : props.stringPropertyNames()) {
            trackedUpstreamPaths.add(key);
        }

        // Scan oap-libs-for-graalvm for replacement .java files
        List<String> untracked = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(libsDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().contains("src/main/java/"))
                .forEach(replacementFile -> {
                    // Extract the relative path after src/main/java/
                    String fullPath = replacementFile.toString();
                    int srcIdx = fullPath.indexOf("src/main/java/");
                    if (srcIdx < 0) return;
                    String classRelPath = fullPath.substring(srcIdx + "src/main/java/".length());

                    // Only check org.apache.skywalking classes
                    if (!classRelPath.startsWith(SKYWALKING_PACKAGE)) return;

                    // Try to find the upstream source file
                    String upstreamPath = resolveUpstreamPath(projectRoot, classRelPath);
                    if (upstreamPath != null && !trackedUpstreamPaths.contains(upstreamPath)) {
                        untracked.add(String.format(
                            "  replacement: %s%n  upstream:    %s",
                            projectRoot.relativize(replacementFile), upstreamPath));
                    }
                });
        }

        if (!untracked.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Replacement classes not tracked in ").append(PROPS_RESOURCE).append(":\n");
            for (String u : untracked) {
                msg.append(u).append('\n');
            }
            msg.append("  Action: add the upstream path and its SHA-256 to ").append(PROPS_RESOURCE).append('\n');
            msg.append("  Run: shasum -a 256 <upstream-path>\n");
            fail(msg.toString());
        }
    }

    /**
     * Resolve the upstream source path for a replacement class.
     * Searches under skywalking/ for a .java file matching the same relative
     * class path (e.g. org/apache/skywalking/.../Foo.java).
     *
     * @return relative path from project root (e.g. skywalking/oap-server/.../Foo.java),
     *         or null if no upstream source found
     */
    private static String resolveUpstreamPath(Path projectRoot, String classRelPath) {
        Path skywalking = projectRoot.resolve("skywalking");
        if (!Files.isDirectory(skywalking)) return null;

        try (Stream<Path> walk = Files.walk(skywalking)) {
            return walk
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/"))
                .filter(p -> !p.toString().contains("/test/"))
                .filter(p -> {
                    String s = p.toString();
                    int idx = s.indexOf("src/main/java/");
                    if (idx < 0) return false;
                    String rel = s.substring(idx + "src/main/java/".length());
                    return rel.equals(classRelPath);
                })
                .map(p -> projectRoot.relativize(p).toString())
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(PROPS_RESOURCE)) {
            if (is != null) {
                props.load(is);
            }
        }
        return props;
    }

    private static String computeFileSha256(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
