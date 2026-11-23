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
import lombok.Getter;
import lombok.ToString;
import org.apache.skywalking.oap.meter.analyzer.v2.compiler.MALClassGenerator;
import org.apache.skywalking.oap.server.buildtools.precompiler.GeneratedInstanceCheck;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;

/**
 * Build-time same-FQCN replacement of upstream v2 MAL FilterExpression: compiles with upstream's
 * {@link MALClassGenerator} as its startup path does, writing the classes to the output directory, and records each
 * filter with the coordinates and literal {@code MetricConvert} passed, by which the rule registry keys the classes.
 * Nothing filters samples at build time.
 */
@ToString(of = {"literal"})
public class FilterExpression {

    private static final MALClassGenerator GENERATOR = new MALClassGenerator();

    /**
     * Every filter compiled, in order. The precompiler reads it after the MAL pass.
     */
    public static final List<Compiled> COMPILED = Collections.synchronizedList(new ArrayList<>());

    /**
     * The coordinates and literals compiled: a boot asking again (the Zabbix receiver builds a converter per host
     * pattern of one rule file) gets the filter the runtime registry holds for it, compiled once.
     */
    private static final Set<List<String>> COMPILED_INPUTS = ConcurrentHashMap.newKeySet();

    /**
     * Forgets every filter compiled, for a MAL pass to start over.
     */
    public static void clearCompiled() {
        COMPILED.clear();
        COMPILED_INPUTS.clear();
    }

    public static void setClassOutputDir(final File dir) {
        GENERATOR.setClassOutputDir(dir);
    }

    @Getter
    private final String literal;

    public FilterExpression(final String literal) {
        this(literal, null, null);
    }

    public FilterExpression(final String literal, final String filterNameHint) {
        this(literal, filterNameHint, null);
    }

    public FilterExpression(final String literal,
                            final String filterNameHint,
                            final DslSourceRef sourceRef) {
        this.literal = literal;
        if (sourceRef == null) {
            throw new IllegalStateException("MAL filter expression has no source coordinates, which the rule registry "
                + "keys pre-compiled filters by: " + literal);
        }
        if (!COMPILED_INPUTS.add(List.of(sourceRef.describeYaml(), literal))) {
            return;
        }
        try {
            if (filterNameHint != null) {
                GENERATOR.setClassNameHint(filterNameHint);
            }
            GENERATOR.setSourceRef(sourceRef);
            try {
                final MalFilter malFilter = GENERATOR.compileFilter(literal);
                GeneratedInstanceCheck.requireNoStateAfterConstruction(malFilter, Set.of(), "MAL filter " + literal);
                COMPILED.add(new Compiled(sourceRef, literal, malFilter.getClass().getName()));
            } finally {
                GENERATOR.setClassNameHint(null);
                GENERATOR.setSourceRef(null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile MAL filter expression (" + sourceRef.describeYaml()
                + "): " + literal + ": " + e.getMessage(), e);
        }
    }

    // Runtime-rule overload (upstream signature). The precompiler only runs the startup path
    // (pool/targetClassLoader null), so it delegates to the shared-generator compile.
    public FilterExpression(final String literal,
                            final String filterNameHint,
                            final DslSourceRef sourceRef,
                            final ClassPool pool,
                            final ClassLoader targetClassLoader) {
        this(literal, filterNameHint, sourceRef);
    }

    public Map<String, SampleFamily> filter(final Map<String, SampleFamily> sampleFamilies) {
        throw new UnsupportedOperationException("filter is not available at build time. literal=" + literal);
    }

    /**
     * A compiled filter: what {@code MetricConvert} passed and the class generated from it.
     */
    public record Compiled(DslSourceRef sourceRef, String literal, String className) {
    }
}
