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

package org.apache.skywalking.oap.meter.analyzer.dsl;

import com.google.common.collect.ImmutableList;
import groovy.lang.Binding;
import groovy.lang.GString;
import groovy.lang.GroovyShell;
import groovy.util.DelegatingScript;
import java.io.File;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;
import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

/**
 * Build-time same-FQCN replacement for upstream MAL DSL.
 * Adds targetDirectory to CompilerConfiguration so Groovy writes .class files
 * during compilation. Tracks metricName → scriptClassName for manifest generation.
 */
@Slf4j
public final class DSL {
    private static File TARGET_DIRECTORY;
    private static final Map<String, String> SCRIPT_REGISTRY = new LinkedHashMap<>();
    private static final Map<String, Integer> METRIC_OCCURRENCE = new HashMap<>();
    private static final Map<String, String> EXPRESSION_HASHES = new LinkedHashMap<>();

    public static void setTargetDirectory(File dir) {
        TARGET_DIRECTORY = dir;
    }

    public static Map<String, String> getScriptRegistry() {
        return Collections.unmodifiableMap(SCRIPT_REGISTRY);
    }

    public static Map<String, String> getExpressionHashes() {
        return Collections.unmodifiableMap(EXPRESSION_HASHES);
    }

    public static void reset() {
        TARGET_DIRECTORY = null;
        SCRIPT_REGISTRY.clear();
        METRIC_OCCURRENCE.clear();
        EXPRESSION_HASHES.clear();
    }

    /**
     * Parse string literal to Expression object, which can be reused.
     * Same CompilerConfiguration as upstream, plus targetDirectory for bytecode capture.
     */
    @SuppressWarnings("rawtypes")
    public static Expression parse(final String metricName, final String expression) {
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.setScriptBaseClass(DelegatingScript.class.getName());
        ImportCustomizer icz = new ImportCustomizer();
        icz.addImport("K8sRetagType", K8sRetagType.class.getName());
        icz.addImport("DetectPoint", DetectPoint.class.getName());
        icz.addImport("Layer", Layer.class.getName());
        icz.addImport("ProcessRegistry", ProcessRegistry.class.getName());
        cc.addCompilationCustomizers(icz);

        final SecureASTCustomizer secureASTCustomizer = new SecureASTCustomizer();
        secureASTCustomizer.setDisallowedStatements(
            ImmutableList.<Class<? extends Statement>>builder()
                         .add(WhileStatement.class)
                         .add(DoWhileStatement.class)
                         .add(ForStatement.class)
                         .build());
        secureASTCustomizer.setAllowedReceiversClasses(
            ImmutableList.<Class>builder()
                         .add(Object.class)
                         .add(Map.class)
                         .add(List.class)
                         .add(Array.class)
                         .add(K8sRetagType.class)
                         .add(DetectPoint.class)
                         .add(Layer.class)
                         .add(ProcessRegistry.class)
                         .add(GString.class)
                         .add(String.class)
                         .build());
        cc.addCompilationCustomizers(secureASTCustomizer);

        if (TARGET_DIRECTORY != null) {
            cc.setTargetDirectory(TARGET_DIRECTORY);
        }

        GroovyShell sh = new GroovyShell(new Binding(), cc);
        String scriptName;
        if (metricName == null) {
            int n = METRIC_OCCURRENCE.merge("__init__", 1, Integer::sum);
            scriptName = "InitScript" + (n - 1);
        } else {
            int occurrence = METRIC_OCCURRENCE.merge(metricName, 1, Integer::sum);
            if (occurrence == 1) {
                scriptName = metricName;
            } else {
                // Combination pattern: multiple expressions for the same metric.
                // Suffix with occurrence index for unique class names.
                scriptName = metricName + "_" + (occurrence - 1);
            }
        }
        DelegatingScript script = (DelegatingScript) sh.parse(expression, scriptName);

        if (metricName != null && TARGET_DIRECTORY != null) {
            SCRIPT_REGISTRY.put(scriptName, script.getClass().getName());
            EXPRESSION_HASHES.put(scriptName, sha256(expression));
            log.debug("Compiled MAL script: {} -> {}", scriptName, script.getClass().getName());
        }

        return new Expression(metricName, expression, script);
    }

    private static String sha256(final String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
