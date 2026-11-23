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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.graalvm.OverlayFiles;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every LAL rule a rules overlay added or overrode loads from the image, and has its class where the runtime DSL looks
 * it up (its source coordinates, name, DSL text and the types upstream's boot resolves); nothing is compiled for a
 * rule the boot skips. A file without rules is read and recorded for the rule catalog, and yields none. Nothing runs
 * without an overlay.
 */
class OverlayLalRuleTest {
    private static final Path STAGED = Path.of(System.getProperty("rules.staging.dir",
        Path.of(System.getProperty("user.dir")).getParent().resolve("build-tools/precompiler/target/rule-resources")
            .toString()));

    /** The overlay's layers, as the image registers them at boot, so its rules resolve them. */
    @BeforeAll
    static void registerCompiledLayers() throws Exception {
        UpstreamLalBoot.registerLayers();
    }

    @TestFactory
    Stream<DynamicTest> everyOverlayLalRuleIsCompiled() throws Exception {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final String file : OverlayFiles.under("lal")) {
            tests.add(DynamicTest.dynamicTest(file, () -> {
                // As the log analyzer lists it: the file name without its last extension.
                final String name = file.substring("lal/".length(), file.lastIndexOf('.'));
                final List<LALConfig> rules = new ArrayList<>();
                LALConfigs.load("lal", List.of(name)).forEach(configs -> rules.addAll(configs.getRules()));
                assertEquals(ruleCount(STAGED.resolve(file)), rules.size(), "the rules loaded from " + file);
                // Read and recorded for the rule catalog as upstream's loader records it, a file without rules too
                assertEquals(Files.readString(STAGED.resolve(file)), StaticRuleRegistry.active().find("lal", name)
                                                                                  .orElse(null), file);
                final Set<List<String>> found = new HashSet<>();
                for (final LALConfig rule : rules) {
                    assertEquals(file, rule.getSourcePath(), "the source " + rule.getName() + " is stamped with");
                    // The boot looks up no class for a rule it skips.
                    if (UpstreamLalBoot.skips(rule)) {
                        continue;
                    }
                    final LalRule compiled = UpstreamLalBoot.compiledRule(rule);
                    assertNotNull(compiled, "no class compiled for " + rule.getName() + " at line " + rule.getLineNo());
                    found.add(UpstreamLalBoot.compiledFrom(compiled));
                }
                // Nothing is compiled for a rule the boot skips.
                assertEquals(CompiledRules.lalRules().stream().filter(compiled -> compiled.getSource().equals(file))
                                          .map(UpstreamLalBoot::compiledFrom).collect(Collectors.toSet()),
                    found, "the classes compiled from " + file);
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
