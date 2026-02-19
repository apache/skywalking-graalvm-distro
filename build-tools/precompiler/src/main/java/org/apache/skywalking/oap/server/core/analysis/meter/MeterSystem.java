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

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.NotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.Service;

/**
 * Build-time same-FQCN replacement for upstream MeterSystem.
 * Uses Javassist to generate meter classes and writes .class files to disk
 * instead of loading them into the JVM. Skips MetricsStreamProcessor registration.
 *
 * The Precompiler sets functionRegister and outputDirectory before creating this instance.
 */
@Slf4j
public class MeterSystem implements Service {
    private static final String METER_CLASS_PACKAGE = "org.apache.skywalking.oap.server.core.analysis.meter.dynamic.";

    private static Map<String, Class<? extends AcceptableValue>> PRELOADED_FUNCTION_REGISTER;
    private static String OUTPUT_DIRECTORY;

    private ClassPool classPool;
    private Map<String, Class<? extends AcceptableValue>> functionRegister = new HashMap<>();
    private Map<String, MeterDefinition> meterPrototypes = new HashMap<>();
    private final List<String> exportedClasses = new ArrayList<>();

    public static void setFunctionRegister(Map<String, Class<? extends AcceptableValue>> register) {
        PRELOADED_FUNCTION_REGISTER = register;
    }

    public static void setOutputDirectory(String dir) {
        OUTPUT_DIRECTORY = dir;
    }

    public static void reset() {
        PRELOADED_FUNCTION_REGISTER = null;
        OUTPUT_DIRECTORY = null;
    }

    public List<String> getExportedClasses() {
        return Collections.unmodifiableList(exportedClasses);
    }

    @SuppressWarnings("unchecked")
    public MeterSystem(final ModuleManager manager) {
        classPool = ClassPool.getDefault();

        if (PRELOADED_FUNCTION_REGISTER != null) {
            functionRegister = new HashMap<>(PRELOADED_FUNCTION_REGISTER);
            log.info("MeterSystem (build-time): loaded {} meter functions from preloaded register",
                functionRegister.size());
        } else {
            throw new UnexpectedException(
                "Build-time MeterSystem requires preloaded function register. "
                + "Call MeterSystem.setFunctionRegister() before construction.");
        }
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

    public synchronized <T> void create(String metricsName,
                                        String functionName,
                                        ScopeType type,
                                        Class<T> dataType) throws IllegalArgumentException {
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

        final CtClass parentClass;
        try {
            parentClass = classPool.get(meterFunction.getCanonicalName());
            if (!Metrics.class.isAssignableFrom(meterFunction)) {
                throw new IllegalArgumentException(
                    "Function " + functionName + " doesn't inherit from Metrics.");
            }
        } catch (NotFoundException e) {
            throw new IllegalArgumentException("Function " + functionName + " can't be found by javaassist.");
        }
        final String className = formatName(metricsName);

        // Check whether the metrics class is already defined or not
        try {
            CtClass existingMetric = classPool.get(METER_CLASS_PACKAGE + className);
            if (existingMetric.getSuperclass() != parentClass || type != meterPrototypes.get(metricsName)
                                                                                        .getScopeType()) {
                throw new IllegalArgumentException(
                    metricsName + " has been defined, but calculate function or/are scope type is/are different.");
            }
            log.info("Metric {} is already defined, so skip the metric creation.", metricsName);
            return;
        } catch (NotFoundException e) {
            // Not found — proceed with creation
        }

        CtClass metricsClass = classPool.makeClass(METER_CLASS_PACKAGE + className, parentClass);

        // Create empty constructor
        try {
            CtConstructor defaultConstructor = CtNewConstructor.make(
                "public " + className + "() {}", metricsClass);
            metricsClass.addConstructor(defaultConstructor);
        } catch (CannotCompileException e) {
            log.error("Can't add empty constructor in " + className + ".", e);
            throw new UnexpectedException(e.getMessage(), e);
        }

        // Generate `AcceptableValue<T> createNew()` method
        try {
            metricsClass.addMethod(CtNewMethod.make(
                ""
                    + "public org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue createNew() {"
                    + "    org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue meterVar = new " + METER_CLASS_PACKAGE + className + "();"
                    + "    ((org.apache.skywalking.oap.server.core.analysis.meter.Meter)meterVar).initMeta(\"" + metricsName + "\", " + type.getScopeId() + ");"
                    + "    return meterVar;"
                    + " }"
                , metricsClass));
        } catch (CannotCompileException e) {
            log.error("Can't generate createNew method for " + className + ".", e);
            throw new UnexpectedException(e.getMessage(), e);
        }

        // Write .class file to output directory instead of loading into JVM
        try {
            if (OUTPUT_DIRECTORY == null) {
                throw new UnexpectedException("OUTPUT_DIRECTORY not set for build-time MeterSystem");
            }
            metricsClass.writeFile(OUTPUT_DIRECTORY);
            meterPrototypes.put(metricsName, new MeterDefinition(type, null, dataType));
            exportedClasses.add(metricsName + "=" + METER_CLASS_PACKAGE + className);
            log.debug("Exported meter class: {}", metricsClass.getName());
        } catch (CannotCompileException | IOException e) {
            log.error("Can't write " + className + " to " + OUTPUT_DIRECTORY, e);
            throw new UnexpectedException(e.getMessage(), e);
        }
    }

    public <T> AcceptableValue<T> buildMetrics(String metricsName,
                                               Class<T> dataType) {
        throw new UnsupportedOperationException(
            "buildMetrics is not available at build time. metricsName=" + metricsName);
    }

    public void doStreamingCalculation(AcceptableValue acceptableValue) {
        throw new UnsupportedOperationException(
            "doStreamingCalculation is not available at build time");
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
