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

package org.apache.skywalking.oap.server.graalvm.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.server.core.rule.ext.RuleSetMerger;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.library.module.ModuleManager;

/**
 * The rule files the image was compiled from, carried verbatim under {@code META-INF/rule-source/} (the precompiler's
 * export), for every catalog whose upstream loader passes the files it reads through {@link RuleSetMerger}: every
 * staged file, one yielding no rule too, as upstream's loader reads and records it before skipping it. The
 * replacement loaders pass the files they load through it too, which records them in upstream's
 * {@link StaticRuleRegistry}, the record of what the boot loaded that upstream's rule catalog reads.
 */
public final class RuleSources {
    public static final String ROOT = "META-INF/rule-source/";
    public static final String INDEX = ROOT + "index.txt";

    private RuleSources() {
    }

    /**
     * A carried rule file: its catalog, its rule name (its path in the catalog without extension) and that path.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Source {
        private final String catalog;
        private final String name;
        private final String path;
    }

    /**
     * @return the carried rule file of a catalog and rule name, or null
     */
    public static Source find(final String catalog, final String name) {
        return Index.SOURCES.get(catalog + ":" + name);
    }

    /**
     * @param activated the rule files the build activated for the catalog, every staged one, as the registry's
     *                  activation lists them (comma-separated names)
     * @return the carried source of each, in the activation's order
     * @throws IllegalStateException when the image carries no source of one, which a loader would otherwise not see
     */
    public static List<Source> carried(final String catalog, final String activated) {
        if (activated == null) {
            throw new IllegalStateException("The rule registry has no activation for the catalog " + catalog);
        }
        final List<Source> sources = new ArrayList<>();
        for (final String name : activated.split(",")) {
            if (name.trim().isEmpty()) {
                continue;
            }
            final Source source = find(catalog, name.trim());
            if (source == null) {
                throw new IllegalStateException("The image carries no source of the compiled rule file " + catalog + "/"
                    + name.trim() + "; rebuild the distro");
            }
            sources.add(source);
        }
        return sources;
    }

    public static byte[] content(final Source source) {
        final String resource = ROOT + source.getCatalog() + "/" + source.getPath();
        try (InputStream in = BuildManifest.open(RuleSources.class.getClassLoader(), resource)) {
            if (in == null) {
                throw new IllegalStateException("The rule source " + resource + " the index lists is not in the image");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the rule source " + resource, e);
        }
    }

    /**
     * Passes the named files of the catalog, as the image carries them, through upstream's {@link RuleSetMerger}, as
     * upstream's loader passes the files it reads, so they are recorded in {@link StaticRuleRegistry}. The image runs
     * the classes compiled from those very bytes, so a {@code RuntimeRuleOverrideResolver} that changes or drops one
     * fails the boot.
     *
     * @param installedManager whether to merge with the module manager core installed, as upstream's loaders do when
     *                         they are given none
     * @param manager          the module manager given, otherwise
     */
    public static void merge(final String catalog, final Collection<String> names, final boolean installedManager,
                             final ModuleManager manager) {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        for (final String name : names) {
            final Source source = find(catalog, name);
            if (source == null) {
                throw new IllegalStateException("The image carries no source of the compiled rule file " + catalog + "/"
                    + name + "; rebuild the distro");
            }
            files.put(name, content(source));
        }
        requireUnchanged(catalog, files, installedManager
            ? RuleSetMerger.merge(catalog, new HashMap<>(files))
            : RuleSetMerger.merge(catalog, new HashMap<>(files), manager));
    }

    static void requireUnchanged(final String catalog, final Map<String, byte[]> files,
                                 final Map<String, byte[]> merged) {
        final boolean unchanged = merged.keySet().equals(files.keySet())
            && files.entrySet().stream().allMatch(file -> Arrays.equals(file.getValue(), merged.get(file.getKey())));
        if (!unchanged) {
            throw new IllegalStateException("A RuntimeRuleOverrideResolver changed or dropped rule files of " + catalog
                + " among " + files.keySet() + "; this image runs the rules compiled into it and cannot apply that");
        }
    }

    private static final class Index {
        private static final Map<String, Source> SOURCES = new HashMap<>();

        static {
            for (final String line : BuildManifest.lines(INDEX)) {
                final String[] parts = line.split("\\|", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("Malformed line in " + INDEX + ": " + line);
                }
                final int dot = parts[1].lastIndexOf('.');
                final String name = dot > parts[1].lastIndexOf('/') ? parts[1].substring(0, dot) : parts[1];
                SOURCES.put(parts[0] + ":" + name, new Source(parts[0], name, parts[1]));
            }
        }
    }
}
