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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes the manifests and config-data the passes record, the intermediates the rule registry and the native-image
 * metadata are generated from.
 */
final class BuildIntermediates {
    /**
     * Scan output directory for v2 generated .class files matching a package prefix.
     * Converts directory structure to fully-qualified class names.
     */
    static List<String> scanV2Classes(String outputDir, String packagePrefix) throws IOException {
        String packagePath = packagePrefix.replace('.', '/');
        Path dir = Path.of(outputDir, packagePath);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<String> classNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".class"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String simpleName = fileName.substring(0, fileName.length() - ".class".length());
                     classNames.add(packagePrefix + simpleName);
                 });
        }
        Collections.sort(classNames);
        return classNames;
    }

    /**
     * Writes a rule's name into a build manifest, a rule without one (which upstream's factories compile) as such:
     * {@code unnamed=true} and no {@code name}, so it is told apart from a rule named {@code ""} or {@code "null"}.
     */
    static void setRuleName(Properties manifest, String prefix, String name) {
        if (name == null) {
            manifest.setProperty(prefix + "unnamed", "true");
        } else {
            manifest.setProperty(prefix + "name", name);
        }
    }

    /**
     * The rule registry is generated from this JSON, so it has to hold all upstream's loader read: write it, read it
     * back and require the same state, or a property Jackson cannot see (a setter without a getter) would vanish
     * before the registry is generated.
     */
    static void writeConfigData(ObjectMapper mapper, Path file, Object value, TypeReference<?> type)
        throws Exception {
        byte[] json = mapper.writeValueAsBytes(value);
        // Read back as RegistryGenerator reads it: a computed getter (Rule#getSourceName) is written, never read.
        Object readBack = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                                .readValue(json, type);
        String difference = StateComparison.firstDifference(value, readBack, file.getFileName().toString());
        if (difference != null) {
            throw new IllegalStateException("Rule data does not survive its JSON copy, at " + difference
                + ": the rule registry would lose it; teach the precompiler to carry it");
        }
        Files.write(file, json);
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static void writeManifest(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * {@link Properties#store}'s escaping, so any rule or file name round-trips through
     * {@code Properties.load(Reader)} with UTF-8, one entry per line, sorted so the build is reproducible.
     */
    static void writeEscapedProperties(Path path, Properties props) throws IOException {
        StringWriter raw = new StringWriter();
        props.store(raw, null);
        List<String> lines = raw.toString().lines()
            .filter(line -> !line.startsWith("#"))
            .sorted()
            .collect(Collectors.toList());
        Files.write(path, lines, StandardCharsets.UTF_8);
    }
}
