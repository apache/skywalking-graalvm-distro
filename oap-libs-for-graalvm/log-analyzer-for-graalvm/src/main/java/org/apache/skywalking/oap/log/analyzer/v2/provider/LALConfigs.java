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

package org.apache.skywalking.oap.log.analyzer.v2.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.library.util.CollectionUtils.isEmpty;

/**
 * GraalVM replacement for upstream LALConfigs.
 * Original: skywalking/oap-server/analyzer/log-analyzer/src/main/java/.../v2/provider/LALConfigs.java
 * Repackaged into log-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Loads pre-compiled LAL config data from JSON manifest
 * (META-INF/config-data/lal.json) instead of filesystem YAML files via ResourceUtils.getPathFiles().
 * Why: The distro intentionally excludes raw YAML config directories (lal/)
 * — their DSL expressions are pre-compiled at build time. Config data is serialized as JSON
 * by the precompiler for runtime wiring.
 */
@Data
@Slf4j
public class LALConfigs {
    private List<LALConfig> rules;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static List<LALConfigs> load(final String path, final List<String> files) throws Exception {
        if (isEmpty(files)) {
            return Collections.emptyList();
        }

        log.info("Loading LAL configs from pre-compiled distro ({})", path);

        String resourcePath = "META-INF/config-data/" + path + ".json";
        try (InputStream is = LALConfigs.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new ModuleStartException(
                    "Pre-compiled LAL config data not found: " + resourcePath
                    + ". Ensure the precompiler has been run.");
            }

            Map<String, LALConfigs> allConfigs = MAPPER.readValue(
                is, new TypeReference<Map<String, LALConfigs>>() { });

            List<LALConfigs> result = allConfigs.entrySet().stream()
                .filter(e -> files.contains(getNameWithoutExtension(e.getKey())))
                .map(e -> {
                    LALConfigs configs = e.getValue();
                    if (configs != null && configs.getRules() != null) {
                        configs.getRules().forEach(c -> c.setSourceName(e.getKey()));
                    }
                    return configs;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            log.info("Loaded {} pre-compiled LAL configs from {} (filtered from {} available)",
                result.size(), path, allConfigs.size());
            return result;
        }
    }
}
