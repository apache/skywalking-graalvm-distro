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

package org.apache.skywalking.oap.log.analyzer.v2.dsl;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;

/**
 * Finds a LAL rule's compiled class as the runtime {@code DSL.of} does: among the rules compiled at the
 * {@code DslSourceRef} upstream builds from the stamped coordinates, the one {@code DSL.select} takes for the rule's
 * name, DSL text and the input and output types upstream's boot passes. Rules on one line of one name are told apart
 * as the boot tells them apart.
 */
public final class CompiledLalRuleLookup {
    private CompiledLalRuleLookup() {
    }

    /**
     * @return the compiled rule, or null when none was compiled from these inputs
     */
    public static LalRule find(final LALConfig config, final Class<?> inputType, final Class<?> outputType) {
        final String source = DslSourceRef.ofRule(config.getSourcePath(), config.getLineNo()).describeYaml();
        final List<LalRule> candidates = CompiledRules.lalRules().stream()
            .filter(rule -> DslSourceRef.ofRule(rule.getSource(), rule.getLine()).describeYaml().equals(source))
            .collect(Collectors.toList());
        return DSL.select(candidates, config.getName(), config.getDsl(), inputType, outputType);
    }
}
