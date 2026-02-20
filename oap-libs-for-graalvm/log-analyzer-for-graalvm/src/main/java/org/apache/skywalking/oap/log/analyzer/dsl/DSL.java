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

import groovy.util.DelegatingScript;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.dsl.spec.filter.FilterSpec;
import org.apache.skywalking.oap.log.analyzer.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

/**
 * GraalVM replacement for upstream LAL DSL.
 * Original: skywalking/oap-server/analyzer/log-analyzer/src/main/java/.../dsl/DSL.java
 * Repackaged into log-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled LAL @CompileStatic Groovy script classes from
 * META-INF/lal-scripts-by-hash.txt manifest (keyed by SHA-256 hash) instead of GroovyShell runtime compilation.
 * Why: Groovy runtime compilation is incompatible with GraalVM native image.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DSL {
    private static final String MANIFEST_PATH = "META-INF/lal-scripts-by-hash.txt";
    private static volatile Map<String, String> SCRIPT_MAP;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    private final DelegatingScript script;
    private final FilterSpec filterSpec;

    public static DSL of(final ModuleManager moduleManager,
                         final LogAnalyzerModuleConfig config,
                         final String dsl) throws ModuleStartException {
        Map<String, String> scriptMap = loadManifest();
        String dslHash = sha256(dsl);
        String className = scriptMap.get(dslHash);
        if (className == null) {
            throw new ModuleStartException(
                "Pre-compiled LAL script not found for DSL hash: " + dslHash
                    + ". Available: " + scriptMap.size() + " scripts.");
        }

        try {
            Class<?> scriptClass = Class.forName(className);
            DelegatingScript script = (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();
            FilterSpec filterSpec = new FilterSpec(moduleManager, config);
            script.setDelegate(filterSpec);
            int count = LOADED_COUNT.incrementAndGet();
            log.debug("Loaded pre-compiled LAL script [{}/{}]: {}", count, scriptMap.size(), className);
            return new DSL(script, filterSpec);
        } catch (ClassNotFoundException e) {
            throw new ModuleStartException(
                "Pre-compiled LAL script class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new ModuleStartException(
                "Failed to instantiate pre-compiled LAL script: " + className, e);
        }
    }

    public void bind(final Binding binding) {
        this.filterSpec.bind(binding);
    }

    public void evaluate() {
        script.run();
    }

    private static Map<String, String> loadManifest() {
        if (SCRIPT_MAP != null) {
            return SCRIPT_MAP;
        }
        synchronized (DSL.class) {
            if (SCRIPT_MAP != null) {
                return SCRIPT_MAP;
            }
            Map<String, String> map = new HashMap<>();
            try (InputStream is = DSL.class.getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
                if (is == null) {
                    log.warn("LAL script manifest not found: {}", MANIFEST_PATH);
                    SCRIPT_MAP = map;
                    return map;
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
                        if (parts.length == 2) {
                            map.put(parts[0], parts[1]);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load LAL script manifest", e);
            }
            log.info("Loaded {} pre-compiled LAL scripts from manifest", map.size());
            SCRIPT_MAP = map;
            return map;
        }
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
