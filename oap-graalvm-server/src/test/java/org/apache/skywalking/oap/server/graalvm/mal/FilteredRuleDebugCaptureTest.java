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

package org.apache.skywalking.oap.server.graalvm.mal;

import com.google.common.collect.ImmutableMap;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.oap.meter.analyzer.v2.Analyzer;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.debug.MALDebugRecorder;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.dsl.Catalog;
import org.apache.skywalking.oap.server.core.dsl.debug.GateHolder;
import org.apache.skywalking.oap.server.core.dsl.debug.RuleKey;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs upstream's {@link MetricConvert} / {@link Analyzer} pipeline over the pre-compiled classes of
 * a rule file with a file-level filter while a debug session is attached to every rule. The filter
 * probe in {@code Analyzer.analyse} only fires with a session active, and it reads the filter text
 * through {@code FilterExpression.getLiteral()}; the same-FQCN replacement must keep that accessor,
 * otherwise ingestion dies with {@code NoSuchMethodError} the moment an operator starts a session.
 */
class FilteredRuleDebugCaptureTest {
    private static final String FILTER = "{ tags -> tags.job_name == 'skywalking-so11y' }";

    @Test
    void filteredRuleKeepsAnalysingWhileDebugSessionIsActive() throws Exception {
        MeterSystem meterSystem = Mockito.mock(MeterSystem.class);
        Rule rule = Rules.loadRules("otel-rules", List.of("oap")).stream()
            .filter(r -> "oap".equals(r.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals(FILTER, rule.getFilter().trim(), "otel-rules/oap.yaml no longer carries the expected filter");

        MetricConvert convert = new MetricConvert(rule, meterSystem);
        CapturingRecorder recorder = new CapturingRecorder();
        for (Analyzer analyzer : analyzersOf(convert)) {
            GateHolder holder = analyzer.getExpression().debugHolder();
            assertNotNull(holder, "debug probes are not compiled into " + analyzer.getMetricName());
            holder.addRecorder(recorder);
        }

        long now = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        Sample sample = Sample.builder()
            .name("jvm_memory_bytes_used")
            .labels(ImmutableMap.of(
                "job_name", "skywalking-so11y",
                "service", "oap-svc",
                "host_name", "oap-host-1",
                "area", "heap"))
            .value(1024)
            .timestamp(now)
            .build();
        ImmutableMap<String, SampleFamily> input = ImmutableMap.of(
            "jvm_memory_bytes_used", SampleFamilyBuilder.newBuilder(sample).build());

        convert.toMeter(input);

        assertTrue(recorder.filterCaptures > 0, "the filter probe never fired");
        assertEquals(FILTER, recorder.lastFilterText);
        assertTrue(recorder.lastKept, "the so11y sample should survive the job_name filter");
        assertFalse(recorder.lastSurviving.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<Analyzer> analyzersOf(MetricConvert convert) throws Exception {
        Field field = MetricConvert.class.getDeclaredField("analyzers");
        field.setAccessible(true);
        return (List<Analyzer>) field.get(convert);
    }

    private static final class CapturingRecorder implements MALDebugRecorder {
        private int filterCaptures;
        private String lastFilterText;
        private boolean lastKept;
        private Map<String, SampleFamily> lastSurviving = Map.of();

        @Override
        public String sessionId() {
            return "test";
        }

        @Override
        public RuleKey ruleKey() {
            return new RuleKey(Catalog.OTEL_RULES, "oap", "*");
        }

        @Override
        public boolean matches(RuleKey candidate) {
            return true;
        }

        @Override
        public boolean isCaptured() {
            return false;
        }

        @Override
        public void appendInput(String rule, String metricRef, SampleFamily family) {
        }

        @Override
        public void appendFilter(String rule, String filterText, Map<String, SampleFamily> surviving, boolean kept) {
            filterCaptures++;
            lastFilterText = filterText;
            lastSurviving = surviving;
            lastKept = kept;
        }

        @Override
        public void appendStage(String rule, String sourceText, SampleFamily family) {
        }

        @Override
        public void appendDownsample(String rule, String function, String origin, SampleFamily family) {
        }

        @Override
        public void appendMeterEmit(String rule, MeterEntity entity, String metricName,
                                    AcceptableValue<?> value, long timeBucket) {
        }
    }
}
