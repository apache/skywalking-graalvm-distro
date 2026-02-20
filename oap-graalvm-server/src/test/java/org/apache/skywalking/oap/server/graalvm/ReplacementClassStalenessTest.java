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
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Staleness detector for same-FQCN replacement classes.
 *
 * <p>Tracks the SHA-256 of each upstream source file that has a same-FQCN
 * replacement in this project. If an upstream file changes (e.g. after a
 * {@code skywalking/} submodule update), this test fails with a clear message
 * indicating which replacement(s) need review and update.
 *
 * <p>SHA-256 hashes are recorded in {@code replacement-source-sha256.properties}
 * (same pattern as {@code precompiled-yaml-sha256.properties}).
 */
class ReplacementClassStalenessTest {

    private static final String PROPS_RESOURCE = "replacement-source-sha256.properties";

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
        if (!mismatches.isEmpty()) {
            msg.append("Upstream source files changed — review and update these replacements:\n");
            for (String m : mismatches) {
                msg.append(m).append('\n');
            }
        }
        if (!missing.isEmpty()) {
            msg.append("Upstream source files not found:\n");
            for (String m : missing) {
                msg.append("  ").append(m).append('\n');
            }
        }

        if (msg.length() > 0) {
            fail(msg.toString());
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
