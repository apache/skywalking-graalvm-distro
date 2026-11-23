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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OalPassTest {

    @Test
    void discoverOALDefinesShouldFindAllExpectedDefines() throws Exception {
        OALDefine[] defines = OalPass.discoverOALDefines();
        Set<String> actualScripts = Arrays.stream(defines)
            .map(OALDefine::getConfigFile)
            .collect(Collectors.toSet());

        // Verify all known OAL scripts are discovered
        assertTrue(actualScripts.contains("oal/core.oal"), "Should discover core.oal");
        assertTrue(actualScripts.contains("oal/disable.oal"), "Should discover disable.oal");
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        assertDoesNotThrow(() -> OalPass.requireEveryScriptCompiled(
            OalPass.effectiveOALDefines(defines, overlay), OalPass.stagedOalScripts()));
    }

    @Test
    void aStagedOalScriptNoDefineNamesFailsTheBuild() {
        final OALDefine[] defines = {new OalPass.OverlayOALDefine("oal/fleet.oal")};
        final IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> OalPass.requireEveryScriptCompiled(defines, List.of("oal/fleet.oal", "oal/orphan.oal")));
        assertTrue(e.getMessage().contains("[oal/orphan.oal]"), e.getMessage());
    }

    @Test
    void anOalDefineWithoutItsOwnStaticInstanceFailsTheBuild() {
        assertSame(Singleton.INSTANCE, OalPass.defineOf(Singleton.class));
        for (final Class<?> define : List.of(NoInstance.class, ForeignInstance.class)) {
            final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OalPass.defineOf(define));
            assertTrue(e.getMessage().contains(define.getName()), e.getMessage());
        }
    }

    // Nested, so the classpath scan for OALDefine subclasses does not find them.

    public static final class Singleton extends OALDefine {
        public static final Singleton INSTANCE = new Singleton();

        private Singleton() {
            super("oal/singleton.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    public static final class NoInstance extends OALDefine {
        public NoInstance() {
            super("oal/no-instance.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    public static final class ForeignInstance extends OALDefine {
        public static final OALDefine INSTANCE = Singleton.INSTANCE;

        private ForeignInstance() {
            super("oal/foreign-instance.oal", "org.apache.skywalking.oap.server.core.source");
        }
    }

    @Test
    void allDiscoveredOALScriptsShouldBeOnClasspath() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        // A script the rules overlay excluded is not compiled, so it is not there.
        final OverlayInventory overlay = OverlayInventory.read(System.getProperty(OverlayInventory.PROPERTY));
        for (OALDefine define : OalPass.effectiveOALDefines(OalPass.discoverOALDefines(), overlay)) {
            assertNotNull(
                cl.getResource(define.getConfigFile()),
                "OAL script not found on classpath: " + define.getConfigFile()
                    + " (" + define.getClass().getSimpleName() + ")"
            );
        }
    }
}
