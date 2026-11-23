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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.config.v2.compiler.HierarchyRuleClassGenerator;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.dsl.DslYamlLineIndex;
import org.yaml.snakeyaml.Yaml;

import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.scanV2Classes;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeEscapedProperties;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;

/**
 * The Hierarchy pass: checks the hierarchy definition as Core does at boot, and compiles its matching rules.
 */
@Slf4j
final class HierarchyPass {
    private static final String HIERARCHY_RT_PACKAGE =
        "org.apache.skywalking.oap.server.core.config.v2.compiler.hierarchy.rule.rt.";

    /**
     * What Core's {@code HierarchyDefinitionService} checks at boot, before any rule file's own layers register: every
     * layer of {@code layer-levels} is registered, and every layer of {@code hierarchy} has a level above each of its
     * lower layers.
     */
    @SuppressWarnings("unchecked")
    static void checkHierarchy(String definitionFile) throws Exception {
        final Map<String, Object> definition = new Yaml().load(definitionText(definitionFile));
        final Map<String, Object> levels = section(definition, definitionFile, "layer-levels");
        for (final Map.Entry<String, Object> level : levels.entrySet()) {
            if (Layer.nameOf(level.getKey()) == Layer.UNDEFINED) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + level.getKey() + " is not a "
                    + "registered layer; declare it in layer-extensions.yml");
            }
        }
        final Map<String, Object> hierarchy = section(definition, definitionFile, "hierarchy");
        for (final Map.Entry<String, Object> upper : hierarchy.entrySet()) {
            final Object upperLevel = levels.get(upper.getKey());
            if (upperLevel == null) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + upper.getKey() + " is not defined");
            }
            // The hierarchy service reads the level of each layer here, upper or lower, as an integer.
            if (!(upperLevel instanceof Integer)) {
                throw new IllegalStateException(definitionFile + " layer-levels: " + upper.getKey()
                    + " is not an integer level: " + upperLevel);
            }
            if (!(upper.getValue() instanceof Map)) {
                throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey()
                    + " does not map its lower layers to matching rules");
            }
            final Map<String, Object> lowers = (Map<String, Object>) upper.getValue();
            for (final Map.Entry<String, Object> lower : lowers.entrySet()) {
                final Object lowerLevel = levels.get(lower.getKey());
                if (lowerLevel == null) {
                    throw new IllegalStateException(definitionFile + " layer-levels: " + lower.getKey()
                        + " is not defined");
                }
                if (!(lowerLevel instanceof Integer)) {
                    throw new IllegalStateException(definitionFile + " layer-levels: " + lower.getKey()
                        + " is not an integer level: " + lowerLevel);
                }
                // A matching rule's name, or none: upstream relates the two layers without automatic matching then.
                if (lower.getValue() != null && !(lower.getValue() instanceof String)) {
                    throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey() + "."
                        + lower.getKey() + " does not name a matching rule: " + lower.getValue());
                }
                if ((Integer) upperLevel <= (Integer) lowerLevel) {
                    throw new IllegalStateException(definitionFile + " hierarchy: " + upper.getKey()
                        + " layer-level should be greater than " + lower.getKey() + " layer-level");
                }
            }
        }
    }

    /**
     * Pre-compile hierarchy matching rules using v2 ANTLR4 + Javassist engine.
     *
     * Loads hierarchy-definition.yml, compiles each auto-matching-rule expression
     * via HierarchyRuleClassGenerator, and writes .class files to the output directory.
     */
    @SuppressWarnings("unchecked")
    static void compileHierarchy(String outputDir, String definitionFile) throws Exception {
        HierarchyRuleClassGenerator hierarchyGenerator = new HierarchyRuleClassGenerator();
        File hierarchyRtDir = new File(outputDir, HIERARCHY_RT_PACKAGE.replace('.', '/'));
        hierarchyRtDir.mkdirs();
        hierarchyGenerator.setClassOutputDir(hierarchyRtDir);

        final String yamlText = definitionText(definitionFile);
        Map<String, Object> yamlMap = new Yaml().load(yamlText);
        // Per-rule lines, as upstream CompiledHierarchyRuleProvider stamps them, so class
        // names ({file}_L{line}_{rule}) match the JVM distro.
        final Map<String, Integer> ruleLines =
            DslYamlLineIndex.keyLines(yamlText, "auto-matching-rules");
        final Properties hierarchyRules = new Properties();
        // An empty section is valid: the manifests are written empty, as there are no rules to load.
        Map<String, String> autoMatchingRules =
            (Map<String, String>) (Map<?, ?>) section(yamlMap, definitionFile, "auto-matching-rules");

        int count = 0;
        for (Map.Entry<String, String> entry : autoMatchingRules.entrySet()) {
            String ruleName = entry.getKey();
            String expression = entry.getValue();
            try {
                final Integer line = ruleLines.get(ruleName);
                hierarchyGenerator.setSourceRef(DslSourceRef.ofRule(
                    "hierarchy-definition.yml", line == null ? 0 : line));
                hierarchyGenerator.setClassNameHint(ruleName);
                final Object compiled = hierarchyGenerator.compile(ruleName, expression);
                GeneratedInstanceCheck.requireNoStateAfterConstruction(
                    compiled, Set.of(), "Hierarchy rule " + ruleName);
                hierarchyRules.setProperty(ruleName, compiled.getClass().getName());
                count++;
                log.debug("Hierarchy: compiled rule {} -> {}", ruleName, expression);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to compile hierarchy rule " + ruleName, e);
            }
        }

        // Write manifest
        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> hierarchyV2Classes = scanV2Classes(outputDir, HIERARCHY_RT_PACKAGE);
        writeManifest(metaInf.resolve("hierarchy-v2-classes.txt"), hierarchyV2Classes);
        // Rule name to class, escaped: a rule may be named anything upstream accepts
        writeEscapedProperties(metaInf.resolve("hierarchy-v2-rules.txt"), hierarchyRules);

        log.info("Hierarchy pre-compilation: {} rules, {} v2 classes",
            count, hierarchyV2Classes.size());
    }

    /**
     * A section upstream's HierarchyDefinitionService reads without a null check: a missing one would fail its boot.
     * An explicitly empty one is valid.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> section(final Map<String, Object> definition, final String definitionFile,
                                       final String key) {
        if (definition == null || !(definition.get(key) instanceof Map)) {
            throw new IllegalStateException(definitionFile + " has no " + key + " section");
        }
        return (Map<String, Object>) definition.get(key);
    }

    /**
     * @return the definition's text; upstream's hierarchy service fails the boot without the file
     */
    private static String definitionText(final String definitionFile) throws IOException {
        try (InputStream is = HierarchyPass.class.getClassLoader().getResourceAsStream(definitionFile)) {
            if (is == null) {
                throw new IllegalStateException(definitionFile + " is not on the classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
