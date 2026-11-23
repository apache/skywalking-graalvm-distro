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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;

/**
 * Exports the rule sources the read-only rule catalog serves (META-INF/rule-source/) and the OAL scripts.
 */
@Slf4j
final class RuleSourceExport {
    /**
     * Exports the rule files the image was compiled from, of the catalogs whose upstream loader records the files it
     * reads ({@link RuleCatalog#mergedByLoader}), verbatim under {@code META-INF/rule-source/}, with a line each in its
     * {@code index.txt}: {@code catalog|relativePath}, the rule name being the path without extension. The replacement
     * loaders record the files they load from these, as upstream's loaders record the files they read, which the
     * read-only {@code /runtime/rule} catalog lists. The files are every staged one, a file yielding no rule too, as
     * upstream's loaders record every file they read.
     */
    static void exportRuleSources(String outputDir, List<RuleCatalog> catalogs) throws Exception {
        Path root = Path.of(outputDir, "META-INF", "rule-source");
        Files.createDirectories(root);
        Path configData = Path.of(outputDir, "META-INF", "config-data");
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<String> index = new ArrayList<>();
        for (RuleCatalog ruleCatalog : catalogs) {
            String catalog = ruleCatalog.getDirectory();
            // Every staged file the loader reads, a file yielding no rule too, as upstream's loader records it; of TAL,
            // the files the TAL pass carries (none requiring a module the image does not ship).
            List<String> files = new ArrayList<>();
            if (ruleCatalog.getEngine() == RuleCatalog.Engine.TAL) {
                mapper.readTree(configData.resolve(catalog + ".json").toFile()).fieldNames().forEachRemaining(files::add);
            } else {
                files.addAll(RuleTree.rulePaths(catalog));
            }
            Path dir = RuleTree.existingDirectory(catalog);
            for (String rel : files) {
                Path out = root.resolve(catalog).resolve(rel);
                Files.createDirectories(out.getParent());
                Files.write(out, Files.readAllBytes(dir.resolve(rel)));
                index.add(catalog + "|" + rel);
            }
        }
        Files.write(root.resolve("index.txt"), index, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        log.info("Rule sources: exported {} files of {}", index.size(), catalogs);
    }

    /**
     * Copies the OAL scripts to their upstream classpath paths ({@code oal/core.oal} ...): the dsl-debugging module's
     * {@code RuntimeOalRestHandler} reads them for {@code GET /runtime/oal/files/{name}}.
     */
    static void exportOalScripts(String outputDir, OALDefine[] oalDefines) throws IOException {
        ClassLoader cl = RuleSourceExport.class.getClassLoader();
        for (OALDefine define : oalDefines) {
            String configFile = define.getConfigFile();
            try (InputStream is = cl.getResourceAsStream(configFile)) {
                if (is == null) {
                    throw new IllegalStateException("The OAL script " + configFile + " is not on the classpath");
                }
                Path out = Path.of(outputDir, configFile);
                Files.createDirectories(out.getParent());
                Files.write(out, is.readAllBytes());
            }
        }
        log.info("Rule sources: exported {} OAL scripts", oalDefines.length);
    }
}
