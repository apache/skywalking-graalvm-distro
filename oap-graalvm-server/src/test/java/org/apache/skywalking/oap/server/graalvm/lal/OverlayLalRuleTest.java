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

package org.apache.skywalking.oap.server.graalvm.lal;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.graalvm.OverlayFiles;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every LAL rule a rules overlay added or overrode loads from the image, and has its class under the source
 * coordinates the log analyzer looks it up by. Nothing runs without an overlay.
 */
class OverlayLalRuleTest extends LALScriptComparisonBase {
    private static final Path STAGED = Path.of(System.getProperty("rules.staging.dir",
        Path.of(System.getProperty("user.dir")).getParent().resolve("build-tools/precompiler/target/rule-resources")
            .toString()));

    /** The overlay's layers, as the image registers them at boot, so its rules resolve them. */
    @BeforeAll
    static void registerCompiledLayers() {
        LayerExtensionLoader.load();
    }

    @TestFactory
    Stream<DynamicTest> everyOverlayLalRuleIsCompiled() throws Exception {
        final Map<String, LalRule> registry = loadRegistry();
        final List<DynamicTest> tests = new ArrayList<>();
        for (final String file : OverlayFiles.under("lal")) {
            tests.add(DynamicTest.dynamicTest(file, () -> {
                // As the log analyzer lists it: the file name without its last extension.
                final String name = file.substring("lal/".length(), file.lastIndexOf('.'));
                final List<LALConfig> rules = new ArrayList<>();
                LALConfigs.load("lal", List.of(name)).forEach(configs -> rules.addAll(configs.getRules()));
                assertEquals(ruleCount(STAGED.resolve(file)), rules.size(), "the rules loaded from " + file);
                for (final LALConfig rule : rules) {
                    assertEquals(file, rule.getSourcePath(), "the source " + rule.getName() + " is stamped with");
                    assertNotNull(registry.get(rule.getSourcePath() + ":" + rule.getLineNo()),
                        "no class compiled for " + rule.getName() + " at " + rule.getSourcePath() + ":"
                            + rule.getLineNo());
                }
            }));
        }
        return tests.stream();
    }

    private static int ruleCount(final Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            final Map<String, Object> yaml = new Yaml().load(reader);
            return yaml == null || !(yaml.get("rules") instanceof List) ? 0 : ((List<?>) yaml.get("rules")).size();
        }
    }
}
