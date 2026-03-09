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

package org.apache.skywalking.oap.meter.analyzer.v2.dsl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.meter.analyzer.v2.compiler.MALClassGenerator;

/**
 * Build-time same-FQCN replacement of upstream v2 MAL DSL.
 *
 * <p>Delegates to the real {@link MALClassGenerator} for compilation and
 * additionally records the mapping from expression hash + metric name
 * to the generated class name. The precompiler reads {@link #COMPILE_MAP}
 * after all compilations and writes it to a manifest file for runtime use.
 *
 * <p>The manifest key is {@code sha256(expression).substring(0,16) + "|" + metricName}.
 * This avoids collisions when multiple YAML files (e.g., {@code otel-rules/vm.yaml}
 * and {@code telegraf-rules/vm.yaml}) define the same metric name with different
 * expressions — they produce different SHA-256 hashes.
 */
@Slf4j
public final class DSL {

    private static final MALClassGenerator GENERATOR = new MALClassGenerator();

    /**
     * Mapping recorded during compilation: {@code "exprHash|metricName" -> FQCN}.
     * Read by the precompiler after all MetricConvert instances have been created.
     */
    public static final Map<String, String> COMPILE_MAP = new ConcurrentHashMap<>();

    /**
     * Configure the GENERATOR to write .class files to the given directory.
     */
    public static void setClassOutputDir(final File dir) {
        GENERATOR.setClassOutputDir(dir);
    }

    public static Expression parse(final String metricName, final String expression) {
        return parse(metricName, expression, null);
    }

    public static Expression parse(final String metricName,
                                   final String expression,
                                   final String yamlSource) {
        try {
            GENERATOR.setYamlSource(yamlSource);
            final MalExpression malExpr = GENERATOR.compile(metricName, expression);

            // Record mapping for manifest generation using expression hash
            final String key = expressionKey(expression, metricName);
            COMPILE_MAP.put(key, malExpr.getClass().getName());

            return new Expression(metricName, expression, malExpr);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to compile MAL expression for metric: " + metricName
                    + ", expression: " + expression, e);
        }
    }

    public static String expressionKey(final String expression, final String metricName) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(expression.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString() + "|" + metricName;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
