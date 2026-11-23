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
 *
 */

package org.apache.skywalking.oap.server.buildtools.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The committed {@code YamlConfigLoaderUtils} is what the generator writes for the current upstream config classes.
 * A setting it has no branch for is logged as unsupported and ignored at boot, so a sync that adds a config field
 * without regenerating would silently drop it.
 */
class GeneratedConfigLoaderIsCurrentTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent().getParent();
    private static final Path LOADER = ROOT.resolve(
        "oap-graalvm-server/src/main/java/org/apache/skywalking/oap/server/library/util/YamlConfigLoaderUtils.java");
    private static final Path MANIFEST = ROOT.resolve(
        "build-tools/precompiler/src/main/resources/META-INF/module-config-classes.txt");

    @Test
    void theCommittedLoaderIsWhatTheGeneratorWrites(@TempDir final Path out) throws Exception {
        final Path loader = out.resolve("YamlConfigLoaderUtils.java");
        final Path manifest = out.resolve("module-config-classes.txt");
        ConfigInitializerGenerator.main(new String[] {loader.toString(), manifest.toString()});

        final String regenerate = " is stale: regenerate it with the config-generator (.claude/skills/sync-submodule, "
            + "\"Config handling\")";
        assertEquals(Files.readString(loader), Files.readString(LOADER), LOADER + regenerate);
        assertEquals(Files.readString(manifest), Files.readString(MANIFEST), MANIFEST + regenerate);
        final Path setByField = MANIFEST.resolveSibling(ConfigInitializerGenerator.SET_BY_FIELD_MANIFEST);
        assertEquals(Files.readString(manifest.resolveSibling(ConfigInitializerGenerator.SET_BY_FIELD_MANIFEST)),
            Files.readString(setByField), setByField + regenerate);
    }
}
