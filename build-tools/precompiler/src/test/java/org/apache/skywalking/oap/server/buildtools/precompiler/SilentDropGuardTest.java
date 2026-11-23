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

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.skywalking.oap.server.buildtools.common.ProtobufMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build fails where the native image would otherwise run a rule without part of what upstream gave it: state a
 * generator sets on a compiled instance after constructing it, and rule data the registry cannot write.
 */
class SilentDropGuardTest {

    public static class Generated {
        Object wiredLater;
        Object builtInConstructor = new Object();
        int constant = 7;
        List<String> list = new ArrayList<>();
        StringBuilder text = new StringBuilder("constant");
    }

    @Test
    void stateSetAfterConstructionFailsTheBuild() {
        final Generated compiled = new Generated();
        assertDoesNotThrow(() -> GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled, Set.of(), "rule"));

        compiled.wiredLater = new Object();
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled, Set.of(), "rule"));
        assertTrue(e.getMessage().contains("wiredLater"), e.getMessage());
        assertDoesNotThrow(
            () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled, Set.of("wiredLater"), "rule"),
            "a field the runtime replays");

        final Generated changed = new Generated();
        changed.constant = 8;
        assertThrows(IllegalStateException.class,
            () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(changed, Set.of(), "rule"));

        final Generated filled = new Generated();
        filled.list.add("drop");
        final IllegalStateException inside = assertThrows(IllegalStateException.class,
            () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(filled, Set.of(), "rule"),
            "a change inside an object the constructor built");
        assertTrue(inside.getMessage().contains("list"), inside.getMessage());
    }

    public static class Shared {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        ReentrantLock lock = new ReentrantLock();
    }

    public static final class Key {
        final String name;
        int line;

        Key(final String name, final int line) {
            this.name = name;
            this.line = line;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key && ((Key) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    @Test
    void stateEqualsDoesNotSeeIsCompared() {
        final Shared aliased = new Shared();
        aliased.second = aliased.first;
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(aliased, Set.of(), "rule"),
            "two fields sharing one list, where a new instance has two");
        assertTrue(e.getMessage().contains("second"), e.getMessage());

        final Shared locked = new Shared();
        locked.lock.lock();
        try {
            assertThrows(IllegalStateException.class,
                () -> GeneratedInstanceCheck.requireNoStateAfterConstruction(locked, Set.of(), "rule"));
        } finally {
            locked.lock.unlock();
        }
        assertDoesNotThrow(() -> GeneratedInstanceCheck.requireNoStateAfterConstruction(new Shared(), Set.of(), "rule"));

        // a key equal by equals, yet another line
        final Map<Key, String> a = new HashMap<>(Map.of(new Key("k", 1), "v"));
        final Map<Key, String> b = new HashMap<>(Map.of(new Key("k", 8), "v"));
        assertNotNull(StateComparison.firstDifference(a, b, "map"));
        assertNull(StateComparison.firstDifference(a, new HashMap<>(Map.of(new Key("k", 1), "v")), "map"));
    }

    public static class Inherited {
        private Boolean enabled;

        public boolean getEnabled() {
            return enabled != null && enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }
    }

    @Test
    void anExplicitValueTheGetterReadsLikeTheDefaultIsCarried() throws Exception {
        final Inherited bean = new Inherited();
        bean.setEnabled(false);
        assertNotNull(StateComparison.firstDifference(bean, new Inherited(), "bean"), "null and false differ");
        final Inherited rebuilt = new Inherited();
        rebuilt.setEnabled(bean.getEnabled());
        assertNull(StateComparison.firstDifference(bean, rebuilt, "bean"), "the setter rebuilds it");
    }

    @Test
    void onlyAGeneratedMessageGetsADescriptorClosure() {
        assertEquals(Struct.class, ProtobufMessages.generatedMessage(Struct.class));
        assertEquals(Struct.class,
            ProtobufMessages.generatedMessage(Struct.Builder.class));
        assertNull(ProtobufMessages.generatedMessage(DynamicMessage.class));
        assertNull(ProtobufMessages.generatedMessage(DynamicMessage.Builder.class));
        assertNull(ProtobufMessages.generatedMessage(String.class));
    }

    public static class RuleData {
        // A property needs no field of its name.
        private String stored;
        private String noAccessors;

        public String getName() {
            return stored;
        }

        public void setName(final String name) {
            this.stored = name;
        }
    }

    @Test
    void ruleDataTheRegistryCannotWriteFailsTheBuild() throws Exception {
        final List<PropertyDescriptor> carried = new ArrayList<>();
        for (final PropertyDescriptor property
            : Introspector.getBeanInfo(RuleData.class, Object.class).getPropertyDescriptors()) {
            carried.add(property);
        }
        final RuleData data = new RuleData();
        data.setName("a");
        assertDoesNotThrow(() -> RegistryGenerator.requireCarried(data, carried));

        data.noAccessors = "lost";
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> RegistryGenerator.requireCarried(data, carried));
        assertTrue(e.getMessage().contains("noAccessors"), e.getMessage());
    }
}
