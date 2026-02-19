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

package org.apache.skywalking.oap.server.graalvm.mal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Staleness detector for MAL YAML files.
 *
 * <p>This test compares the SHA-256 hash of each tracked MAL YAML file
 * against the recorded hash in {@code mal-yaml-sha256.properties}.
 * If any file's content has changed (e.g. after a {@code skywalking/}
 * submodule update), the test fails with a clear list of which files
 * changed and need their comparison tests regenerated.
 *
 * <p><b>AI workflow</b>: Before starting MAL test work, run this test
 * first. If it passes, all existing tests are up-to-date. If it fails,
 * re-read only the changed YAML files, regenerate their test classes,
 * then update the SHA-256 in the properties file.
 *
 * @see MALScriptComparisonBase
 */
class MALYamlStalenessTest {

    private static final String PROPS_RESOURCE = "mal-yaml-sha256.properties";

    @Test
    void allTrackedYamlFilesMatchRecordedSha256() throws Exception {
        Properties props = loadProperties();
        assertTrue(!props.isEmpty(),
            PROPS_RESOURCE + " is empty or not found");

        List<String> mismatches = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String yamlPath : props.stringPropertyNames()) {
            String expectedSha = props.getProperty(yamlPath).trim();
            String actualSha = computeSha256(yamlPath);

            if (actualSha == null) {
                missing.add(yamlPath);
            } else if (!expectedSha.equals(actualSha)) {
                mismatches.add(String.format(
                    "  %s%n    expected: %s%n    actual:   %s",
                    yamlPath, expectedSha, actualSha));
            }
        }

        StringBuilder msg = new StringBuilder();
        if (!mismatches.isEmpty()) {
            msg.append("MAL YAML files changed (regenerate corresponding tests):\n");
            for (String m : mismatches) {
                msg.append(m).append('\n');
            }
        }
        if (!missing.isEmpty()) {
            msg.append("MAL YAML files not found on classpath:\n");
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

    private String computeSha256(final String resourcePath) {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
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
