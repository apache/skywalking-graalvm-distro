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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesStagerTest {
    @TempDir
    Path root;
    private Path upstream;
    private Path overlay;

    @BeforeEach
    void bundledRules() throws Exception {
        upstream = root.resolve("upstream");
        overlay = root.resolve("overlay");
        write(upstream, "oal/core.oal", "core");
        write(upstream, "oal/tcp.oal", "tcp");
        write(upstream, "otel-rules/vm.yaml", "vm");
        write(upstream, "otel-rules/k8s/k8s-node.yaml", "node");
        write(upstream, "lal/default.yaml", "default");
        write(upstream, "hierarchy-definition.yml", "hierarchy: {}");
        write(upstream, "layer-extensions.yml", "layers: []");
        write(upstream, "application.yml", "core: {}");
    }

    @Test
    void anOverlayAddsOverridesAndExcludesFileByFile() throws Exception {
        manifest("add: [\"otel-rules/fleet/*\", oal/fleet.oal]\n"
            + "override: [lal/default.yaml, layer-extensions.yml]\n"
            + "exclude: [oal/tcp.oal, \"otel-rules/k8s/**\"]\n");
        write(overlay, "otel-rules/fleet/fleet-a.yaml", "a");
        write(overlay, "otel-rules/fleet/fleet-b.yml", "b");
        write(overlay, "oal/fleet.oal", "fleet");
        write(overlay, "lal/default.yaml", "overridden");
        write(overlay, "layer-extensions.yml", "layers: [FLEET]");
        write(overlay, "README.md", "not a rule");

        final Path staging = root.resolve("staging");
        final Path state = root.resolve("state");
        new RulesStager(upstream, overlay, staging, state).stage();

        assertEquals("overridden", read(staging, "lal/default.yaml"));
        assertEquals("a", read(staging, "otel-rules/fleet/fleet-a.yaml"));
        assertEquals("vm", read(staging, "otel-rules/vm.yaml"));
        assertEquals("core: {}", read(staging, "application.yml"));
        assertFalse(Files.exists(staging.resolve("oal/tcp.oal")));
        assertFalse(Files.exists(staging.resolve("otel-rules/k8s/k8s-node.yaml")));
        assertFalse(Files.exists(staging.resolve("README.md")));

        final List<String> inventory = Files.readAllLines(state.resolve(RulesStager.INVENTORY)).stream()
            .map(line -> line.substring(0, line.lastIndexOf('\t'))).sorted().collect(Collectors.toList());
        assertEquals(List.of("add\toal/fleet.oal", "add\totel-rules/fleet/fleet-a.yaml",
            "add\totel-rules/fleet/fleet-b.yml", "exclude\toal/tcp.oal", "exclude\totel-rules/k8s/k8s-node.yaml",
            "override\tlal/default.yaml", "override\tlayer-extensions.yml"), inventory);

        final Path record = state.resolve("compiled-rules");
        assertEquals("overridden", read(record, "lal/default.yaml"));
        assertTrue(Files.exists(record.resolve(RulesStager.MANIFEST)));
        assertFalse(Files.exists(record.resolve("oal/tcp.oal")));
        assertFalse(Files.exists(record.resolve("application.yml")), "the record holds rule files only");
        assertTrue(read(record, "BUILD-INFO").contains("overlay.digest=sha256:"));
    }

    @Test
    void restagingLeavesNothingOfAPreviousOverlay() throws Exception {
        manifest("add: [lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        final Path staging = root.resolve("staging");
        final Path state = root.resolve("state");
        new RulesStager(upstream, overlay, staging, state).stage();
        assertTrue(Files.exists(staging.resolve("lal/fleet.yaml")));

        new RulesStager(upstream, null, staging, state).stage();
        assertFalse(Files.exists(staging.resolve("lal/fleet.yaml")));
        assertFalse(Files.exists(state.resolve("compiled-rules/lal/fleet.yaml")));
        assertEquals(List.of(), Files.readAllLines(state.resolve(RulesStager.INVENTORY)));
        assertTrue(read(state.resolve("compiled-rules"), "BUILD-INFO").contains("overlay.digest=none"));
    }

    @Test
    void anUndeclaredRuleFileFails() throws Exception {
        manifest("add: [lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        write(overlay, "lal/other.yaml", "other");
        fails("lal/other.yaml is not declared");
    }

    @Test
    void aRuleFileDeclaredTwiceFails() throws Exception {
        manifest("add: [lal/fleet.yaml]\noverride: [\"lal/*\"]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        fails("declared both as add");
    }

    @Test
    void addingABundledFileFails() throws Exception {
        manifest("add: [lal/default.yaml]\n");
        write(overlay, "lal/default.yaml", "x");
        fails("declare it as override");
    }

    @Test
    void overridingAFileUpstreamDoesNotHaveFails() throws Exception {
        manifest("override: [lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "x");
        fails("declare it as add");
    }

    @Test
    void anEntryMatchingNoOverlayFileFails() throws Exception {
        manifest("add: [lal/fleet.yaml, tal/missing.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        fails("tal/missing.yaml of rules-overlay.yaml matches no overlay rule file");
    }

    @Test
    void aStaleExcludeFails() throws Exception {
        manifest("exclude: [oal/browser.oal]\n");
        fails("oal/browser.oal of rules-overlay.yaml matches no bundled rule file");
    }

    @Test
    void excludingAFileTheOverlayProvidesFails() throws Exception {
        manifest("override: [lal/default.yaml]\nexclude: [lal/default.yaml]\n");
        write(overlay, "lal/default.yaml", "x");
        fails("matches no bundled rule file the overlay does not provide");
    }

    @Test
    void twoFilesDifferingByExtensionFail() throws Exception {
        manifest("add: [lal/default.yml]\n");
        write(overlay, "lal/default.yml", "x");
        fails("differ by extension or case only");
    }

    @Test
    void aNestedFileInAFlatCatalogFails() throws Exception {
        manifest("add: [\"lal/**\"]\n");
        write(overlay, "lal/sub/fleet.yaml", "x");
        fails("nested in lal/");
    }

    @Test
    void theHierarchyCannotBeExcluded() throws Exception {
        manifest("exclude: [hierarchy-definition.yml]\n");
        fails("hierarchy-definition.yml cannot be excluded");
    }

    @Test
    void anOverlayWithoutAManifestFails() throws Exception {
        write(overlay, "lal/fleet.yaml", "x");
        fails("has no rules-overlay.yaml");
    }

    @Test
    void anEntryListedTwiceFails() throws Exception {
        manifest("add: [lal/fleet.yaml, lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        fails("lists lal/fleet.yaml twice");
    }

    @Test
    void aFileTheEnginesDoNotReadIsNotARuleFile() throws Exception {
        manifest("add: [log-mal-rules/fleet.yam]\n");
        write(overlay, "log-mal-rules/fleet.yam", "fleet");
        fails("log-mal-rules/fleet.yam of rules-overlay.yaml matches no overlay rule file");
    }

    @Test
    void aNameTheSettingsCannotCarryFails() throws Exception {
        manifest("add: [\"lal/*\"]\n");
        write(overlay, "lal/a,b.yaml", "x");
        fails("lal/a,b.yaml has a name this distro cannot carry");
    }

    @Test
    void aNameOutsideTheSafeCharactersFails() throws Exception {
        manifest("add: [\"lal/*\"]\n");
        write(overlay, "lal/team\u00a0.yaml", "rules: []");
        fails("lal/team\u00a0.yaml has a name this distro cannot carry");
    }

    @Test
    void aTooLongNameFails() throws Exception {
        final String name = "oal/" + "a".repeat(61) + ".oal";
        manifest("add: [\"oal/*\"]\n");
        write(overlay, name, "x");
        fails(name + " has a name this distro cannot carry");
    }

    @Test
    void aHiddenDirectoryIsNotEntered() throws Exception {
        final Path cache = overlay.resolve(".cache");
        Files.createDirectories(cache);
        Files.createSymbolicLink(cache.resolve("loop"), cache);
        manifest("add: [lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        final Path staging = root.resolve("staging");
        new RulesStager(upstream, overlay, staging, root.resolve("state")).stage();
        assertEquals("fleet", read(staging, "lal/fleet.yaml"));
    }

    @Test
    void aGlobCharacterInAMalNameFails() throws Exception {
        manifest("add: [\"otel-rules/*\"]\n");
        write(overlay, "otel-rules/a[1].yaml", "x");
        fails("otel-rules/a[1].yaml has a name this distro cannot carry");
    }

    @Test
    void aDoubleExtensionFails() throws Exception {
        manifest("add: [\"otel-rules/*\"]\n");
        write(overlay, "otel-rules/sample.yaml.yml", "x");
        fails("otel-rules/sample.yaml.yml has a name this distro cannot carry");
    }

    @Test
    void anAddDifferingFromABundledFileByCaseOnlyFails() throws Exception {
        manifest("add: [lal/DEFAULT.yaml]\n");
        write(overlay, "lal/DEFAULT.yaml", "x");
        fails("lal/DEFAULT.yaml and lal/default.yaml differ by extension or case only");
    }

    @Test
    void aDirectoryDifferingFromABundledOneByCaseOnlyFails() throws Exception {
        manifest("add: [otel-rules/K8S/fleet.yaml]\nexclude: [\"otel-rules/k8s/**\"]\n");
        write(overlay, "otel-rules/K8S/fleet.yaml", "x");
        fails("otel-rules/k8s and otel-rules/K8S differ by case only");
    }

    @Test
    void anotherExtensionAndCaseOfABundledFileFails() throws Exception {
        // Their generated manifests would be one file on a case-insensitive disk.
        manifest("add: [otel-rules/VM.yml]\n");
        write(overlay, "otel-rules/VM.yml", "x");
        fails("otel-rules/VM.yml and otel-rules/vm.yaml differ by extension or case only");
    }

    @Test
    void anAddMayReplaceAnExcludedFileOfAnotherCase() throws Exception {
        manifest("add: [lal/DEFAULT.yaml]\nexclude: [lal/default.yaml]\n");
        write(overlay, "lal/DEFAULT.yaml", "replaced");
        final Path staging = root.resolve("staging");
        new RulesStager(upstream, overlay, staging, root.resolve("state")).stage();
        try (Stream<Path> lal = Files.list(staging.resolve("lal"))) {
            assertEquals(List.of("DEFAULT.yaml"), lal.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aHiddenLinkCycleAtTheRootIsSkipped() throws Exception {
        Files.createDirectories(overlay);
        Files.createSymbolicLink(overlay.resolve(".cache"), overlay);
        manifest("add: [lal/fleet.yaml]\n");
        write(overlay, "lal/fleet.yaml", "fleet");
        final Path staging = root.resolve("staging");
        new RulesStager(upstream, overlay, staging, root.resolve("state")).stage();
        assertEquals("fleet", read(staging, "lal/fleet.yaml"));
    }

    @Test
    void aZabbixDoubleExtensionFails() throws Exception {
        manifest("add: [\"zabbix-rules/*\"]\n");
        write(overlay, "zabbix-rules/fleet.yaml.yml", "x");
        fails("zabbix-rules/fleet.yaml.yml has a name this distro cannot carry");
    }

    @Test
    void aTooLongPathFails() throws Exception {
        final String name = "otel-rules/" + "a".repeat(60) + "/" + "b".repeat(60) + "/c.yaml";
        manifest("add: [\"otel-rules/**\"]\n");
        write(overlay, name, "x");
        fails(name + " has a name this distro cannot carry");
    }

    @Test
    void anOalNameKeepsEachDispatcherItsSource() {
        // The rule browser cuts a dispatcher's simple name at its first "Dispatcher".
        final String prefix = Precompiler.OverlayOALDefine.catalog("oal/PaymentDispatcher.oal");
        assertEquals("OverlayPayment_dispatcher_", prefix);
        assertEquals("OverlayFleet_u_m_pDisp_", Precompiler.OverlayOALDefine.catalog("oal/Fleet_-.Disp.oal"));
    }

    @Test
    void aLinkedRuleDirectoryIsStaged() throws Exception {
        final Path shared = root.resolve("shared-lal");
        write(shared, "fleet.yaml", "fleet");
        Files.createDirectories(overlay);
        Files.createSymbolicLink(overlay.resolve("lal"), shared);
        manifest("add: [\"lal/*.yaml\"]\n");
        final Path staging = root.resolve("staging");
        new RulesStager(upstream, overlay, staging, root.resolve("state")).stage();
        assertEquals("fleet", read(staging, "lal/fleet.yaml"));
    }

    @Test
    void aFileOfAnotherExtensionIsNotARuleFile() throws Exception {
        // Upstream documents rule files as YAML; its loaders strip whatever extension a file has, but no rule file
        // upstream ships or documents relies on that.
        manifest("add: [tal/custom.rules]\n");
        write(overlay, "tal/custom.rules", "rules: []");
        fails("tal/custom.rules of rules-overlay.yaml matches no overlay rule file");
    }

    @Test
    void aNestedFileOfACatalogUpstreamWalksIsStaged() throws Exception {
        manifest("add: [log-mal-rules/team/fleet.yaml]\n");
        write(overlay, "log-mal-rules/team/fleet.yaml", "fleet");
        final Path staging = root.resolve("staging");
        new RulesStager(upstream, overlay, staging, root.resolve("state")).stage();
        assertEquals("fleet", read(staging, "log-mal-rules/team/fleet.yaml"));
    }

    @Test
    void aSectionGivenTwiceFails() throws Exception {
        manifest("exclude: [oal/tcp.oal]\nexclude: [lal/default.yaml]\n");
        fails("duplicate key exclude");
    }

    @Test
    void anUnknownManifestSectionFails() throws Exception {
        manifest("activate: [x]\n");
        fails("unknown section activate");
    }

    private void fails(final String message) {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> new RulesStager(upstream, overlay, root.resolve("staging"), root.resolve("state")).stage());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private void manifest(final String content) throws Exception {
        write(overlay, RulesStager.MANIFEST, content);
    }

    private static void write(final Path dir, final String file, final String content) throws Exception {
        final Path path = dir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String read(final Path dir, final String file) throws Exception {
        return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
    }
}
