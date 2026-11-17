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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for LAL v2 pre-compilation tests.
 *
 * <p>Verifies that pre-compiled LAL classes from the build-time manifest
 * can be loaded and instantiated. With v2, both fresh compilation and
 * pre-compilation use the same ANTLR4+Javassist engine, so the main
 * verification is that the precompiler correctly captured all classes.
 */
abstract class LALScriptComparisonBase {
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
     * Load the pre-compiled LalExpression for a rule by the same source coordinates the runtime
     * DSL replacement uses ({@code sourcePath:lineNo} in the generated rule registry), falling back to
     * the rule name. Rule names repeat across files (network-profiling-slow-trace), so the
     * coordinates are what disambiguate.
     */
    protected static LalExpression loadPrecompiled(final LALConfig rule) {
        final Map<String, LalRule> rules = loadRegistry();
        LalRule compiled = rules.get(rule.getSourcePath() + ":" + rule.getLineNo());
        if (compiled == null) {
            compiled = rules.get("name:" + rule.getName());
        }
        assertNotNull(compiled, "Pre-compiled LAL expression not found for rule: " + rule.getName());
        return compiled.getExpression().get();
    }

    protected static LalExpression loadPrecompiled(final String ruleName) {
        final LalRule compiled = loadRegistry().get("name:" + ruleName);
        assertNotNull(compiled, "Pre-compiled LAL expression not found for rule: " + ruleName);
        return compiled.getExpression().get();
    }

    /** Keys: {@code <sourcePath>:<line>} and {@code name:<ruleName>} (first occurrence). */
    protected static Map<String, LalRule> loadRegistry() {
        final Map<String, LalRule> map = new HashMap<>();
        for (final LalRule rule : CompiledRules.lalRules()) {
            map.put(rule.getSource() + ":" + rule.getLine(), rule);
            map.putIfAbsent("name:" + rule.getName(), rule);
        }
        return map;
    }
}
