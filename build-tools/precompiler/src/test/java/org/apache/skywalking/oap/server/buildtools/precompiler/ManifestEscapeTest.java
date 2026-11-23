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

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry is generated from the rules manifests, so a MAL expression and filter text must read back exactly as
 * written: the runtime requires the text MetricConvert passes to equal it.
 */
class ManifestEscapeTest {
    @Test
    void aValueReadsBackAsWritten(@TempDir final Path dir) throws Exception {
        final String[] values = {
            " { tags -> tags.job_name == 'a' }", "\tleading tab", "a\\b", "line\nbreak", "carriage\rreturn", "form\ffeed",
            "  two spaces", "x = y: z", "unicode é"};
        final Properties written = new Properties();
        for (int i = 0; i < values.length; i++) {
            written.setProperty("filter." + i + ".text", values[i]);
        }
        final Path file = dir.resolve("rules.txt");
        Precompiler.writeEscapedProperties(file, written);
        final Properties read = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            read.load(reader);
        }
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], read.getProperty("filter." + i + ".text"), values[i]);
        }
    }
}
