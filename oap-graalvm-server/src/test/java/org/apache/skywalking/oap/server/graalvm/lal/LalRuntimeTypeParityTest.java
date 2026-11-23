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

package org.apache.skywalking.oap.server.graalvm.lal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LAL {@code DSL} replacement runs a rule's class only when upstream's boot passes the input and output types the
 * class was compiled for. The precompiler resolves them on its own classpath; upstream resolves them here, through
 * the SPI providers the server ships. A provider only one of the two sees would fail the native boot: catch it here,
 * finding every rule the boot runs as the runtime {@code DSL} finds it, with upstream's own resolution, so rules on one
 * line of one name that the build tells apart by their inputs are found apart.
 */
class LalRuntimeTypeParityTest {

    @BeforeAll
    static void registerLayers() throws Exception {
        UpstreamLalBoot.registerLayers();
    }

    @Test
    void everyRuleWasCompiledForTheTypesTheBootResolves() throws Exception {
        final Set<List<String>> found = new HashSet<>();
        for (final LalFile file : CompiledRules.lalFiles()) {
            if (file.getConfigs().getRules() == null) {
                continue;
            }
            for (final LALConfig config : file.getConfigs().getRules()) {
                // The boot looks up no class for a rule it skips.
                if (UpstreamLalBoot.skips(config)) {
                    continue;
                }
                // Found as the boot finds it: at the DslSourceRef built from the stamped coordinates, by the name, the
                // DSL text and the input and output types upstream resolves for it.
                final LalRule rule = UpstreamLalBoot.compiledRule(config);
                assertNotNull(rule, "no class compiled for " + config.getName() + " (" + config.getSourcePath() + ":"
                    + config.getLineNo() + ") with input " + typeName(UpstreamLalBoot.inputType(config)) + " and output "
                    + typeName(UpstreamLalBoot.outputType(config)));
                found.add(UpstreamLalBoot.compiledFrom(rule));
            }
        }
        // Compiled exactly for the rules the boot does not skip: a layer or provider only the server registers would
        // show here.
        final Set<List<String>> compiled = CompiledRules.lalRules().stream().map(UpstreamLalBoot::compiledFrom)
                                                        .collect(Collectors.toSet());
        compiled.removeAll(found);
        assertTrue(compiled.isEmpty(), "classes compiled for no rule the boot runs: " + compiled);
    }

    private static String typeName(final Class<?> type) {
        return type == null ? "" : type.getName();
    }
}
