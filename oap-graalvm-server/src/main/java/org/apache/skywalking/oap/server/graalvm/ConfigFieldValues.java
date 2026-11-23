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

package org.apache.skywalking.oap.server.graalvm;

import java.lang.reflect.Field;

/**
 * The conversions upstream's {@code Field.set} applies to a config value, which the generated config binder
 * ({@code YamlConfigLoaderUtils}) puts every value through before its setter: a primitive field takes its wrapper or a
 * value of a primitive type that widens to it, a reference field an instance of its type or null; anything else is
 * refused with {@code Field.set}'s {@code IllegalArgumentException}. A final or static field, which has no setter, is
 * written through {@code Field.set} itself ({@link #setByField}).
 */
public final class ConfigFieldValues {
    private ConfigFieldValues() {
    }

    public static boolean booleanValue(final Object value, final String field) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw refused("boolean", field, value);
    }

    public static char charValue(final Object value, final String field) {
        if (value instanceof Character) {
            return (Character) value;
        }
        throw refused("char", field, value);
    }

    public static byte byteValue(final Object value, final String field) {
        if (value instanceof Byte) {
            return (Byte) value;
        }
        throw refused("byte", field, value);
    }

    public static short shortValue(final Object value, final String field) {
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).shortValue();
        }
        throw refused("short", field, value);
    }

    public static int intValue(final Object value, final String field) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        if (value instanceof Character) {
            return (Character) value;
        }
        throw refused("int", field, value);
    }

    public static long longValue(final Object value, final String field) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Character) {
            return (Character) value;
        }
        throw refused("long", field, value);
    }

    public static float floatValue(final Object value, final String field) {
        if (value instanceof Float || value instanceof Long || value instanceof Integer || value instanceof Short
            || value instanceof Byte) {
            return ((Number) value).floatValue();
        }
        if (value instanceof Character) {
            return (Character) value;
        }
        throw refused("float", field, value);
    }

    public static double doubleValue(final Object value, final String field) {
        if (value instanceof Double || value instanceof Float || value instanceof Long || value instanceof Integer
            || value instanceof Short || value instanceof Byte) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Character) {
            return (Character) value;
        }
        throw refused("double", field, value);
    }

    public static <T> T referenceValue(final Object value, final Class<T> type, final String field) {
        if (value == null || type.isInstance(value)) {
            return type.cast(value);
        }
        throw refused(type.getName(), field, value);
    }

    /**
     * Writes a field as upstream's binder does, reflectively, for a final or a static field, which no setter writes:
     * {@code Field.set} takes an instance of the field's type or null (a final field's own list given back to it
     * included), refuses anything else with its own {@code IllegalArgumentException}, and a static final field with
     * {@code IllegalAccessException}. The field is looked up from the object's class up, as upstream's
     * {@code getDeclaredField}; the precompiler registers every final instance field of a config class for writing.
     */
    public static void setByField(final Object dest, final String name, final Object value) throws IllegalAccessException {
        for (Class<?> c = dest.getClass(); c != null; c = c.getSuperclass()) {
            final Field field;
            try {
                field = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }
            field.setAccessible(true);
            field.set(dest, value);
            return;
        }
        throw new IllegalStateException(dest.getClass().getName() + " has no field " + name);
    }

    private static IllegalArgumentException refused(final String type, final String field, final Object value) {
        return new IllegalArgumentException("Can not set " + type + " field " + field + " to "
            + (value == null ? "null value" : value.getClass().getName()));
    }
}
