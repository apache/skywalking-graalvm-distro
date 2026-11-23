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
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Expression;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.SampleFamilyBuilder;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The comparison holds the two paths to what upstream emits: the samples of each entity, so two expressions that report
 * the same values under another layer differ, each path running on inputs of its own.
 */
class ComparisonEntityTest extends MALScriptComparisonBase {
    @Test
    void expressionsRoutingToAnotherLayerDiffer() throws Exception {
        final ImmutableMap<String, SampleFamily> input = ImmutableMap.of("calls", SampleFamilyBuilder.newBuilder(
            Sample.builder().name("calls").labels(ImmutableMap.of("service", "checkout")).value(3)
                  .timestamp(1_700_000_000_000L).build()).build());
        final String toGeneral = "calls.sum(['service']).service(['service'], Layer.GENERAL)";
        final Expression general = compileFresh("meter_calls", toGeneral);
        final Expression mesh = compileFresh("meter_calls", "calls.sum(['service']).service(['service'], Layer.MESH)");

        runAndCompare("meter_calls", general, compileFresh("meter_calls", toGeneral), false, input, input, false);
        assertThrows(AssertionFailedError.class,
            () -> runAndCompare("meter_calls", general, mesh, false, input, input, false));
    }
}
