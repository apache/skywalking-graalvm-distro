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
package org.apache.skywalking.oap.server.buildtools.config;

import java.lang.reflect.Field;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which fields the generated loader sets through a setter: {@code set<Name>(<field type>)}, not static, on the field's
 * own class or a subclass of it. Every other field goes through {@code Field.set}, as upstream writes every field.
 */
class SetterRuleTest {

    @Test
    void aSetterOfTheFieldsTypeIsCalled() throws Exception {
        assertTrue(ConfigInitializerGenerator.hasSetter(Shapes.class, field(Shapes.class, "withSetter")));
    }

    @Test
    void aFieldWithoutAMatchingSetterGoesThroughFieldSet() throws Exception {
        assertFalse(ConfigInitializerGenerator.hasSetter(Shapes.class, field(Shapes.class, "withoutSetter")));
        // A boxed setter for a primitive field is not the one the loader would call
        assertFalse(ConfigInitializerGenerator.hasSetter(Shapes.class, field(Shapes.class, "boxedSetter")));
        assertFalse(ConfigInitializerGenerator.hasSetter(Shapes.class, field(Shapes.class, "staticSetter")));
        // A boolean field named isX: its setter is setX, not the setIsX the loader would call
        assertFalse(ConfigInitializerGenerator.hasSetter(Shapes.class, field(Shapes.class, "isEnabled")));
    }

    @Test
    void anInheritedFieldTakesTheSetterItsClassOrASubclassDeclares() throws Exception {
        assertTrue(ConfigInitializerGenerator.hasSetter(Child.class, field(Base.class, "inherited")));
        assertTrue(ConfigInitializerGenerator.hasSetter(Child.class, field(Base.class, "setInSubclass")));
    }

    @Test
    void aFieldASubclassRedeclaresIsNotWrittenByTheSuperclassSetter() throws Exception {
        assertFalse(ConfigInitializerGenerator.hasSetter(Child.class, field(Child.class, "shadowed")));
        assertTrue(ConfigInitializerGenerator.analyzeConfigClass(Child.class).fields().stream()
            .filter(f -> f.name().equals("shadowed")).findFirst().orElseThrow().isSetByField());
    }

    private static Field field(final Class<?> type, final String name) throws NoSuchFieldException {
        return type.getDeclaredField(name);
    }

    @SuppressWarnings("unused")
    static class Shapes extends ModuleConfig {
        private String withSetter;
        private String withoutSetter;
        private int boxedSetter;
        private int staticSetter;
        private boolean isEnabled;

        public void setWithSetter(final String withSetter) {
            this.withSetter = withSetter;
        }

        public void setBoxedSetter(final Integer boxedSetter) {
            this.boxedSetter = boxedSetter;
        }

        public static void setStaticSetter(final int staticSetter) {
        }

        public void setEnabled(final boolean enabled) {
            this.isEnabled = enabled;
        }
    }

    @SuppressWarnings("unused")
    static class Base extends ModuleConfig {
        private int inherited;
        private int setInSubclass;
        private int shadowed;

        public void setInherited(final int inherited) {
            this.inherited = inherited;
        }

        public void setShadowed(final int shadowed) {
            this.shadowed = shadowed;
        }
    }

    @SuppressWarnings("unused")
    static class Child extends Base {
        private int shadowed;

        public void setSetInSubclass(final int value) {
        }
    }
}
