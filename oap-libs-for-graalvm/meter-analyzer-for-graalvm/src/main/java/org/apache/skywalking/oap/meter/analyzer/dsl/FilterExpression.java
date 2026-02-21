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

import groovy.lang.Closure;
import groovy.lang.Script;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * GraalVM replacement for upstream FilterExpression.
 * Original: skywalking/oap-server/analyzer/meter-analyzer/src/main/java/.../dsl/FilterExpression.java
 * Repackaged into meter-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled Groovy filter closure classes from
 * META-INF/mal-filter-scripts.properties manifest instead of GroovyShell runtime compilation.
 * Why: Groovy runtime compilation is incompatible with GraalVM native image.
 */
@Slf4j
@ToString(of = {"literal"})
public class FilterExpression {
    private static final String MANIFEST_PATH = "META-INF/mal-filter-scripts.properties";
    private static volatile Map<String, String> FILTER_MAP;
    private static final AtomicInteger LOADED_COUNT = new AtomicInteger();

    private final String literal;
    private final Closure<Boolean> filterClosure;

    @SuppressWarnings("unchecked")
    public FilterExpression(final String literal) {
        this.literal = literal;

        Map<String, String> filterMap = loadManifest();
        String className = filterMap.get(literal);
        if (className == null) {
            throw new IllegalStateException(
                "Pre-compiled filter script not found for: " + literal
                    + ". Available filters: " + filterMap.size());
        }

        try {
            Class<?> scriptClass = Class.forName(className);
            Script filterScript = (Script) scriptClass.getDeclaredConstructor().newInstance();
            filterClosure = (Closure<Boolean>) filterScript.run();
            int count = LOADED_COUNT.incrementAndGet();
            log.debug("Loaded pre-compiled filter script [{}/{}]: {}", count, filterMap.size(), literal);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "Pre-compiled filter script class not found: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate pre-compiled filter script: " + className, e);
        }
    }

    public Map<String, SampleFamily> filter(final Map<String, SampleFamily> sampleFamilies) {
        try {
            Map<String, SampleFamily> result = new HashMap<>();
            for (Map.Entry<String, SampleFamily> entry : sampleFamilies.entrySet()) {
                SampleFamily afterFilter = entry.getValue().filter(tags -> filterClosure.call(tags));
                if (!Objects.equals(afterFilter, SampleFamily.EMPTY)) {
                    result.put(entry.getKey(), afterFilter);
                }
            }
            return result;
        } catch (Throwable t) {
            log.error("failed to run \"{}\"", literal, t);
        }
        return sampleFamilies;
    }

    private static Map<String, String> loadManifest() {
        if (FILTER_MAP != null) {
            return FILTER_MAP;
        }
        synchronized (FilterExpression.class) {
            if (FILTER_MAP != null) {
                return FILTER_MAP;
            }
            Map<String, String> map = new HashMap<>();
            try (InputStream is = FilterExpression.class.getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
                if (is == null) {
                    log.warn("Filter script manifest not found: {}", MANIFEST_PATH);
                    FILTER_MAP = map;
                    return map;
                }
                Properties props = new Properties();
                props.load(is);
                props.forEach((k, v) -> map.put((String) k, (String) v));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load filter script manifest", e);
            }
            log.info("Loaded {} pre-compiled filter scripts from manifest", map.size());
            FILTER_MAP = map;
            return map;
        }
    }
}
