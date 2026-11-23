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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayInventoryTest {

    @Test
    void readsTheFilesTheStagerRecorded(@TempDir final Path dir) throws Exception {
        final Path file = write(dir, "add\toal/fleet.oal\tab12", "override\tlal/default.yaml\tcd34",
            "exclude\toal/tcp.oal\tef56");
        final OverlayInventory inventory = OverlayInventory.read(file.toString());
        assertEquals(Set.of("oal/fleet.oal"), inventory.added());
        assertEquals(Set.of("lal/default.yaml"), inventory.overridden());
        assertEquals(Set.of("oal/tcp.oal"), inventory.excluded());
    }

    @Test
    void noInventoryNamedFailsTheBuild() {
        assertThrows(IllegalStateException.class, () -> OverlayInventory.read(null));
        assertThrows(IllegalStateException.class, () -> OverlayInventory.read(" "));
    }

    @Test
    void aMalformedLineFailsTheBuild(@TempDir final Path dir) throws Exception {
        for (final String line : List.of("exclude\toal/tcp.oal", "", "add\toal/a.oal\tab\tcd")) {
            final Path file = write(dir, "add\toal/fleet.oal\tab12", line);
            final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OverlayInventory.read(file.toString()));
            assertTrue(e.getMessage().startsWith("Malformed line"), e.getMessage());
        }
    }

    private static Path write(final Path dir, final String... lines) throws Exception {
        final Path file = dir.resolve(RulesStager.INVENTORY);
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }
}
