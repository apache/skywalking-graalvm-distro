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

import groovy.util.DelegatingScript;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * GraalVM runtime replacement for upstream MAL DSL.
 * Same FQCN — shadows the upstream class via Maven classpath precedence.
 *
 * Instead of compiling Groovy at runtime, loads pre-compiled script classes
 * from the classpath using a build-time manifest.
 */
@Slf4j
public final class DSL {
    private static final String MANIFEST_PATH = "META-INF/mal-groovy-scripts.txt";
    private static volatile Map<String, String> SCRIPT_MAP;

    public static Expression parse(final String metricName, final String expression) {
        if (metricName == null) {
            throw new UnsupportedOperationException(
                "Init expressions (metricName=null) are not supported in GraalVM mode. "
                    + "All init expressions must be pre-compiled at build time.");
        }

        Map<String, String> scriptMap = loadManifest();
        String className = scriptMap.get(metricName);
        if (className == null) {
            throw new IllegalStateException(
                "Pre-compiled MAL script not found for metric: " + metricName
                    + ". Available: " + scriptMap.keySet());
        }

        try {
            Class<?> scriptClass = Class.forName(className);
            DelegatingScript script = (DelegatingScript) scriptClass.getDeclaredConstructor().newInstance();
            log.debug("Loaded pre-compiled MAL script: {} -> {}", metricName, className);
            return new Expression(metricName, expression, script);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Pre-compiled MAL script class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate pre-compiled MAL script: " + className, e);
        }
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
                    log.warn("MAL script manifest not found: {}", MANIFEST_PATH);
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
                throw new IllegalStateException("Failed to load MAL script manifest", e);
            }
            log.info("Loaded {} pre-compiled MAL scripts from manifest", map.size());
            SCRIPT_MAP = map;
            return map;
        }
    }
}
