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
import java.util.Objects;
import java.util.stream.Collectors;
import javassist.ClassPool;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledMalFilter;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * Same-FQCN replacement for upstream v2 MAL FilterExpression.
 *
 * <p>Returns the {@link MalFilter} the precompiler compiled for the rule file's filter at the coordinates
 * {@code MetricConvert} passes, from the generated {@link CompiledRules} registry, instead of compiling it at runtime;
 * the literal must be the one it was compiled from.
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
        malFilter = compiledFilter(literal, sourceRef);
    }

    // Runtime-rule overload (upstream signature). We load the pre-compiled filter by coordinates,
    // so pool/targetClassLoader are ignored; runtime-rule hot-update is unsupported (501).
    public FilterExpression(final String literal,
                            final String filterNameHint,
                            final DslSourceRef sourceRef,
                            final ClassPool pool,
                            final ClassLoader targetClassLoader) {
        this(literal, filterNameHint, sourceRef);
    }

    private static MalFilter compiledFilter(final String literal, final DslSourceRef sourceRef) {
        // Without coordinates (no rule file upstream locates), by literal: classes compiled from one literal run alike.
        final List<CompiledMalFilter> candidates = sourceRef != null
            ? Index.BY_SOURCE.getOrDefault(sourceRef.getYamlFile() + ":" + sourceRef.getYamlLine(), List.of())
            : Index.BY_LITERAL.getOrDefault(literal, List.of());
        return select(candidates, literal, sourceRef);
    }

    /**
     * @return the filter compiled from this literal among those compiled where upstream locates the rule file
     * @throws IllegalStateException when none was
     */
    static MalFilter select(final List<CompiledMalFilter> candidates, final String literal,
                            final DslSourceRef sourceRef) {
        for (final CompiledMalFilter candidate : candidates) {
            if (candidate.getLiteral().equals(literal)) {
                return candidate.getCompiled().get();
            }
        }
        throw new IllegalStateException(
            "No pre-compiled MAL filter expression ("
                + (sourceRef == null ? "no source coordinates" : sourceRef.describeYaml()) + "): " + literal
                + "; compiled there: "
                + candidates.stream().map(CompiledMalFilter::getLiteral).collect(Collectors.toList())
                + ". The rule files changed after the build; rebuild the distro.");
    }

    public Map<String, SampleFamily> filter(final Map<String, SampleFamily> sampleFamilies) {
        final Map<String, SampleFamily> result = new HashMap<>();
        // As upstream: a sample the filter fails on is dropped, the batch's other samples filtered as usual.
        final Throwable[] failure = new Throwable[1];
        for (final Map.Entry<String, SampleFamily> entry : sampleFamilies.entrySet()) {
            final SampleFamily afterFilter = entry.getValue().filter(labels -> {
                try {
                    return malFilter.test(labels);
                } catch (Throwable t) {
                    if (failure[0] == null) {
                        failure[0] = t;
                    }
                    return false;
                }
            });
            if (!Objects.equals(afterFilter, SampleFamily.EMPTY)) {
                result.put(entry.getKey(), afterFilter);
            }
        }
        if (failure[0] != null) {
            log.error("failed to run \"{}\" on a sample; the samples it failed on are dropped", literal, failure[0]);
        }
        return result;
    }

    private static final class Index {
        private static final Map<String, List<CompiledMalFilter>> BY_SOURCE = new HashMap<>();
        private static final Map<String, List<CompiledMalFilter>> BY_LITERAL = new HashMap<>();

        static {
            for (final CompiledMalFilter compiled : CompiledRules.malFilters()) {
                BY_SOURCE.computeIfAbsent(compiled.getSource() + ":" + compiled.getLine(), k -> new ArrayList<>())
                         .add(compiled);
                BY_LITERAL.computeIfAbsent(compiled.getLiteral(), k -> new ArrayList<>()).add(compiled);
            }
        }
    }
}
