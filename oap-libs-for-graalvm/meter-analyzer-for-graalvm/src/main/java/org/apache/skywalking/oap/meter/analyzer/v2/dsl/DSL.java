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

import java.util.function.Supplier;
import javassist.ClassPool;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * Same-FQCN replacement for upstream v2 MAL DSL.
 *
 * <p>Returns the {@link MalExpression} the precompiler compiled from the expression text, which is the key
 * {@code MetricConvert} passes, from the generated {@link CompiledRules} registry instead of compiling it with
 * MALClassGenerator at runtime. Closure fields are self-wired by the generated class's static initializer.
 */
public final class DSL {
    public static Expression parse(final String metricName, final String expression) {
        return parse(metricName, expression, null);
    }

    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef) {
        final Supplier<MalExpression> compiled = CompiledRules.malExpressions().get(expression);
        if (compiled == null) {
            throw new IllegalStateException(
                "Pre-compiled MAL expression not found for metric: " + metricName
                    + " (expression: " + expression + ")"
                    + ". Available: " + CompiledRules.malExpressions().size() + " expressions");
        }
        return new Expression(metricName, expression, compiled.get());
    }

    // Runtime-rule overload (upstream signature). We load the pre-compiled class by expression
    // text, so pool/targetClassLoader are ignored; runtime-rule hot-update is unsupported (501).
    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef,
                                   final ClassPool pool,
                                   final ClassLoader targetClassLoader) {
        return parse(metricName, expression, sourceRef);
    }
}
