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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The rule files the build's rules overlay added or overrode, from the inventory the precompiler's {@code RulesStager}
 * wrote ({@code action<TAB>path<TAB>sha256}); none without an overlay.
 */
public final class OverlayFiles {
    private OverlayFiles() {
    }

    /**
     * @param directory a rule directory, {@code otel-rules} for one
     * @return the added and overridden files under it, by their path from the rule tree root
     */
    public static List<String> under(final String directory) throws IOException {
        final String inventory = System.getProperty("rules.overlay.inventory");
        if (inventory == null) {
            return List.of();
        }
        // The stager writes it on every build, empty without an overlay: a missing one is a broken build.
        if (!Files.isRegularFile(Path.of(inventory))) {
            throw new IllegalStateException("No overlay inventory at " + inventory);
        }
        return Files.readAllLines(Path.of(inventory), StandardCharsets.UTF_8).stream()
                    .map(line -> line.split("\t"))
                    .filter(parts -> parts.length >= 2 && !"exclude".equals(parts[0])
                        && parts[1].startsWith(directory + "/"))
                    .map(parts -> parts[1])
                    .collect(Collectors.toList());
    }
}
