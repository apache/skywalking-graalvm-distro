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

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.RuleSources;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.library.util.CollectionUtils.isEmpty;
import static org.apache.skywalking.oap.server.library.util.StringUtil.isNotBlank;

/**
 * GraalVM replacement for upstream LALConfigs.
 * Original: skywalking/oap-server/analyzer/log-analyzer/src/main/java/.../v2/provider/LALConfigs.java
 * Repackaged into log-analyzer-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Complete rewrite. Returns the rule files from the generated {@link CompiledRules} registry, which holds
 * what upstream's loader read from each lal/ file at build time, the coordinates it stamped included
 * (sourceName/sourcePath/lineNo, which LogFilterListener builds the DslSourceRef and the dsl-debugging RuleKey from),
 * instead of reading YAML through ResourceUtils.getPathFiles(); registers their inline layerDefinitions as upstream
 * does, and passes every listed file it reads, from the sources the image carries ({@link RuleSources}, a file yielding
 * no rules too), through upstream's RuleSetMerger, which records them for the rule catalog. Upstream's
 * {@code load(path, files, manager)}, which merges runtime-rule overrides, is left out: nothing calls it, and
 * runtime-rule is not shipped.
 * Why: The distro ships no lal/ directory; its rules are compiled at build time.
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
        checkArgument(isNotBlank(path), "path cannot be blank");
        // As upstream fails on a directory that does not exist; the image compiles lal/ only, so another holds no file
        if (!RuleCatalog.exists(path)) {
            throw new ModuleStartException("Failed to load LAL config rules",
                new FileNotFoundException("path not found: " + path));
        }
        final boolean lal = LAL_CATALOG.equals(path + "/");
        // Upstream's loader reads every listed file of its directory, here every listed file the build activated (each
        // staged one), and passes it through RuleSetMerger, recording it for the rule catalog, which requires its
        // carried source; then skips one that yields no rules (comments only, or no rules list), registering none of
        // its layers
        final List<String> activated = lal
            ? Arrays.stream(Objects.requireNonNull(CompiledRules.activation().get(RuleCatalog.LAL.setting().key()),
                "the rule registry has no activation for lalFiles").split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toList())
            : List.of();
        final List<String> read = activated.stream().filter(files::contains).collect(Collectors.toList());
        RuleSources.merge("lal", read, true, null);
        final List<LalFile> all = lal ? CompiledRules.lalFiles() : List.of();
        final List<LalFile> listed = all.stream().filter(f -> read.contains(getNameWithoutExtension(f.getName())))
                                        .collect(Collectors.toList());
        final List<LALConfigs> result = listed.stream()
            .map(f -> {
                final LALConfigs configs = f.getConfigs();
                registerInlineLayers(getNameWithoutExtension(f.getName()), configs);
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
