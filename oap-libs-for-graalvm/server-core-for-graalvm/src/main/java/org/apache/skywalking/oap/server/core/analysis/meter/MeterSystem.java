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
import org.apache.skywalking.oap.server.core.analysis.meter.function.LabeledValueLimits;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.model.StorageManipulationOpt;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * GraalVM replacement for upstream MeterSystem.
 * Original: skywalking/oap-server/server-core/src/main/java/.../analysis/meter/MeterSystem.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: takes the @MeterFunction classes and the meter classes the precompiler generated from the generated
 * CompiledRules registry, instead of scanning the class path with Guava ClassPath and generating the meter classes
 * with Javassist. create is upstream's boot path (createInternal) with the class generation replaced by the registry
 * lookup.
 * Why: Guava ClassPath scanning and Javassist runtime code generation are incompatible with GraalVM native image.
 *
 * Left out on purpose, as only runtime-rule (not shipped) calls them: create(String, String, ScopeType, ClassPool,
 * ClassLoader, StorageManipulationOpt), create(String, String, ScopeType, Class, ClassPool, Class),
 * removeMetric(String), removeMetric(String, StorageManipulationOpt), suspendDispatch(Set) and resumeDispatch(Set).
 */
@Slf4j
public class MeterSystem implements Service {
    private static final String METER_CLASS_PACKAGE = "org.apache.skywalking.oap.server.core.analysis.meter.dynamic.";

    private ModuleManager manager;
    private Map<String, Class<? extends AcceptableValue>> functionRegister = new HashMap<>();
    private Map<String, MeterDefinition> meterPrototypes = new HashMap<>();
    /**
     * What upstream's class pool holds: the class of every metric whose creation got as far as making it, by class
     * name, kept when the registration then fails (a failed registration only drops the prototype).
     */
    private Map<String, Class<?>> definedClasses = new HashMap<>();

    public MeterSystem(final ModuleManager manager) {
        this.manager = manager;
        functionRegister.putAll(CompiledRules.meterFunctions());
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
        final StorageManipulationOpt opt = StorageManipulationOpt.schemaCreateIfAbsent();
        final Class<? extends AcceptableValue> meterFunction = functionRegister.get(functionName);

        if (meterFunction == null) {
            throw new IllegalArgumentException("Function " + functionName + " can't be found.");
        }

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
        final String className = formatName(metricsName);

        final MeterDefinition existingDefinition = meterPrototypes.get(metricsName);
        if (existingDefinition != null
            && existingDefinition.getScopeType() == type
            && existingDefinition.getDataType().equals(dataType)
            && existingDefinition.getMeterPrototype().getClass().getSuperclass() == meterFunction) {
            log.debug("Metric {} already registered with matching shape; reusing existing "
                + "Metrics class + workers (FILTER_ONLY re-apply path).", metricsName);
            return;
        }

        // Upstream's check of its class pool, as written: after a failed registration it reads the dropped prototype
        // and throws, so a second declaration of the metric never reaches the storage again
        final Class<?> existingMetric = definedClasses.get(className);
        if (existingMetric != null) {
            if (existingMetric.getSuperclass() != meterFunction
                || type != meterPrototypes.get(metricsName).getScopeType()) {
                throw new IllegalArgumentException(
                    metricsName + " has been defined, but calculate function or/are scope type is/are different.");
            }
            log.info("Metric {} is already defined, so skip the metric creation.", metricsName);
            return;
        }

        // By the metric's own name: the class name lowercases it in the build's locale, which the boot's may not share
        final Class<?> targetClass = CompiledRules.meterClasses().get(metricsName);
        if (targetClass == null) {
            throw new UnexpectedException("Pre-generated meter class not found for metric " + metricsName + " ("
                + METER_CLASS_PACKAGE + className + "). Ensure the precompiler generated this class at build time.");
        }
        if (targetClass.getSuperclass() != meterFunction) {
            throw new UnexpectedException("Pre-generated meter class " + targetClass.getName() + " extends "
                + targetClass.getSuperclass().getName() + ", not the function " + functionName + " ("
                + meterFunction.getName() + ") this creation asks for.");
        }
        // Where upstream makes the class in its pool
        definedClasses.put(className, targetClass);
        try {
            AcceptableValue prototype = (AcceptableValue) targetClass.getDeclaredConstructor().newInstance();
            meterPrototypes.put(metricsName, new MeterDefinition(type, prototype, dataType));

            log.debug("Generate metrics class, " + targetClass.getName());

            MetricsStreamProcessor.getInstance().create(
                manager,
                new StreamDefinition(
                    metricsName, type.getScopeId(), prototype.builder(), MetricsStreamProcessor.class),
                (Class<? extends Metrics>) targetClass,
                opt
            );
            if (opt.hasShapeMismatch()) {
                meterPrototypes.remove(metricsName);
            }
        } catch (ReflectiveOperationException | StorageException e) {
            log.error("Can't compile/load/init " + className + ".", e);
            meterPrototypes.remove(metricsName);
            throw new UnexpectedException(e.getMessage(), e);
        }
    }

    /**
     * Cap the label sets one row of a labeled metric holds, see {@link LabeledValueLimits}.
     *
     * @param metricsName the metric, as it was created
     * @param maxLabels   the most label sets one row holds; 0 or less for no limit
     */
    public void limitLabels(final String metricsName, final int maxLabels) {
        // The class the metric was created with, named as the build named it
        final Class<?> recorded = CompiledRules.meterClasses().get(metricsName);
        LabeledValueLimits.register(
            recorded != null ? recorded.getName() : METER_CLASS_PACKAGE + formatName(metricsName), maxLabels);
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
