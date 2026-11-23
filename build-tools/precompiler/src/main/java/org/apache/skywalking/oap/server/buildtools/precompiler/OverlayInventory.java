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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule files the rules overlay added, overrode and excluded, as {@link RulesStager} recorded them
 * ({@code action<TAB>path<TAB>sha256} per line). Empty without an overlay.
 */
final class OverlayInventory {
    /** The system property naming the inventory file. */
    static final String PROPERTY = "rules.overlay.inventory";

    private final Set<String> added = new TreeSet<>();
    private final Set<String> overridden = new TreeSet<>();
    private final Set<String> excluded = new TreeSet<>();

    static OverlayInventory read(final String file) throws IOException {
        // The stager writes it on every build, empty without an overlay: a missing one would drop the overlay's OAL.
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("The system property " + PROPERTY + " names no overlay inventory");
        }
        if (!Files.isRegularFile(Path.of(file))) {
            throw new IllegalStateException("No overlay inventory at " + file + "; the rules were not staged");
        }
        final OverlayInventory inventory = new OverlayInventory();
        for (final String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            final String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("Malformed line in the overlay inventory " + file + ": " + line);
            }
            switch (parts[0]) {
                case "add":
                    inventory.added.add(parts[1]);
                    break;
                case "override":
                    inventory.overridden.add(parts[1]);
                    break;
                case "exclude":
                    inventory.excluded.add(parts[1]);
                    break;
                default:
                    throw new IllegalStateException("Unknown action in the overlay inventory " + file + ": " + line);
            }
        }
        return inventory;
    }

    Set<String> added() {
        return added;
    }

    Set<String> overridden() {
        return overridden;
    }

    Set<String> excluded() {
        return excluded;
    }
}
