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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Whether two objects hold the same state, for the build-time guards that require what the runtime rebuilds (a
 * pre-compiled class by its no-arg constructor, rule data by its setters or from JSON) to equal what upstream
 * produced. It fails closed: values, text, arrays and collections compare by content, objects of this project field
 * by field, a JDK value by its equals, and anything it cannot compare (a JDK object without equals that is not known
 * to be stateless, a structure deeper than it walks) is a difference, so a guard never passes on what it did not see.
 */
final class StateComparison {
    private static final int MAX_DEPTH = 64;

    /**
     * Fields whose value is a representation the getter resolves: compared through the getter, as every reader
     * sees them. Rule#getSourcePath falls back to the name when the loader stamped no path.
     */
    private static final Map<String, Set<String>> COMPARED_VIA_GETTER = Map.of(
        "org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule", Set.of("sourcePath"));

    /** JDK objects without equals that hold no state. */
    private static final Set<Class<?>> STATELESS = Set.of(Object.class);

    /**
     * The objects paired so far, both ways: an object of one side pairs with one object of the other, so two fields
     * sharing a mutable object match only two fields sharing one, and a pair already being compared is a cycle.
     */
    private IdentityHashMap<Object, Object> forward = new IdentityHashMap<>();
    private IdentityHashMap<Object, Object> reverse = new IdentityHashMap<>();

    private StateComparison() {
    }

    /**
     * @return where the two first differ, as a path from {@code path}, or null when they hold the same state
     */
    static String firstDifference(final Object a, final Object b, final String path) {
        return new StateComparison().difference(a, b, path, 0);
    }

    /**
     * A comparison whose pairing spans several {@link #differenceOf} calls, for the fields of one object.
     */
    static StateComparison context() {
        return new StateComparison();
    }

    String differenceOf(final Object a, final Object b, final String path) {
        return difference(a, b, path, 0);
    }

    private String difference(final Object a, final Object b, final String path, final int depth) {
        if (a == b) {
            return null;
        }
        if (a == null || b == null) {
            return path + " (" + describe(a) + " vs " + describe(b) + ")";
        }
        final String value = valueDifference(a, b, path);
        if (value != null) {
            return value.isEmpty() ? null : value;
        }
        if (depth > MAX_DEPTH) {
            return path + " (deeper than " + MAX_DEPTH + " levels, not compared)";
        }
        if (!isImmutable(a) || !isImmutable(b)) {
            final Object paired = forward.get(a);
            if (paired != null || reverse.containsKey(b)) {
                // Paired before: matched or under comparison further up (a cycle) when it is this pair.
                return paired == b ? null : path + " (shares an object with another field the other side does not)";
            }
            forward.put(a, b);
            reverse.put(b, a);
        }
        if (a instanceof Set && b instanceof Set) {
            return setDifference((Set<?>) a, (Set<?>) b, path, depth);
        }
        if (a instanceof Collection && b instanceof Collection && !(a instanceof Set) && !(b instanceof Set)) {
            return sequenceDifference((Collection<?>) a, (Collection<?>) b, path, depth);
        }
        if (a instanceof Map && b instanceof Map) {
            return mapDifference((Map<?, ?>) a, (Map<?, ?>) b, path, depth);
        }
        if (a.getClass() != b.getClass()) {
            return path + " (" + describe(a) + " vs " + describe(b) + ")";
        }
        if (a instanceof Optional) {
            return difference(((Optional<?>) a).orElse(null), ((Optional<?>) b).orElse(null), path, depth + 1);
        }
        if (a instanceof AtomicReference) {
            return difference(((AtomicReference<?>) a).get(), ((AtomicReference<?>) b).get(), path, depth + 1);
        }
        if (a.getClass().isArray()) {
            return arrayDifference(a, b, path, depth);
        }
        if (a instanceof ReentrantLock) {
            final ReentrantLock left = (ReentrantLock) a;
            final ReentrantLock right = (ReentrantLock) b;
            return left.isFair() == right.isFair() && left.isLocked() == right.isLocked()
                && left.hasQueuedThreads() == right.hasQueuedThreads()
                ? null : path + " (lock state " + left + " vs " + right + ")";
        }
        if (a instanceof ReentrantReadWriteLock) {
            final ReentrantReadWriteLock left = (ReentrantReadWriteLock) a;
            final ReentrantReadWriteLock right = (ReentrantReadWriteLock) b;
            return left.isFair() == right.isFair() && left.getReadLockCount() == right.getReadLockCount()
                && left.isWriteLocked() == right.isWriteLocked() && left.hasQueuedThreads() == right.hasQueuedThreads()
                ? null : path + " (lock state " + left + " vs " + right + ")";
        }
        if (isJdk(a.getClass())) {
            if (overridesEquals(a.getClass())) {
                return a.equals(b) ? null : path + " (" + a + " vs " + b + ")";
            }
            return STATELESS.contains(a.getClass())
                ? null : path + " (a " + a.getClass().getName() + ", which this comparison cannot read)";
        }
        return memberDifference(a, b, path, depth);
    }

    /**
     * Shared freely without effect: no aliasing to track.
     */
    private static boolean isImmutable(final Object value) {
        final String name = value.getClass().getName();
        return name.startsWith("java.util.ImmutableCollections$") || name.startsWith("java.util.Collections$Empty")
            || name.startsWith("java.util.Collections$Unmodifiable") || name.startsWith("java.util.Collections$Singleton")
            || name.startsWith("com.google.common.collect.Immutable") || name.startsWith("com.google.common.collect.Regular")
            || name.startsWith("com.google.common.collect.Singleton") || value instanceof Optional;
    }

    /**
     * Runs a tentative match: its pairings are kept only when it matches, so a failed candidate leaves none.
     */
    private String trial(final Object a, final Object b, final String path, final int depth) {
        final IdentityHashMap<Object, Object> savedForward = new IdentityHashMap<>(forward);
        final IdentityHashMap<Object, Object> savedReverse = new IdentityHashMap<>(reverse);
        final String d = difference(a, b, path, depth);
        if (d != null) {
            forward = savedForward;
            reverse = savedReverse;
        }
        return d;
    }

    /**
     * @return null when not a value, "" when equal values, otherwise the difference
     */
    private static String valueDifference(final Object a, final Object b, final String path) {
        if (a instanceof AtomicBoolean || a instanceof AtomicInteger || a instanceof AtomicLong) {
            return a.getClass() == b.getClass() && a.toString().equals(b.toString())
                ? "" : path + " (" + a + " vs " + b + ")";
        }
        if (a instanceof Number || a instanceof Boolean || a instanceof Character || a instanceof Enum
            || a instanceof Class || a instanceof String) {
            return a.equals(b) ? "" : path + " (" + a + " vs " + b + ")";
        }
        if (a instanceof CharSequence && b instanceof CharSequence) {
            return a.toString().equals(b.toString()) ? "" : path + " (\"" + a + "\" vs \"" + b + "\")";
        }
        if (a instanceof Pattern && b instanceof Pattern) {
            final Pattern left = (Pattern) a;
            final Pattern right = (Pattern) b;
            return left.pattern().equals(right.pattern()) && left.flags() == right.flags()
                ? "" : path + " (/" + left + "/ vs /" + right + "/)";
        }
        return null;
    }

    private String memberDifference(final Object a, final Object b, final String path, final int depth) {
        try {
            for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (isJdk(c)) {
                    return STATELESS.contains(c) ? null
                        : path + " (extends " + c.getName() + ", which this comparison cannot read)";
                }
                final Set<String> viaGetter = COMPARED_VIA_GETTER.getOrDefault(c.getName(), Set.of());
                for (final Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    final Object left;
                    final Object right;
                    if (viaGetter.contains(field.getName())) {
                        final Method getter = c.getMethod(
                            "get" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1));
                        left = getter.invoke(a);
                        right = getter.invoke(b);
                    } else {
                        field.setAccessible(true);
                        left = field.get(a);
                        right = field.get(b);
                    }
                    final String d = difference(left, right, path + "." + field.getName(), depth + 1);
                    if (d != null) {
                        return d;
                    }
                }
            }
            return null;
        } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    private String arrayDifference(final Object a, final Object b, final String path, final int depth) {
        if (Array.getLength(a) != Array.getLength(b)) {
            return path + " (length " + Array.getLength(a) + " vs " + Array.getLength(b) + ")";
        }
        for (int i = 0; i < Array.getLength(a); i++) {
            final String d = difference(Array.get(a, i), Array.get(b, i), path + "[" + i + "]", depth + 1);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private String sequenceDifference(final Collection<?> a, final Collection<?> b, final String path,
                                      final int depth) {
        if (a.size() != b.size()) {
            return path + " (size " + a.size() + " vs " + b.size() + ")";
        }
        final Iterator<?> left = a.iterator();
        final Iterator<?> right = b.iterator();
        for (int i = 0; left.hasNext(); i++) {
            final String d = difference(left.next(), right.next(), path + "[" + i + "]", depth + 1);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /**
     * Elements match by state, not equals, which may leave out a field (MetricsRule's line).
     */
    private String setDifference(final Set<?> a, final Set<?> b, final String path, final int depth) {
        if (a.size() != b.size()) {
            return path + " (size " + a.size() + " vs " + b.size() + ")";
        }
        final List<Object> unmatched = new ArrayList<>(b);
        for (final Object element : a) {
            boolean matched = false;
            for (final Iterator<Object> it = unmatched.iterator(); it.hasNext(); ) {
                if (trial(element, it.next(), path + "{}", depth + 1) == null) {
                    it.remove();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return path + " (" + element + " has no counterpart)";
            }
        }
        return null;
    }

    /**
     * Entries match one to one by the state of both key and value, not by the key's equals, which may leave out a
     * field (MetricsRule's line).
     */
    private String mapDifference(final Map<?, ?> a, final Map<?, ?> b, final String path, final int depth) {
        if (a.size() != b.size()) {
            return path + " (size " + a.size() + " vs " + b.size() + ")";
        }
        final List<Map.Entry<?, ?>> unmatched = new ArrayList<>(b.entrySet());
        for (final Map.Entry<?, ?> entry : a.entrySet()) {
            boolean matched = false;
            for (final Iterator<Map.Entry<?, ?>> it = unmatched.iterator(); it.hasNext(); ) {
                final Map.Entry<?, ?> candidate = it.next();
                final IdentityHashMap<Object, Object> savedForward = new IdentityHashMap<>(forward);
                final IdentityHashMap<Object, Object> savedReverse = new IdentityHashMap<>(reverse);
                if (difference(entry.getKey(), candidate.getKey(), path + "{key}", depth + 1) == null
                    && difference(entry.getValue(), candidate.getValue(), path + "[" + entry.getKey() + "]",
                    depth + 1) == null) {
                    it.remove();
                    matched = true;
                    break;
                }
                forward = savedForward;
                reverse = savedReverse;
            }
            if (!matched) {
                return path + " (entry " + entry.getKey() + " has no counterpart)";
            }
        }
        return null;
    }

    private static boolean overridesEquals(final Class<?> type) {
        try {
            return type.getMethod("equals", Object.class).getDeclaringClass() != Object.class;
        } catch (final NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isJdk(final Class<?> type) {
        final String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
            || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    private static String describe(final Object value) {
        return value == null ? "null" : "a " + value.getClass().getName();
    }
}
