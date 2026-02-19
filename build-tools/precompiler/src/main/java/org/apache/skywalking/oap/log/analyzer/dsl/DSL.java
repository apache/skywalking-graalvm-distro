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

package org.apache.skywalking.oap.log.analyzer.dsl;

import com.google.common.collect.ImmutableList;
import groovy.lang.GString;
import groovy.lang.GroovyShell;
import groovy.transform.CompileStatic;
import groovy.util.DelegatingScript;
import java.io.File;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.dsl.spec.LALDelegatingScript;
import org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

/**
 * Build-time same-FQCN replacement for upstream LAL DSL.
 * Adds targetDirectory to CompilerConfiguration so Groovy writes .class files
 * for LAL scripts. Tracks both scriptName → className and dslHash → className
 * for manifest generation (dslHash is needed because upstream DSL.of() only
 * receives the DSL string, not the rule name).
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DSL {
    private static File TARGET_DIRECTORY;
    private static final Map<String, String> SCRIPT_REGISTRY = new LinkedHashMap<>();
    private static final Map<String, String> DSL_HASH_REGISTRY = new LinkedHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final DelegatingScript script;

    public static void setTargetDirectory(File dir) {
        TARGET_DIRECTORY = dir;
    }

    public static Map<String, String> getScriptRegistry() {
        return Collections.unmodifiableMap(SCRIPT_REGISTRY);
    }

    public static Map<String, String> getDslHashRegistry() {
        return Collections.unmodifiableMap(DSL_HASH_REGISTRY);
    }

    public static void reset() {
        TARGET_DIRECTORY = null;
        SCRIPT_REGISTRY.clear();
        DSL_HASH_REGISTRY.clear();
        COUNTER.set(0);
    }

    @SuppressWarnings("rawtypes")
    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl) throws ModuleStartException {
        String scriptName = "LALScript_" + COUNTER.getAndIncrement();
        return compileInternal(scriptName, dsl);
    }

    public static DSL compile(final String scriptName, final String dsl) {
        return compileInternal(scriptName, dsl);
    }

    @SuppressWarnings("rawtypes")
    private static DSL compileInternal(final String scriptName, final String dsl) {
        final CompilerConfiguration cc = new CompilerConfiguration();
        final ASTTransformationCustomizer customizer =
            new ASTTransformationCustomizer(
                singletonMap(
                    "extensions",
                    singletonList(LALPrecompiledExtension.class.getName())
                ),
                CompileStatic.class
            );
        cc.addCompilationCustomizers(customizer);
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
                         .add(GString.class)
                         .add(String.class)
                         .add(ProcessRegistry.class)
                         .build());
        cc.addCompilationCustomizers(secureASTCustomizer);
        cc.setScriptBaseClass(LALDelegatingScript.class.getName());

        ImportCustomizer icz = new ImportCustomizer();
        icz.addImport("ProcessRegistry", ProcessRegistry.class.getName());
        cc.addCompilationCustomizers(icz);

        if (TARGET_DIRECTORY != null) {
            cc.setTargetDirectory(TARGET_DIRECTORY);
        }

        final GroovyShell sh = new GroovyShell(cc);
        final DelegatingScript script = (DelegatingScript) sh.parse(dsl, scriptName);

        if (TARGET_DIRECTORY != null) {
            String className = script.getClass().getName();
            SCRIPT_REGISTRY.put(scriptName, className);
            DSL_HASH_REGISTRY.putIfAbsent(sha256(dsl), className);
            log.debug("Compiled LAL script: {} -> {}", scriptName, className);
        }

        return new DSL(script);
    }

    public void bind(final Binding binding) {
        throw new UnsupportedOperationException("bind is not available at build time");
    }

    public void evaluate() {
        throw new UnsupportedOperationException("evaluate is not available at build time");
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
