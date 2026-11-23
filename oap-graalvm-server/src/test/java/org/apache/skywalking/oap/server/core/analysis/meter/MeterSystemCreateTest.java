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

package org.apache.skywalking.oap.server.core.analysis.meter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Map;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.StreamDefinition;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.LabeledValueLimits;
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.model.StorageManipulationOpt;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The same-FQCN {@code MeterSystem} creates a metric from the pre-generated meter class as upstream's boot path does:
 * the same duplicate checks, and the metric left uncreated when its streams do not come up.
 */
class MeterSystemCreateTest {
    private MockedStatic<MetricsStreamProcessor> processor;
    private MetricsStreamProcessor streams;
    private String metric;
    private String function;

    @BeforeEach
    void mockStreams() {
        streams = mock(MetricsStreamProcessor.class);
        processor = Mockito.mockStatic(MetricsStreamProcessor.class);
        processor.when(MetricsStreamProcessor::getInstance).thenReturn(streams);
        for (final Map.Entry<String, Class<?>> meter : CompiledRules.meterClasses().entrySet()) {
            final MeterFunction meterFunction = meter.getValue().getSuperclass().getAnnotation(MeterFunction.class);
            if (meterFunction != null) {
                metric = meter.getKey();
                function = meterFunction.functionName();
                return;
            }
        }
        throw new AssertionError("no pre-generated meter class extends a meter function");
    }

    @AfterEach
    void closeStreams() {
        processor.close();
    }

    @Test
    void aMetricCreatedAgainWithTheSameShapeIsCreatedOnce() throws Exception {
        final MeterSystem meterSystem = new MeterSystem(null);
        meterSystem.create(metric, function, ScopeType.SERVICE);
        meterSystem.create(metric, function, ScopeType.SERVICE);
        verify(streams, times(1)).create(any(), any(StreamDefinition.class), any(), any(StorageManipulationOpt.class));
    }

    @Test
    void aMetricCreatedAgainWithAnotherFunctionOrScopeFails() {
        final MeterSystem meterSystem = new MeterSystem(null);
        meterSystem.create(metric, function, ScopeType.SERVICE);
        final String other = CompiledRules.meterFunctions().keySet().stream().filter(f -> !f.equals(function))
                                          .findFirst().orElseThrow();
        final IllegalArgumentException byFunction = assertThrows(IllegalArgumentException.class,
            () -> meterSystem.create(metric, other, ScopeType.SERVICE));
        assertEquals(metric + " has been defined, but calculate function or/are scope type is/are different.",
            byFunction.getMessage());
        assertThrows(IllegalArgumentException.class, () -> meterSystem.create(metric, function, ScopeType.ENDPOINT));
    }

    @Test
    void aMetricTheStorageRefusesToReshapeIsLeftUncreated() throws Exception {
        doAnswer(invocation -> {
            invocation.<StorageManipulationOpt>getArgument(3).recordOutcome(
                "measure", metric, StorageManipulationOpt.Outcome.SKIPPED_SHAPE_MISMATCH, "another shape");
            return null;
        }).when(streams).create(any(), any(StreamDefinition.class), any(), any(StorageManipulationOpt.class));
        final MeterSystem meterSystem = new MeterSystem(null);
        meterSystem.create(metric, function, ScopeType.SERVICE);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> meterSystem.buildMetrics(metric, Long.class));
        assertEquals("Uncreated metrics " + metric, e.getMessage());
        assertSecondDeclarationStopsBeforeTheStorage(meterSystem);
    }

    /**
     * A second declaration of a metric whose registration failed (bundled rule files declare some metrics twice) stops
     * at upstream's class-pool check, which keeps the class and reads the prototype the failure dropped, before it
     * reaches the stream processor and the storage again.
     */
    private void assertSecondDeclarationStopsBeforeTheStorage(final MeterSystem meterSystem) throws Exception {
        assertThrows(NullPointerException.class, () -> meterSystem.create(metric, function, ScopeType.SERVICE));
        verify(streams, times(1)).create(any(), any(StreamDefinition.class), any(), any(StorageManipulationOpt.class));
        assertThrows(IllegalArgumentException.class, () -> meterSystem.buildMetrics(metric, Long.class));
    }

    @Test
    void aMetricWhoseStreamsFailIsLeftUncreated() throws Exception {
        doThrow(new StorageException("refused"))
            .when(streams).create(any(), any(StreamDefinition.class), any(), any(StorageManipulationOpt.class));
        final MeterSystem meterSystem = new MeterSystem(null);
        assertThrows(UnexpectedException.class, () -> meterSystem.create(metric, function, ScopeType.SERVICE));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> meterSystem.buildMetrics(metric, Long.class));
        assertEquals("Uncreated metrics " + metric, e.getMessage());
        assertSecondDeclarationStopsBeforeTheStorage(meterSystem);
    }

    @Test
    void aCreatedMetricBuildsFromItsPreGeneratedClass() throws Exception {
        final MeterSystem meterSystem = new MeterSystem(null);
        meterSystem.create(metric, function, ScopeType.SERVICE);
        assertNotNull(meterSystem.buildMetrics(metric, acceptedType(CompiledRules.meterFunctions().get(function))));
    }

    /**
     * The boot lowercases a metric name for its class in the JVM's locale, which need not be the build's (Turkish
     * lowercases {@code I} to a dotless {@code ı}): the class is the one the build recorded for the name, and a label
     * limit set for the name applies to it.
     */
    @Test
    void aMetricIsCreatedFromItsRecordedClassWhateverTheBootsLocale() throws Exception {
        final Map.Entry<String, Class<?>> dotted = CompiledRules.meterClasses().entrySet().stream()
            .filter(meter -> meter.getKey().contains("I")
                && meter.getValue().getSuperclass().getAnnotation(MeterFunction.class) != null)
            .findFirst().orElseThrow(() -> new AssertionError("no metric name with an upper-case I"));
        final String name = dotted.getKey();
        final String dottedFunction = dotted.getValue().getSuperclass().getAnnotation(MeterFunction.class).functionName();
        final Locale locale = Locale.getDefault();
        final int limitBefore = LabeledValueLimits.of((Metrics) dotted.getValue().getDeclaredConstructor().newInstance());
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            final MeterSystem meterSystem = new MeterSystem(null);
            meterSystem.create(name, dottedFunction, ScopeType.SERVICE);
            final AcceptableValue<?> created =
                meterSystem.buildMetrics(name, acceptedType(CompiledRules.meterFunctions().get(dottedFunction)));
            assertEquals(dotted.getValue(), created.getClass());
            meterSystem.limitLabels(name, limitBefore + 7);
            assertEquals(limitBefore + 7, LabeledValueLimits.of((Metrics) created));
        } finally {
            Locale.setDefault(locale);
            LabeledValueLimits.register(dotted.getValue().getName(), limitBefore);
        }
    }

    private static Class<?> acceptedType(final Class<?> meterFunction) throws ClassNotFoundException {
        for (final Type type : meterFunction.getGenericInterfaces()) {
            if (type instanceof ParameterizedType
                && ((ParameterizedType) type).getRawType().equals(AcceptableValue.class)) {
                return Class.forName(((ParameterizedType) type).getActualTypeArguments()[0].getTypeName());
            }
        }
        throw new AssertionError(meterFunction + " is no AcceptableValue");
    }
}
