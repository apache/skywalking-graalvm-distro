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

package org.apache.skywalking.oap.server.graalvm.mal;

import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.graalvm.OverlayFiles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MAL rule a rules overlay added or overrode runs, compiled into the image, as a fresh compile of it does, on
 * inputs generated from its expressions. Nothing runs without an overlay.
 */
class OverlayRuleTest extends MALScriptComparisonBase {
    private static final List<String> CATALOGS = List.of(
        "otel-rules", "meter-analyzer-config", "log-mal-rules", "envoy-metrics-rules", "telegraf-rules", "zabbix-rules");
    private static final Path DISTRO = Path.of(System.getProperty("user.dir")).getParent();
    /** The filtered rule of the repository's own {@code test/rules-overlay-fixture}. */
    private static final String FIXTURE = "otel-rules/fixture/fixture-service.yaml";

    /** The overlay's layers, as the image registers them at boot, so its rules resolve them. */
    @BeforeAll
    static void registerCompiledLayers() {
        LayerExtensionLoader.load();
    }

    @TestFactory
    Stream<DynamicTest> everyOverlayMalRuleRunsAsAFreshCompile() throws Exception {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final String catalog : CATALOGS) {
            for (final String file : OverlayFiles.under(catalog)) {
                // An empty document holds no rule: the loaders skip it.
                if (isEmpty(file)) {
                    continue;
                }
                final boolean zabbix = "zabbix-rules".equals(catalog);
                generateOverlayComparisonTests(file, zabbix ? loadZabbixRule(file) : loadRule(file))
                    .forEach(tests::add);
            }
        }
        return tests.stream();
    }

    @Test
    void theFixturesFilterKeepsItsSamplesOnly() throws Exception {
        Assumptions.assumeTrue(isTheRepositoryFixture(), "not built with test/rules-overlay-fixture");
        final FilterExpression filter = new FilterExpression(loadRule(FIXTURE).getFilter());
        final Map<String, SampleFamily> kept = filter.filter(ImmutableMap.of(
            "fixture_calls", family("fixture_calls", "fixture")));
        final Map<String, SampleFamily> dropped = filter.filter(ImmutableMap.of(
            "fixture_calls", family("fixture_calls", "other")));
        assertEquals(1, kept.size(), "a sample of the fixture job passes the filter");
        assertTrue(dropped.isEmpty(), "a sample of another job does not");
    }

    private static boolean isTheRepositoryFixture() throws Exception {
        if (!OverlayFiles.under("otel-rules").contains(FIXTURE)) {
            return false;
        }
        final Path staged = Path.of(System.getProperty("rules.staging.dir",
            DISTRO.resolve("build-tools/precompiler/target/rule-resources").toString())).resolve(FIXTURE);
        return Arrays.equals(Files.readAllBytes(staged),
            Files.readAllBytes(DISTRO.resolve("test/rules-overlay-fixture").resolve(FIXTURE)));
    }

    private static boolean isEmpty(final String file) throws Exception {
        try (InputStream in = OverlayRuleTest.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("The overlay rule file " + file + " is not on the class path");
            }
            return new Yaml().load(in) == null;
        }
    }

    private static SampleFamily family(final String name, final String job) {
        return SampleFamilyBuilder.newBuilder(Sample.builder().name(name)
            .labels(ImmutableMap.of("job_name", job, "service", "svc")).value(1).timestamp(1).build()).build();
    }
}
