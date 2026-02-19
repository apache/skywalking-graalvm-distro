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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.StreamDefinition;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * GraalVM replacement for upstream MeterSystem.
 * Same FQCN — shadows the upstream class via Maven classpath precedence.
 *
 * Loads @MeterFunction classes from a build-time manifest instead of Guava ClassPath.from().
 * Loads pre-generated meter classes from classpath instead of Javassist dynamic generation.
 */
@Slf4j
public class MeterSystem implements Service {
    private static final String METER_CLASS_PACKAGE = "org.apache.skywalking.oap.server.core.analysis.meter.dynamic.";
    private static final String METER_FUNCTION_MANIFEST = "META-INF/annotation-scan/MeterFunction.txt";

    private ModuleManager manager;
    private Map<String, Class<? extends AcceptableValue>> functionRegister = new HashMap<>();
    private Map<String, MeterDefinition> meterPrototypes = new HashMap<>();

    @SuppressWarnings("unchecked")
    public MeterSystem(final ModuleManager manager) {
        this.manager = manager;

        // Read from build-time manifest instead of ClassPath.from()
        try (InputStream is = MeterSystem.class.getClassLoader().getResourceAsStream(METER_FUNCTION_MANIFEST)) {
            if (is == null) {
                throw new UnexpectedException(
                    "MeterFunction manifest not found: " + METER_FUNCTION_MANIFEST);
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) {
                        log.warn("Invalid MeterFunction manifest line: {}", line);
                        continue;
                    }
                    String functionName = parts[0];
                    String className = parts[1];

                    Class<?> functionClass = Class.forName(className);
                    if (!AcceptableValue.class.isAssignableFrom(functionClass)) {
                        throw new IllegalArgumentException(
                            "Function " + functionClass.getCanonicalName()
                                + " doesn't implement AcceptableValue.");
                    }
                    functionRegister.put(
                        functionName,
                        (Class<? extends AcceptableValue>) functionClass
                    );
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new UnexpectedException("Failed to load MeterFunction manifest", e);
        }

        log.info("MeterSystem: loaded {} meter functions from manifest", functionRegister.size());
    }

    public synchronized <T> void create(String metricsName,
                                        String functionName,
                                        ScopeType type) throws IllegalArgumentException {
        final Class<? extends AcceptableValue> meterFunction = functionRegister.get(functionName);

        if (meterFunction == null) {
            throw new IllegalArgumentException("Function " + functionName + " can't be found.");
        }
        Type acceptance = null;
        for (final Type genericInterface : meterFunction.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                if (parameterizedType.getRawType().getTypeName().equals(AcceptableValue.class.getName())) {
                    Type[] arguments = parameterizedType.getActualTypeArguments();
                    acceptance = arguments[0];
                    break;
                }
            }
        }
        try {
            create(metricsName, functionName, type, Class.forName(Objects.requireNonNull(acceptance).getTypeName()));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> void create(String metricsName,
                                        String functionName,
                                        ScopeType type,
                                        Class<T> dataType) throws IllegalArgumentException {
        // Skip if already registered
        if (meterPrototypes.containsKey(metricsName)) {
            MeterDefinition existing = meterPrototypes.get(metricsName);
            if (existing.getScopeType() != type) {
                throw new IllegalArgumentException(
                    metricsName + " has been defined, but scope type is different.");
            }
            log.info("Metric {} is already defined, so skip the metric creation.", metricsName);
            return;
        }

        final Class<? extends AcceptableValue> meterFunction = functionRegister.get(functionName);
        if (meterFunction == null) {
            throw new IllegalArgumentException("Function " + functionName + " can't be found.");
        }

        // Validate data type
        boolean foundDataType = false;
        String acceptance = null;
        for (final Type genericInterface : meterFunction.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                if (parameterizedType.getRawType().getTypeName().equals(AcceptableValue.class.getName())) {
                    Type[] arguments = parameterizedType.getActualTypeArguments();
                    if (arguments[0].equals(dataType)) {
                        foundDataType = true;
                    } else {
                        acceptance = arguments[0].getTypeName();
                    }
                }
                if (foundDataType) {
                    break;
                }
            }
        }
        if (!foundDataType) {
            throw new IllegalArgumentException("Function " + functionName
                                                   + " requires <" + acceptance + "> in AcceptableValue"
                                                   + " but using " + dataType.getName() + " in the creation");
        }

        if (!Metrics.class.isAssignableFrom(meterFunction)) {
            throw new IllegalArgumentException(
                "Function " + functionName + " doesn't inherit from Metrics.");
        }

        // Load pre-generated meter class from classpath (built by Precompiler)
        final String className = METER_CLASS_PACKAGE + formatName(metricsName);
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Metrics> targetClass =
                (Class<? extends Metrics>) Class.forName(className);
            AcceptableValue prototype = (AcceptableValue) targetClass.getDeclaredConstructor().newInstance();
            meterPrototypes.put(metricsName, new MeterDefinition(type, prototype, dataType));

            log.debug("Loaded pre-generated meter class: {}", className);

            MetricsStreamProcessor.getInstance().create(
                manager,
                new StreamDefinition(
                    metricsName, type.getScopeId(), prototype.builder(), MetricsStreamProcessor.class),
                targetClass
            );
        } catch (ClassNotFoundException e) {
            throw new UnexpectedException(
                "Pre-generated meter class not found: " + className
                    + ". Ensure the precompiler generated this class at build time.", e);
        } catch (StorageException | ReflectiveOperationException e) {
            log.error("Can't load/init pre-generated meter class: {}", className, e);
            throw new UnexpectedException(e.getMessage(), e);
        }
    }

    public <T> AcceptableValue<T> buildMetrics(String metricsName,
                                               Class<T> dataType) {
        MeterDefinition meterDefinition = meterPrototypes.get(metricsName);
        if (meterDefinition == null) {
            throw new IllegalArgumentException("Uncreated metrics " + metricsName);
        }
        if (!meterDefinition.getDataType().equals(dataType)) {
            throw new IllegalArgumentException(
                "Unmatched metrics data type, request for " + dataType.getName()
                    + ", but defined as " + meterDefinition.getDataType());
        }

        return meterDefinition.getMeterPrototype().createNew();
    }

    public void doStreamingCalculation(AcceptableValue acceptableValue) {
        final long timeBucket = acceptableValue.getTimeBucket();
        if (timeBucket == 0L) {
            acceptableValue.setTimeBucket(TimeBucket.getMinuteTimeBucket(System.currentTimeMillis()));
        }
        MetricsStreamProcessor.getInstance().in((Metrics) acceptableValue);
    }

    private static String formatName(String metricsName) {
        return metricsName.toLowerCase();
    }

    @RequiredArgsConstructor
    @Getter
    private static class MeterDefinition {
        private final ScopeType scopeType;
        private final AcceptableValue meterPrototype;
        private final Class<?> dataType;
    }
}
