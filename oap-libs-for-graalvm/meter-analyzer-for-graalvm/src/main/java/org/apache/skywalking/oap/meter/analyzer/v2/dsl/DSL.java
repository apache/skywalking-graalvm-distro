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

package org.apache.skywalking.oap.meter.analyzer.v2.dsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javassist.ClassPool;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalExpression;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * Same-FQCN replacement for upstream v2 MAL DSL.
 *
 * <p>Returns the {@link MalExpression} the precompiler compiled for the rule at the coordinates {@code MetricConvert}
 * passes, from the generated {@link CompiledRules} registry, instead of compiling it with MALClassGenerator at
 * runtime; the metric name and expression text must be those it was compiled from. Closure fields are self-wired by
 * the generated class's static initializer.
 *
 * <p>Left out on purpose: {@code extractMetadata(String)}, which only runtime-rule (not shipped) calls.
 */
public final class DSL {
    public static Expression parse(final String metricName, final String expression) {
        return parse(metricName, expression, null);
    }

    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef) {
        // Without coordinates (no rule upstream locates), by metric name: classes compiled from one text run alike.
        final List<CompiledMalExpression> candidates = sourceRef != null
            ? Index.BY_SOURCE.getOrDefault(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine(), List.of())
            : Index.BY_NAME.getOrDefault(metricName, List.of());
        return new Expression(metricName, expression, select(candidates, metricName, expression, sourceRef).get());
    }

    /**
     * @return the class compiled for this metric name and text among those compiled where upstream locates the rule
     * @throws IllegalStateException when none was
     */
    static Supplier<MalExpression> select(final List<CompiledMalExpression> candidates, final String metricName,
                                          final String expression, final DslSourceRef sourceRef) {
        for (final CompiledMalExpression candidate : candidates) {
            if (candidate.getMetricName().equals(metricName) && candidate.getExpression().equals(expression)) {
                return candidate.getCompiled();
            }
        }
        throw new IllegalStateException(
            "No pre-compiled MAL expression for metric: " + metricName + " ("
                + (sourceRef == null ? "no source coordinates" : sourceRef.describeYaml()) + "), expression: "
                + expression + "; compiled there: "
                + candidates.stream().map(c -> c.getMetricName() + ": " + c.getExpression())
                            .collect(Collectors.toList())
                + ". The rule files changed after the build; rebuild the distro.");
    }

    // Runtime-rule overload (upstream signature). We load the pre-compiled class by coordinates,
    // so pool/targetClassLoader are ignored; runtime-rule hot-update is unsupported (501).
    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef,
                                   final ClassPool pool,
                                   final ClassLoader targetClassLoader) {
        return parse(metricName, expression, sourceRef);
    }

    private static final class Index {
        private static final Map<String, List<CompiledMalExpression>> BY_SOURCE = new HashMap<>();
        private static final Map<String, List<CompiledMalExpression>> BY_NAME = new HashMap<>();

        static {
            for (final CompiledMalExpression compiled : CompiledRules.malExpressions()) {
                BY_SOURCE.computeIfAbsent(compiled.getSource() + ":" + compiled.getLine(), k -> new ArrayList<>())
                         .add(compiled);
                BY_NAME.computeIfAbsent(compiled.getMetricName(), k -> new ArrayList<>()).add(compiled);
            }
        }
    }
}
