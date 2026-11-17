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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.library.util.CollectionUtils.isEmpty;

/**
 * GraalVM replacement for upstream LALConfigs.
 * Original: skywalking/oap-server/analyzer/log-analyzer/src/main/java/.../v2/provider/LALConfigs.java
 * Repackaged into log-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Returns the rule files from the generated {@link CompiledRules} registry, which holds
 * what upstream's loader read from each lal/ file at build time, instead of reading YAML through
 * ResourceUtils.getPathFiles(); registers their inline layerDefinitions as upstream does.
 * Why: The distro ships no lal/ directory; its rules are compiled at build time. sourceName/sourcePath are
 * re-stamped here exactly as upstream does, since LogFilterListener builds the DslSourceRef (and the
 * dsl-debugging RuleKey) from them.
 */
@Data
@Slf4j
public class LALConfigs {
    private List<LALConfig> rules;
    private List<LayerDefinition> layerDefinitions;

    // Must match upstream: LALClassGenerator strips this catalog prefix when naming classes.
    public static final String LAL_CATALOG = "lal/";

    public static void stampSource(final LALConfig config, final String fileName) {
        final String canonical = fileName.endsWith(".yaml") || fileName.endsWith(".yml")
            ? fileName : fileName + ".yaml";
        config.setSourceName(canonical);
        config.setSourcePath(LAL_CATALOG + canonical);
    }

    public static List<LALConfigs> load(final String path, final List<String> files) throws Exception {
        if (isEmpty(files)) {
            return Collections.emptyList();
        }
        // The precompiler compiles lal/ only.
        if (!LAL_CATALOG.equals(path + "/")) {
            throw new ModuleStartException("No pre-compiled LAL rules under " + path + "; this image compiles lal/");
        }
        final List<LalFile> all = CompiledRules.lalFiles();
        final List<LALConfigs> result = all.stream()
            .filter(f -> files.contains(getNameWithoutExtension(f.getName())))
            .map(f -> {
                final LALConfigs configs = f.getConfigs();
                registerInlineLayers(getNameWithoutExtension(f.getName()), configs);
                configs.getRules().forEach(c -> stampSource(c, f.getName()));
                return configs;
            })
            .collect(Collectors.toList());

        log.info("Loaded {} pre-compiled LAL configs from {} (filtered from {} available)",
            result.size(), path, all.size());
        return result;
    }

    // Same registration and message as upstream's registerInlineLayers.
    private static void registerInlineLayers(final String ruleName, final LALConfigs configs) {
        final List<LayerDefinition> defs = configs.getLayerDefinitions();
        if (defs == null || defs.isEmpty()) {
            return;
        }
        for (final LayerDefinition def : defs) {
            try {
                def.register();
            } catch (RuntimeException e) {
                throw new UnexpectedException(
                    "LAL rule " + ruleName + " layerDefinitions entry rejected: " + def, e);
            }
        }
    }
}
