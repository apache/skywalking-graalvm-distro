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

package org.apache.skywalking.oap.server.graalvm.admin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.dsl.Catalog;
import org.apache.skywalking.oap.server.core.rule.ext.RuleSetMerger;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.core.storage.management.RuntimeRuleManagementDAO;
import org.apache.skywalking.oap.server.graalvm.OverlayFiles;
import org.apache.skywalking.oap.server.graalvm.lal.UpstreamLalBoot;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.apache.skywalking.oap.server.receiver.runtimerule.engine.RuleEngineRegistry;
import org.apache.skywalking.oap.server.receiver.runtimerule.engine.lal.LalRuleEngine;
import org.apache.skywalking.oap.server.receiver.runtimerule.engine.mal.MalRuleEngine;
import org.apache.skywalking.oap.server.receiver.runtimerule.reconcile.DSLManager;
import org.apache.skywalking.oap.server.receiver.runtimerule.reconcile.StaticRuleLoader;
import org.apache.skywalking.oap.server.receiver.runtimerule.rest.RuntimeRuleRestHandler;
import org.apache.skywalking.oap.server.receiver.runtimerule.state.AppliedRuleScript;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The read-only rule catalog answers each read route as upstream's runtime-rule module answers it on a node whose
 * storage holds no runtime rule: upstream's own {@code RuntimeRuleRestHandler} over the {@code StaticRuleRegistry}
 * the replacement rule loaders fill at boot ({@link BootRuleLoads}), its boot-time {@code StaticRuleLoader} run, and an
 * empty rule DAO, side by side with the distro's handler over a matrix of requests; the rules of a module turned off
 * are in neither.
 * Left out: {@code generatedAt} (a clock), the order of {@code /list} rows (upstream's is a hash map's) and the order
 * of the catalogs an {@code invalid_catalog} message names (upstream's is a hash set's).
 */
class BundledRuleCatalogParityTest {
    private static final Pattern CATALOG_LIST = Pattern.compile("catalog must be one of \\[(.*)]");
    private static final String[] HEADERS = {
        "content-type", "x-sw-content-hash", "x-sw-status", "x-sw-source", "x-sw-update-time", "etag"};

    /** The catalogs upstream's runtime-rule read routes serve rule files of. */
    private static final Set<String> SERVED = Arrays.stream(Catalog.values()).filter(c -> c != Catalog.OAL)
                                                    .map(Catalog::getWireName).collect(Collectors.toSet());

    /** Two bundled rule names of each catalog, the first and the last. */
    private static final Map<String, List<String>> NAMES = new LinkedHashMap<>();

    private static RuntimeRuleRestHandler UPSTREAM;
    private static BundledRuleCatalogHandler DISTRO;

    @BeforeAll
    static void registerLayers() throws Exception {
        UpstreamLalBoot.registerLayers();
        DISTRO = new BundledRuleCatalogHandler();
    }

    @BeforeEach
    void bootWithEveryModule() throws Exception {
        BootRuleLoads.bootWith(Set.of(RuleCatalog.values()));
        // From the staged rule tree itself, not from what the loaders recorded
        NAMES.clear();
        OverlayFiles.staged(RuleCatalog.mergedByLoader()).forEach((catalog, names) -> {
            if (!names.isEmpty()) {
                NAMES.put(catalog, names.size() == 1 ? List.of(names.get(0))
                    : List.of(names.get(0), names.get(names.size() - 1)));
            }
        });
        UPSTREAM = upstream();
    }

    /**
     * Upstream's handler over the registry as it is: its runtime-rule module claims the bundled rules when it starts.
     */
    private static RuntimeRuleRestHandler upstream() throws Exception {
        final ModuleManager moduleManager = mock(ModuleManager.class);
        final ModuleProviderHolder storage = mock(ModuleProviderHolder.class);
        final ModuleServiceHolder storageServices = mock(ModuleServiceHolder.class);
        final RuntimeRuleManagementDAO dao = mock(RuntimeRuleManagementDAO.class);
        when(moduleManager.find(StorageModule.NAME)).thenReturn(storage);
        when(storage.provider()).thenReturn(storageServices);
        when(storageServices.getService(RuntimeRuleManagementDAO.class)).thenReturn(dao);
        when(dao.getAll()).thenReturn(List.of());

        final Map<String, AppliedRuleScript> rules = new ConcurrentHashMap<>();
        final RuleEngineRegistry engines = new RuleEngineRegistry();
        engines.register(new MalRuleEngine(rules, moduleManager));
        engines.register(new LalRuleEngine(rules, moduleManager));
        new StaticRuleLoader(engines, rules, null, null).loadAll();
        final DSLManager dslManager = mock(DSLManager.class);
        when(dslManager.getRules()).thenReturn(rules);
        when(dslManager.getEngineRegistry()).thenReturn(engines);
        return new RuntimeRuleRestHandler(moduleManager, dslManager, null, 1000L);
    }

    /**
     * Of the files the rules overlay left (as a boot with every module records them), those of a module turned off leave
     * its bundled listing, the others stay in theirs; every GET answers as upstream's, whatever its status (a name
     * upstream refuses gets its 400). An overlay leaving none passes.
     */
    @Test
    void theRulesOfAModuleTurnedOffAreNotInTheCatalog() throws Exception {
        final Map<String, List<String>> staged = OverlayFiles.staged(RuleCatalog.mergedByLoader());
        final Set<RuleCatalog> loaded = new HashSet<>(Set.of(RuleCatalog.values()));
        loaded.remove(RuleCatalog.TELEGRAF_RULES);
        BootRuleLoads.bootWith(loaded);
        final RuntimeRuleRestHandler upstream = upstream();

        for (final String catalog : catalogs("")) {
            assertSameAnswer("list " + catalog, upstream.list(catalog), DISTRO.list(catalog));
            assertSameAnswer("bundled " + catalog, upstream.listBundled(catalog, "true"),
                DISTRO.listBundled(catalog, "true"));
        }
        for (final Map.Entry<String, List<String>> catalog : staged.entrySet()) {
            final Set<String> bundled = SERVED.contains(catalog.getKey()) ? bundledNames(catalog.getKey()) : Set.of();
            for (final String name : catalog.getValue()) {
                assertSameAnswer("get " + catalog.getKey() + "/" + name, upstream.get(catalog.getKey(), name, "", "", ""),
                    DISTRO.get(catalog.getKey(), name, "", "", ""));
                if (SERVED.contains(catalog.getKey())) {
                    assertEquals(!RuleCatalog.TELEGRAF_RULES.getDirectory().equals(catalog.getKey()),
                        bundled.contains(name), catalog.getKey() + "/" + name + " in the bundled listing");
                }
            }
        }
        assertEquals(Set.of(), bundledNames(RuleCatalog.TELEGRAF_RULES.getDirectory()));
    }

    /**
     * Upstream lists a LAL file only while a layer its rules name is registered, which a module turned off (one whose
     * rule file declares the layer) leaves undone: decided against the layers of the boot, not the build's.
     */
    @Test
    void aLalFileIsListedOnlyWhileTheLayerItsRuleNamesIsRegistered() throws Exception {
        // A name and an ordinal no layer of the boot (layer-extensions.yml, the rule files') takes
        String layer = "PARITY_LATE_LAYER";
        for (int i = 2; layerRegistered(layer); i++) {
            layer = "PARITY_LATE_LAYER_" + i;
        }
        int ordinal = 10_000;
        while (ordinalRegistered(ordinal)) {
            ordinal++;
        }
        // And a file name no staged LAL file has
        final List<String> stagedLal = OverlayFiles.staged(List.of(RuleCatalog.LAL)).get(RuleCatalog.LAL.getDirectory());
        String file = "parity-late-layer";
        for (int i = 2; stagedLal.contains(file); i++) {
            file = "parity-late-layer-" + i;
        }
        // One boot: the file recorded once, and one handler, as the image would have compiled the file
        RuleSetMerger.merge("lal", new HashMap<>(Map.of(file, ("rules:\n  - name: late\n    layer: " + layer
            + "\n    dsl: \"filter { sink {} }\"\n").getBytes(StandardCharsets.UTF_8))), List.of(), null);
        final BundledRuleCatalogHandler distro = new BundledRuleCatalogHandler(
            BundledRuleCatalogHandler.Claims.compiled().withLalFile(file, List.of(layer)));

        // Upstream's runtime-rule claims the files when it starts, after the modules registered their layers: each
        // state is answered by upstream started in it, the distro's by its one handler, per request.
        assertSameAnswer("list lal, layer unregistered", upstream().list("lal"), distro.list("lal"));
        assertFalse(listedNames(distro, "lal").contains(file));

        Layer.register(layer, ordinal, true);
        assertSameAnswer("list lal, layer registered", upstream().list("lal"), distro.list("lal"));
        assertTrue(listedNames(distro, "lal").contains(file));
    }

    /**
     * A file is listed from the rules the image compiled, bound as upstream's loader binds them: a layer written as a
     * one-element sequence binds as its element, as upstream's typed parser reads it. The rules overlay fixture's
     * {@code lal/fixture-bound.yaml} writes one; the distro's handler takes its claims from the generated registry.
     */
    @Test
    void aLalFileIsListedAsUpstreamBindsItsRules() throws Exception {
        assumeTrue(OverlayFiles.isTheRepositoryFixture("lal/fixture-bound.yaml"),
            "the rules overlay fixture is not built in");
        final BundledRuleCatalogHandler distro = new BundledRuleCatalogHandler();

        assertSameAnswer("list lal", upstream().list("lal"), distro.list("lal"));
        assertTrue(listedNames(distro, "lal").contains("fixture-bound"));
    }

    @Test
    void listAnswersAsUpstream() throws Exception {
        for (final String catalog : catalogs("", " lal ", "envoy-metrics-rules", "nope")) {
            assertSameAnswer("list " + catalog, UPSTREAM.list(catalog), DISTRO.list(catalog));
        }
        // Only files the rules overlay left (the staged tree's) are listed, and every MAL file of a served catalog whose
        // compiled rules name a metric is: what decides it, not the file's path
        final Set<String> staged = new HashSet<>();
        OverlayFiles.staged(RuleCatalog.mergedByLoader())
                    .forEach((catalog, names) -> names.forEach(name -> staged.add(catalog + "/" + name)));
        final Set<String> listed = new HashSet<>();
        JsonParser.parseString(DISTRO.list("").aggregate().join().contentUtf8()).getAsJsonObject()
                  .getAsJsonArray("rules").forEach(row -> listed.add(row.getAsJsonObject().get("catalog").getAsString()
                      + "/" + row.getAsJsonObject().get("name").getAsString()));
        assertTrue(staged.containsAll(listed), "listed but not staged: " + listed);
        for (final MalFile file : CompiledRules.malFiles()) {
            final List<MetricsRule> rules = file.getRule().getMetricsRules();
            if (SERVED.contains(file.getCatalog()) && rules != null
                && rules.stream().anyMatch(rule -> rule.getName() != null)) {
                final String key = file.getCatalog() + "/" + file.getRule().getName();
                assertTrue(listed.contains(key), key + " names metrics, so it is listed");
            }
        }
    }

    @Test
    void statusAnswersAsUpstream() {
        for (final String catalog : catalogs("")) {
            for (final String name : new String[] {"", "vm", "default"}) {
                assertSameAnswer("status " + catalog + "/" + name,
                    UPSTREAM.applyStatus(catalog, name, "", ""), DISTRO.applyStatus());
                assertSameAnswer("status " + catalog + "/" + name + " by apply id",
                    UPSTREAM.applyStatus(catalog, name, "abc", "apply-1"), DISTRO.applyStatus());
            }
        }
    }

    @Test
    void getAnswersAsUpstream() {
        final List<String> catalogs = catalogs("", null, "nope");
        for (final String catalog : catalogs) {
            final List<String> names = new ArrayList<>(NAMES.getOrDefault(catalog, List.of()));
            names.addAll(Arrays.asList("no-such-rule", "", null, "../x", "a//b", "/a", "a\\b", "a b"));
            for (final String name : names) {
                for (final String source : new String[] {"", "runtime", "bundled", "Weird"}) {
                    for (final String accept : new String[] {"", "application/json"}) {
                        final String request = "get " + catalog + "/" + name + " source=" + source + " " + accept;
                        assertSameAnswer(request, UPSTREAM.get(catalog, name, source, accept, ""),
                            DISTRO.get(catalog, name, source, accept, ""));
                    }
                }
                final String eTag = UPSTREAM.get(catalog, name, "", "", "").aggregate().join().headers().get("etag");
                if (eTag != null) {
                    assertSameAnswer("get " + catalog + "/" + name + " If-None-Match",
                        UPSTREAM.get(catalog, name, "", "", eTag), DISTRO.get(catalog, name, "", "", eTag));
                }
            }
        }
    }

    @Test
    void bundledAnswersAsUpstream() {
        for (final String catalog : catalogs("", null, "tal", "nope")) {
            for (final String withContent : new String[] {"true", "false", "FALSE", "1", "yes", "no", "", null}) {
                assertSameAnswer("bundled " + catalog + " withContent=" + withContent,
                    UPSTREAM.listBundled(catalog, withContent), DISTRO.listBundled(catalog, withContent));
            }
        }
    }

    // Asked one by one: the registry lists its layers only once sealed, which the boot after the test would refuse
    /**
     * @return the names of the rules the handler lists for the catalog
     */
    private static Set<String> listedNames(final BundledRuleCatalogHandler handler, final String catalog) {
        final Set<String> names = new HashSet<>();
        JsonParser.parseString(handler.list(catalog).aggregate().join().contentUtf8()).getAsJsonObject()
                  .getAsJsonArray("rules").forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static boolean layerRegistered(final String name) {
        try {
            Layer.valueOf(name);
            return true;
        } catch (IllegalArgumentException absent) {
            return false;
        }
    }

    private static boolean ordinalRegistered(final int ordinal) {
        try {
            Layer.valueOf(ordinal);
            return true;
        } catch (UnexpectedException absent) {
            return false;
        }
    }

    /**
     * @return the names the distro's bundled listing of the catalog holds
     */
    private static Set<String> bundledNames(final String catalog) {
        final Set<String> names = new HashSet<>();
        JsonParser.parseString(DISTRO.listBundled(catalog, "false").aggregate().join().contentUtf8()).getAsJsonArray()
                  .forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    /**
     * @return every catalog upstream's {@link Catalog} knows, then the extra ones
     */
    private static List<String> catalogs(final String... extra) {
        final List<String> catalogs = Arrays.stream(Catalog.values()).map(Catalog::getWireName)
                                            .collect(Collectors.toList());
        catalogs.addAll(Arrays.asList(extra));
        return catalogs;
    }

    private static void assertSameAnswer(final String request, final HttpResponse expected, final HttpResponse actual) {
        final AggregatedHttpResponse e = expected.aggregate().join();
        final AggregatedHttpResponse a = actual.aggregate().join();
        assertEquals(e.status(), a.status(), request);
        for (final String header : HEADERS) {
            assertEquals(e.headers().get(header), a.headers().get(header), request + " " + header);
        }
        final String type = String.valueOf(e.headers().get("content-type"));
        if (type.contains("json")) {
            assertEquals(normalized(JsonParser.parseString(e.contentUtf8())),
                normalized(JsonParser.parseString(a.contentUtf8())), request);
        } else {
            assertEquals(e.contentUtf8(), a.contentUtf8(), request);
        }
    }

    private static JsonElement normalized(final JsonElement body) {
        if (!body.isJsonObject()) {
            return body;
        }
        final JsonObject object = body.getAsJsonObject().deepCopy();
        object.remove("generatedAt");
        if (object.has("rules")) {
            final List<JsonElement> rows = new ArrayList<>();
            object.getAsJsonArray("rules").forEach(rows::add);
            rows.sort(Comparator.comparing(row -> row.getAsJsonObject().get("catalog").getAsString()
                + ":" + row.getAsJsonObject().get("name").getAsString()));
            final JsonArray sorted = new JsonArray();
            rows.forEach(sorted::add);
            object.add("rules", sorted);
        }
        if (object.has("message")) {
            final Matcher m = CATALOG_LIST.matcher(object.get("message").getAsString());
            if (m.matches()) {
                object.addProperty("message", "catalog must be one of " + Arrays.stream(m.group(1).split(", "))
                    .sorted().collect(Collectors.toList()));
            }
        }
        return object;
    }
}
