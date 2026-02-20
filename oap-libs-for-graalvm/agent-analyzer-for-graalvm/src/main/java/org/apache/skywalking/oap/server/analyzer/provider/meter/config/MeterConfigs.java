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

package org.apache.skywalking.oap.server.analyzer.provider.meter.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;

/**
 * GraalVM replacement for upstream MeterConfigs.
 * Original: skywalking/oap-server/analyzer/agent-analyzer/src/main/java/.../meter/config/MeterConfigs.java
 * Repackaged into agent-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled meter config data from JSON manifests
 * (META-INF/config-data/{path}.json) instead of filesystem YAML files via ResourceUtils.getPathFiles().
 * Why: The distro intentionally excludes raw YAML config directories (meter-analyzer-config, etc.)
 * — their Groovy expressions are pre-compiled at build time. Config data (metric prefixes, rule names)
 * is serialized as JSON by the precompiler for runtime wiring.
 */
@Slf4j
public class MeterConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Load all configs from pre-compiled JSON manifest.
     */
    public static List<MeterConfig> loadConfig(String path, List<String> fileNames) throws ModuleStartException {
        if (CollectionUtils.isEmpty(fileNames)) {
            return Collections.emptyList();
        }

        log.info("Loading meter configs from pre-compiled distro ({})", path);

        String resourcePath = "META-INF/config-data/" + path + ".json";
        try (InputStream is = MeterConfigs.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new ModuleStartException(
                    "Pre-compiled config data not found: " + resourcePath
                    + ". Ensure the precompiler has been run.");
            }

            Map<String, MeterConfig> allConfigs = MAPPER.readValue(
                is, new TypeReference<Map<String, MeterConfig>>() { });

            List<MeterConfig> result = allConfigs.entrySet().stream()
                .filter(e -> fileNames.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

            log.info("Loaded {} pre-compiled meter configs from {} (filtered from {} available)",
                result.size(), path, allConfigs.size());
            return result;
        } catch (ModuleStartException e) {
            throw e;
        } catch (Exception e) {
            throw new ModuleStartException("Load pre-compiled meter configs failed", e);
        }
    }
}
