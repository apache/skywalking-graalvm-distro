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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The LAL config data the registry is generated from holds every file upstream's loader keeps, by its own name: a file
 * of no rule too, as the runtime registers its {@code layerDefinitions} when it loads it.
 */
class LalConfigDataTest {
    @TempDir
    Path out;

    @Test
    void aFileOfLayersAndNoRuleIsKeptByItsName() throws Exception {
        Files.createDirectories(out.resolve("META-INF"));
        LalPass.compileLAL(out.toString(), "lal-fixture-layers");

        final JsonNode data = new ObjectMapper().readTree(out.resolve("META-INF/config-data/lal.json").toFile());
        final List<String> files = new ArrayList<>();
        data.fieldNames().forEachRemaining(files::add);
        files.sort(null);
        assertEquals(List.of("layers.yaml", "user.yml"), files);
        assertEquals("FIXTURE_LAL_ONLY", data.at("/layers.yaml/layerDefinitions/0/name").asText());
        assertEquals(0, data.at("/layers.yaml/rules").size());
        assertEquals("uses-the-layer", data.at("/user.yml/rules/0/name").asText());
    }
}
