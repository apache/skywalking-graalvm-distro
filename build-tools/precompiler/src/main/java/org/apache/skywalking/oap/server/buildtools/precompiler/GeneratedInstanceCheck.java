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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * The runtime builds every pre-compiled DSL class by its no-arg constructor, so whatever a generator sets on the
 * instance it returns, after constructing it, is missing there. This fails the build when a generated instance holds
 * state a fresh one does not, outside the fields the distro replays (TAL's function objects), so a generator change
 * that wires instances after construction surfaces here instead of as a rule that runs without that state.
 */
public final class GeneratedInstanceCheck {
    private GeneratedInstanceCheck() {
    }

    /**
     * @param compiled what the generator returned
     * @param replayed the fields the runtime sets after construction
     * @param what     the rule, for the message
     */
    public static void requireNoStateAfterConstruction(final Object compiled, final Set<String> replayed,
                                                       final String what) {
        final Class<?> type = compiled.getClass();
        final Object fresh;
        try {
            fresh = type.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(what + ": " + type.getName() + " has no usable no-arg constructor, which "
                + "the distro builds pre-compiled classes by", e);
        }
        // One comparison across the fields: two fields sharing an object match only two fields sharing one.
        final StateComparison comparison = StateComparison.context();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || replayed.contains(field.getName())) {
                    continue;
                }
                field.setAccessible(true);
                final String difference;
                try {
                    difference = comparison.differenceOf(
                        field.get(compiled), field.get(fresh), type.getName() + "." + field.getName());
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(what + ": cannot read " + type.getName() + "." + field.getName(), e);
                }
                if (difference != null) {
                    throw new IllegalStateException(what + ": the generator sets state after construction, at "
                        + difference + " against a new instance. The distro builds pre-compiled classes by their "
                        + "no-arg constructor, so the rule would run without it: record and replay it, as TalDSL does "
                        + "for TAL's function objects.");
                }
            }
        }
    }
}
