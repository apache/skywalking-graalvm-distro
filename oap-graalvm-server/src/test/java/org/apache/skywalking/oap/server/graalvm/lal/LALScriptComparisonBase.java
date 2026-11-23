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

import java.util.ArrayList;
import java.util.List;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for the LAL pre-compilation tests: every rule of a bundled file has its class in the rule registry,
 * where the runtime DSL looks it up, and it instantiates.
 */
abstract class LALScriptComparisonBase {
    private static boolean LAYERS_REGISTERED;

    /**
     * Load LAL rules from a YAML file under the lal/ resource directory.
     */
    protected static List<LALConfig> loadLALRules(final String yamlFileName)
            throws Exception {
        final List<String> fileNames = singletonList(
            yamlFileName.replace(".yaml", "").replace(".yml", ""));
        final List<LALConfigs> configs = LALConfigs.load("lal", fileNames);
        final List<LALConfig> rules = new ArrayList<>();
        for (final LALConfigs c : configs) {
            rules.addAll(c.getRules());
        }
        return rules;
    }

    /**
     * The pre-compiled LalExpression of a rule, found as the runtime DSL finds it: among the classes compiled at the
     * coordinates of the DslSourceRef upstream builds for the rule, the one of its name, DSL text and the input and
     * output types upstream's boot resolves for it.
     */
    protected static LalExpression loadPrecompiled(final LALConfig rule) throws Exception {
        registerLayers();
        final LalRule compiled = UpstreamLalBoot.compiledRule(rule);
        assertNotNull(compiled, "Pre-compiled LAL expression not found for rule " + rule.getName() + " ("
            + rule.getSourcePath() + ":" + rule.getLineNo() + ")");
        return compiled.getExpression().get();
    }

    /**
     * The layers the image registers, so a rule's layer resolves to its source type provider as at boot.
     */
    private static synchronized void registerLayers() throws Exception {
        if (!LAYERS_REGISTERED) {
            UpstreamLalBoot.registerLayers();
            LAYERS_REGISTERED = true;
        }
    }
}
