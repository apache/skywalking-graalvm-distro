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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javassist.ClassPool;
import org.apache.skywalking.oap.meter.analyzer.v2.compiler.MALClassGenerator;
import org.apache.skywalking.oap.server.buildtools.precompiler.GeneratedInstanceCheck;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;

/**
 * Build-time same-FQCN replacement of upstream v2 MAL DSL: compiles with upstream's {@link MALClassGenerator} as its
 * startup path does, writing the classes to the output directory, and records each expression with the coordinates,
 * metric name and text {@code MetricConvert} passed, by which the rule registry keys the classes.
 */
public final class DSL {

    private static final MALClassGenerator GENERATOR = new MALClassGenerator();

    /**
     * Every expression compiled, in order. The precompiler reads it after the MAL pass.
     */
    public static final List<Compiled> COMPILED = Collections.synchronizedList(new ArrayList<>());

    /**
     * The class compiled for each coordinate, metric name and text: a boot asking again (the Zabbix receiver builds a
     * converter per host pattern of one rule file) gets the class the runtime registry holds for it, compiled once.
     */
    private static final Map<List<String>, MalExpression> BY_INPUTS = new ConcurrentHashMap<>();

    /**
     * Forgets every expression compiled, for a MAL pass to start over.
     */
    public static void clearCompiled() {
        COMPILED.clear();
        BY_INPUTS.clear();
    }

    public static void setClassOutputDir(final File dir) {
        GENERATOR.setClassOutputDir(dir);
    }

    public static Expression parse(final String metricName, final String expression) {
        return parse(metricName, expression, null);
    }

    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef) {
        if (sourceRef == null) {
            throw new IllegalStateException("MAL expression for metric: " + metricName + " has no source coordinates, "
                + "which the rule registry keys pre-compiled expressions by: " + expression);
        }
        final List<String> inputs = List.of(sourceRef.describeYaml(), metricName, expression);
        final MalExpression compiled = BY_INPUTS.get(inputs);
        if (compiled != null) {
            return new Expression(metricName, expression, compiled);
        }
        try {
            GENERATOR.setSourceRef(sourceRef);
            GENERATOR.setContent(expression);
            final MalExpression malExpr = GENERATOR.compile(metricName, expression);
            GeneratedInstanceCheck.requireNoStateAfterConstruction(
                malExpr, Set.of(), "MAL expression of " + metricName);
            COMPILED.add(new Compiled(sourceRef, metricName, expression, malExpr.getClass().getName()));
            BY_INPUTS.put(inputs, malExpr);
            return new Expression(metricName, expression, malExpr);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to compile MAL expression for metric: " + metricName
                    + " (" + sourceRef.describeYaml() + ")"
                    + ", expression: " + expression + ": " + e.getMessage(), e);
        }
    }

    // Runtime-rule overload (upstream signature). The precompiler only runs the startup path
    // (pool/targetClassLoader null), so it delegates to the shared-GENERATOR compile.
    public static Expression parse(final String metricName,
                                   final String expression,
                                   final DslSourceRef sourceRef,
                                   final ClassPool pool,
                                   final ClassLoader targetClassLoader) {
        return parse(metricName, expression, sourceRef);
    }

    /**
     * A compiled expression: what {@code MetricConvert} passed and the class generated from it.
     */
    public record Compiled(DslSourceRef sourceRef, String metricName, String expression, String className) {
    }
}
