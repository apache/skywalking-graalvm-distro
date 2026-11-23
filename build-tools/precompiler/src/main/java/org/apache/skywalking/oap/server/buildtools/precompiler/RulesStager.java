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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Stages the rule tree every engine compiles: a copy of upstream's {@code server-starter} resources with the rules
 * overlay merged in file by file, as its {@code rules-overlay.yaml} declares: {@code add} (files upstream does not
 * have), {@code override} (an upstream file replaced whole) and {@code exclude} (upstream files left out, unless the
 * overlay provides them). A second classpath root could not do it: rule directories are found with
 * {@code getResource}, which returns the first match only.
 *
 * <p>Writes, next to the staged tree, {@code overlay-inventory.txt} ({@code action<TAB>path<TAB>sha256} per overlay
 * or excluded file) and {@code compiled-rules/}: copies of every rule file the build compiles, the overlay's manifest and
 * {@code BUILD-INFO}, the record the distributions ship.
 */
@Slf4j
public final class RulesStager {
    static final String MANIFEST = "rules-overlay.yaml";
    static final String INVENTORY = "overlay-inventory.txt";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_NAME = 64;
    private static final int MAX_PATH = 128;

    private final Path upstream;
    private final Path overlay;
    private final Path staging;
    private final Path state;

    RulesStager(final Path upstream, final Path overlay, final Path staging, final Path state) {
        this.upstream = upstream;
        this.overlay = overlay;
        this.staging = staging;
        this.state = state;
    }

    /**
     * @param args upstream resources, the staged tree, the state directory, and the overlay directory (absent, blank
     *             or null, as exec-maven-plugin passes an empty argument, for none)
     */
    public static void main(final String[] args) throws Exception {
        // The real path: a symbolic link given as the overlay would not be walked.
        final Path overlay = args.length > 3 && args[3] != null && !args[3].isBlank()
            ? realPath(Path.of(args[3])) : null;
        new RulesStager(Path.of(args[0]), overlay, Path.of(args[1]), Path.of(args[2])).stage();
    }

    private static Path realPath(final Path overlay) throws IOException {
        if (!Files.isDirectory(overlay)) {
            throw new IllegalStateException("The rules overlay " + overlay.toAbsolutePath() + " is not a directory");
        }
        return overlay.toRealPath();
    }

    void stage() throws Exception {
        deleteRecursively(staging);
        deleteRecursively(state);
        Files.createDirectories(state);
        copyTree(upstream, staging);

        final List<String> inventory = new ArrayList<>();
        String digest = "none";
        if (overlay != null) {
            final Plan plan = plan();
            // Excluded first: an added file may differ from an excluded one by case only.
            for (final String excluded : plan.excluded) {
                inventory.add("exclude\t" + excluded + "\t" + sha256(staging.resolve(excluded)));
                Files.delete(staging.resolve(excluded));
            }
            for (final Map.Entry<String, String> file : plan.files.entrySet()) {
                final Path target = staging.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                if ("override".equals(file.getValue())) {
                    Files.copy(overlay.resolve(file.getKey()), target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(overlay.resolve(file.getKey()), target);
                }
                inventory.add(file.getValue() + "\t" + file.getKey() + "\t" + sha256(overlay.resolve(file.getKey())));
            }
            digest = digest(plan);
            log.info("Rules overlay {}: {} added, {} overridden, {} excluded, {} non-rule files ignored {}", overlay,
                plan.files.values().stream().filter("add"::equals).count(),
                plan.files.values().stream().filter("override"::equals).count(), plan.excluded.size(),
                plan.ignored.size(), plan.ignored);
        }
        Files.write(state.resolve(INVENTORY), inventory, StandardCharsets.UTF_8);
        writeCompiledRules(digest);
    }

    /**
     * What the overlay changes, validated against its manifest and upstream.
     */
    Plan plan() throws Exception {
        final Path manifestFile = overlay.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) {
            throw new IllegalStateException("The rules overlay " + overlay + " has no " + MANIFEST);
        }
        final Map<String, Object> manifest;
        // A second section of one name would silently replace the first.
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            final Object loaded = new Yaml(options).load(reader);
            manifest = loaded == null ? Map.of() : asMap(loaded);
        } catch (YAMLException e) {
            throw new IllegalStateException(MANIFEST + " of " + overlay + " is not valid: " + e.getMessage(), e);
        }
        for (final String key : manifest.keySet()) {
            if (!List.of("add", "override", "exclude").contains(key)) {
                throw new IllegalStateException(MANIFEST + " has an unknown section " + key
                    + "; the sections are add, override and exclude");
            }
        }
        final Map<String, PathMatcher> add = matchers(manifest, "add");
        final Map<String, PathMatcher> override = matchers(manifest, "override");
        final Map<String, PathMatcher> exclude = matchers(manifest, "exclude");

        final Plan plan = new Plan();
        final Set<String> overlayRules = new TreeSet<>();
        // Linked directories are followed (a downstream may link shared rules in); a link cycle fails the walk. Hidden
        // directories are skipped before they are entered.
        Files.walkFileTree(overlay, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    return !dir.equals(overlay) && dir.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    final String rel = relative(overlay, file);
                    if (!attrs.isRegularFile() || rel.equals(MANIFEST) || isHidden(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isRulePath(rel)) {
                        overlayRules.add(rel);
                    } else {
                        plan.ignored.add(rel);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
                    // A link cycle is reported before preVisitDirectory sees the hidden directory.
                    if (isHidden(relative(overlay, file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    throw exc;
                }
            });
        final Set<String> upstreamRules = rulePaths(upstream);
        final Set<String> usedAdd = new TreeSet<>();
        final Set<String> usedOverride = new TreeSet<>();
        for (final String rule : overlayRules) {
            checkFlat(rule);
            final List<String> adds = matching(add, rule);
            final List<String> overrides = matching(override, rule);
            if (adds.isEmpty() && overrides.isEmpty()) {
                throw new IllegalStateException("The overlay rule file " + rule + " is not declared in " + MANIFEST
                    + "; declare it under add or override");
            }
            if (!adds.isEmpty() && !overrides.isEmpty()) {
                throw new IllegalStateException("The overlay rule file " + rule + " is declared both as add "
                    + adds + " and as override " + overrides);
            }
            usedAdd.addAll(adds);
            usedOverride.addAll(overrides);
            final boolean bundled = upstreamRules.contains(rule);
            if (!adds.isEmpty() && bundled) {
                throw new IllegalStateException("The overlay adds " + rule + ", which upstream has; declare it as "
                    + "override");
            }
            if (!overrides.isEmpty() && !bundled) {
                throw new IllegalStateException("The overlay overrides " + rule + ", which upstream does not have; "
                    + "declare it as add");
            }
            plan.files.put(rule, adds.isEmpty() ? "override" : "add");
        }
        unused("add", add.keySet(), usedAdd);
        unused("override", override.keySet(), usedOverride);
        for (final Map.Entry<String, PathMatcher> entry : exclude.entrySet()) {
            final List<String> excluded = upstreamRules.stream()
                .filter(rule -> entry.getValue().matches(Path.of(rule)) && !overlayRules.contains(rule))
                .collect(Collectors.toList());
            if (excluded.isEmpty()) {
                throw new IllegalStateException("The exclude entry " + entry.getKey() + " of " + MANIFEST
                    + " matches no bundled rule file the overlay does not provide");
            }
            plan.excluded.addAll(excluded);
        }
        if (plan.excluded.contains(RuleCatalog.HIERARCHY_DEFINITION)) {
            throw new IllegalStateException(RuleCatalog.HIERARCHY_DEFINITION + " cannot be excluded; override it with "
                + "`hierarchy: {}`, `auto-matching-rules: {}` and `layer-levels: {}` to turn the hierarchy off");
        }
        final Set<String> effective = new TreeSet<>(upstreamRules);
        effective.removeAll(plan.excluded);
        effective.addAll(overlayRules);
        checkDirectoryCase(upstreamRules, overlayRules);
        checkStems(effective);
        plan.manifest = Files.readAllBytes(manifestFile);
        return plan;
    }

    /**
     * Two directories differing by case only are one on a case-insensitive file system, which would stage an overlay
     * file under the bundled spelling.
     */
    private static void checkDirectoryCase(final Set<String> upstreamRules, final Set<String> overlayRules) {
        final Map<String, String> spelling = new TreeMap<>();
        for (final String rule : Stream.concat(upstreamRules.stream(), overlayRules.stream()).toList()) {
            for (int slash = rule.indexOf('/'); slash > 0; slash = rule.indexOf('/', slash + 1)) {
                final String directory = rule.substring(0, slash);
                final String previous = spelling.putIfAbsent(directory.toLowerCase(Locale.ROOT), directory);
                if (previous != null && !previous.equals(directory)) {
                    throw new IllegalStateException("The rule directories " + previous + " and " + directory
                        + " differ by case only; use the bundled spelling");
                }
            }
        }
    }

    /**
     * Two compiled rule files whose names differ by extension or case only would be one rule (and one generated file
     * on a case-insensitive file system). An overlay file may replace an excluded one of another case.
     */
    private static void checkStems(final Set<String> effective) {
        final Map<String, String> byStem = new TreeMap<>();
        for (final String rule : effective) {
            if (RuleCatalog.RULE_FILES.contains(rule)) {
                continue;
            }
            final String stem = rule.contains(".") ? rule.substring(0, rule.lastIndexOf('.')) : rule;
            final String previous = byStem.put(stem.toLowerCase(Locale.ROOT), rule);
            if (previous != null) {
                throw new IllegalStateException("The rule files " + previous + " and " + rule
                    + " differ by extension or case only and would be one rule");
            }
        }
    }

    private static void checkFlat(final String rule) {
        // The image carries a rule file's name into settings lists, generated class files and rule-browser URLs: keep
        // it to names every one of them takes as is. The MAL loaders also strip a listed name's .yaml or .yml.
        final String stem = rule.substring(0, rule.lastIndexOf('.'));
        final Optional<RuleCatalog> catalog =
            RuleCatalog.ofDirectory(rule.contains("/") ? rule.substring(0, rule.indexOf('/')) : "");
        final boolean mal = catalog.map(c -> c.getEngine() == RuleCatalog.Engine.MAL).orElse(false);
        final boolean unsafe = rule.length() > MAX_PATH || Stream.of(rule.split("/"))
                                     .anyMatch(part -> part.length() > MAX_NAME || !SAFE_NAME.matcher(part).matches());
        if (unsafe || mal && (stem.endsWith(".yaml") || stem.endsWith(".yml"))) {
            throw new IllegalStateException("The overlay rule file " + rule + " has a name this distro cannot carry: "
                + "use ASCII letters, digits, '.', '_' and '-', at most " + MAX_NAME + " characters a path segment "
                + "and " + MAX_PATH + " the path, and in a MAL directory one extension");
        }
        if (catalog.isPresent() && !catalog.get().isRecursive() && rule.indexOf('/') != rule.lastIndexOf('/')) {
            throw new IllegalStateException("The overlay rule file " + rule + " is nested in "
                + catalog.get().getDirectory() + "/, whose rules are read from the directory itself only");
        }
    }

    private static void unused(final String section, final Set<String> declared, final Set<String> used) {
        for (final String entry : declared) {
            if (!used.contains(entry)) {
                throw new IllegalStateException("The " + section + " entry " + entry + " of " + MANIFEST
                    + " matches no overlay rule file");
            }
        }
    }

    private static List<String> matching(final Map<String, PathMatcher> matchers, final String rule) {
        return matchers.entrySet().stream().filter(e -> e.getValue().matches(Path.of(rule))).map(Map.Entry::getKey)
                       .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object loaded) {
        if (!(loaded instanceof Map)) {
            throw new IllegalStateException(MANIFEST + " is not a map of add, override and exclude");
        }
        return (Map<String, Object>) loaded;
    }

    private static Map<String, PathMatcher> matchers(final Map<String, Object> manifest, final String section) {
        final Object entries = manifest.get(section);
        final Map<String, PathMatcher> matchers = new LinkedHashMap<>();
        if (entries == null) {
            return matchers;
        }
        if (!(entries instanceof List)) {
            throw new IllegalStateException("The " + section + " section of " + MANIFEST + " is not a list");
        }
        for (final Object entry : (List<?>) entries) {
            if (!(entry instanceof String) || ((String) entry).isBlank()) {
                throw new IllegalStateException("The " + section + " section of " + MANIFEST
                    + " holds a non-path entry " + entry);
            }
            if (matchers.put((String) entry, FileSystems.getDefault().getPathMatcher("glob:" + entry)) != null) {
                throw new IllegalStateException("The " + section + " section of " + MANIFEST + " lists " + entry
                    + " twice");
            }
        }
        return matchers;
    }

    static boolean isRulePath(final String rel) {
        if (RuleCatalog.RULE_FILES.contains(rel)) {
            return true;
        }
        final int slash = rel.indexOf('/');
        final Optional<RuleCatalog> catalog =
            slash < 0 ? Optional.empty() : RuleCatalog.ofDirectory(rel.substring(0, slash));
        if (catalog.isEmpty()) {
            return false;
        }
        // Rule files are YAML, as upstream documents them (OAL scripts .oal): another file there is not a rule file.
        return catalog.get() == RuleCatalog.OAL ? rel.endsWith(".oal") : rel.endsWith(".yaml") || rel.endsWith(".yml");
    }

    private static boolean isHidden(final String rel) {
        return Stream.of(rel.split("/")).anyMatch(part -> part.startsWith("."));
    }

    private static Set<String> rulePaths(final Path root) throws IOException {
        final Set<String> rules = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).map(p -> relative(root, p))
                .filter(rel -> isRulePath(rel) && !isHidden(rel)).forEach(rules::add);
        }
        return rules;
    }

    private void writeCompiledRules(final String digest) throws Exception {
        final Path record = state.resolve("compiled-rules");
        for (final String rule : rulePaths(staging)) {
            final Path target = record.resolve(rule);
            Files.createDirectories(target.getParent());
            Files.copy(staging.resolve(rule), target);
        }
        Files.createDirectories(record);
        if (overlay != null) {
            Files.copy(overlay.resolve(MANIFEST), record.resolve(MANIFEST));
        }
        final List<String> info = new ArrayList<>();
        info.add("# The rules compiled into this image, a record only: the image runs the classes compiled from them.");
        info.add("upstream.commit=" + upstreamCommit());
        info.add("overlay.digest=" + digest);
        Files.write(record.resolve("BUILD-INFO"), info, StandardCharsets.UTF_8);
    }

    /**
     * The submodule's commit: from its own git checkout, or in a source release (no git) from the version file the
     * release generates. A git command run elsewhere would find an enclosing repository's commit.
     */
    private String upstreamCommit() {
        Path submodule = upstream.toAbsolutePath().normalize();
        while (submodule != null && !"oap-server".equals(String.valueOf(submodule.getFileName()))) {
            submodule = submodule.getParent();
        }
        submodule = submodule == null ? null : submodule.getParent();
        if (submodule == null) {
            return "unknown";
        }
        if (Files.exists(submodule.resolve(".git"))) {
            try {
                final Process git = new ProcessBuilder("git", "rev-parse", "HEAD").directory(submodule.toFile())
                                                                                   .redirectErrorStream(true).start();
                final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return git.waitFor() == 0 && out.matches("[0-9a-f]{40}") ? out : "unknown";
            } catch (IOException | InterruptedException e) {
                return "unknown";
            }
        }
        final Path version = submodule.resolveSibling("oap-graalvm-server/src/main/resources/version.properties");
        if (Files.isRegularFile(version)) {
            final Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(version, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                return "unknown";
            }
            return properties.getProperty("git.commit.id", "unknown");
        }
        return "unknown";
    }

    /**
     * Over the manifest and every overlay file (rule or not), so any change to the overlay changes it.
     */
    private String digest(final Plan plan) throws Exception {
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        // Each field length-prefixed, so no two overlays share one byte stream.
        update(sha, plan.manifest);
        final Set<String> files = new TreeSet<>(plan.files.keySet());
        files.addAll(plan.ignored);
        for (final String file : files) {
            update(sha, file.getBytes(StandardCharsets.UTF_8));
            update(sha, Files.readAllBytes(overlay.resolve(file)));
        }
        return "sha256:" + HexFormat.of().formatHex(sha.digest());
    }

    private static void update(final MessageDigest sha, final byte[] field) {
        sha.update(ByteBuffer.allocate(Long.BYTES).putLong(field.length).array());
        sha.update(field);
    }

    private static String sha256(final Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String relative(final Path root, final Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static void copyTree(final Path from, final Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (final Path p : walk.collect(Collectors.toList())) {
                final Path target = to.resolve(relative(from, p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    /**
     * Each overlay rule file with its action ({@code add} / {@code override}), the bundled files excluded, and the
     * overlay's non-rule files, which are ignored.
     */
    static final class Plan {
        final Map<String, String> files = new TreeMap<>();
        final Set<String> excluded = new TreeSet<>();
        final List<String> ignored = new ArrayList<>();
        byte[] manifest;
    }
}
