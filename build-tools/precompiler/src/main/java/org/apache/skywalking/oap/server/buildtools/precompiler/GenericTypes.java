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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the classes a generic type stands for, for the HTTP body scan and the LAL value walk.
 */
final class GenericTypes {
    /**
     * The classes a value of the type holds: a class, an array's element class, a parameterized type's raw class and
     * those of its type arguments ({@code List<Foo>}'s {@code Foo}), a wildcard's upper bounds
     * ({@code List<? extends Foo>}), a generic array's element type ({@code List<Foo>[]}), and a type variable's binding
     * in {@code bindings}, or else its bounds ({@code T[]} with {@code T extends Foo}).
     */
    static List<Class<?>> valueTypes(Type type, Map<TypeVariable<?>, Type> bindings) {
        return valueTypes(type, bindings, new HashSet<>());
    }

    private static List<Class<?>> valueTypes(Type type, Map<TypeVariable<?>, Type> bindings,
                                             Set<TypeVariable<?>> resolving) {
        List<Class<?>> types = new ArrayList<>();
        if (type instanceof Class) {
            Class<?> element = (Class<?>) type;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            types.add(element);
        } else if (type instanceof ParameterizedType) {
            types.add((Class<?>) ((ParameterizedType) type).getRawType());
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                types.addAll(valueTypes(argument, bindings, resolving));
            }
        } else if (type instanceof WildcardType) {
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                types.addAll(valueTypes(bound, bindings, resolving));
            }
        } else if (type instanceof GenericArrayType) {
            types.addAll(valueTypes(((GenericArrayType) type).getGenericComponentType(), bindings, resolving));
        } else if (type instanceof TypeVariable && resolving.add((TypeVariable<?>) type)) {
            // A variable bound to itself through its bounds (T extends Comparable<T>) is resolved once
            TypeVariable<?> variable = (TypeVariable<?>) type;
            Type binding = bindings.get(variable);
            for (Type bound : binding != null ? new Type[] {binding} : variable.getBounds()) {
                types.addAll(valueTypes(bound, bindings, resolving));
            }
            resolving.remove(variable);
        }
        return types;
    }

    /**
     * @return the type each type variable of the class's superclasses and interfaces is given in its hierarchy
     *     ({@code Box<T>}'s {@code T} in {@code FilledBox extends Box<Bound>}: {@code Bound})
     */
    static Map<TypeVariable<?>, Type> typeBindings(Class<?> type) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            List<Type> supertypes = new ArrayList<>(Arrays.asList(c.getGenericInterfaces()));
            if (c.getGenericSuperclass() != null) {
                supertypes.add(c.getGenericSuperclass());
            }
            for (Type supertype : supertypes) {
                Class<?> raw = supertype instanceof ParameterizedType
                    ? (Class<?>) ((ParameterizedType) supertype).getRawType() : (Class<?>) supertype;
                if (supertype instanceof ParameterizedType) {
                    TypeVariable<?>[] parameters = raw.getTypeParameters();
                    Type[] arguments = ((ParameterizedType) supertype).getActualTypeArguments();
                    for (int i = 0; i < parameters.length; i++) {
                        bindings.put(parameters[i], arguments[i]);
                    }
                }
                queue.add(raw);
            }
        }
        return bindings;
    }
}
