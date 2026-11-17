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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javassist.ClassPool;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * Same-FQCN replacement for upstream v2 MAL FilterExpression.
 *
 * <p>Returns the {@link MalFilter} the precompiler compiled from the filter literal, from the generated
 * {@link CompiledRules} registry, instead of compiling it at runtime.
 */
@Slf4j
@ToString(of = {"literal"})
public class FilterExpression {
    @Getter
    private final String literal;
    private final MalFilter malFilter;

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
        final Supplier<MalFilter> compiled = CompiledRules.malFilters().get(literal);
        if (compiled == null) {
            throw new IllegalStateException(
                "Pre-compiled MAL filter not found for: " + literal
                    + ". Available filters: " + CompiledRules.malFilters().size());
        }
        malFilter = compiled.get();
    }

    // Runtime-rule overload (upstream signature). We load the pre-compiled filter by literal,
    // so pool/targetClassLoader are ignored; runtime-rule hot-update is unsupported (501).
    public FilterExpression(final String literal,
                            final String filterNameHint,
                            final DslSourceRef sourceRef,
                            final ClassPool pool,
                            final ClassLoader targetClassLoader) {
        this(literal, filterNameHint, sourceRef);
    }

    public Map<String, SampleFamily> filter(final Map<String, SampleFamily> sampleFamilies) {
        try {
            final Map<String, SampleFamily> result = new HashMap<>();
            for (final Map.Entry<String, SampleFamily> entry : sampleFamilies.entrySet()) {
                final SampleFamily afterFilter = entry.getValue().filter(malFilter::test);
                if (!Objects.equals(afterFilter, SampleFamily.EMPTY)) {
                    result.put(entry.getKey(), afterFilter);
                }
            }
            return result;
        } catch (Throwable t) {
            log.error("failed to run \"{}\"", literal, t);
        }
        return sampleFamilies;
    }
}
