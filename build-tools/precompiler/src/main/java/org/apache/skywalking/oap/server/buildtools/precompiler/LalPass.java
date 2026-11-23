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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALClassGenerator;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.LalExpression;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.log.analyzer.v2.provider.log.listener.LalStaticRuleResolver;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LalInputDebugRenderer;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;

import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.scanV2Classes;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.setRuleName;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.sha256Hex;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeConfigData;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeEscapedProperties;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;

/**
 * The LAL pass: compiles every LAL rule upstream's boot runs, with the input and output types upstream resolves for it.
 */
@Slf4j
final class LalPass {
    private static final String LAL_RT_PACKAGE =
        "org.apache.skywalking.oap.log.analyzer.v2.compiler.rt.";

    /**
     * Pre-compile every LAL rule file under {@code lalPath} with the v2 engine, exporting each class. Upstream's
     * {@code DSL.of} makes a generator per call with no class output, so this pass drives one generator with what
     * upstream's {@code LogFilterListener.Factory} passes it: the rule's {@link DslSourceRef}, its text, and the input
     * and output types the factory resolves ({@link LalStaticRuleResolver}).
     *
     * <p>{@code lal-v2-rules.txt} carries, per rule, the coordinates, the class, the effective input type and what the
     * class was compiled from; {@code lal-io-classes.txt} the {@code LALSourceTypeProvider} and {@code LALOutputBuilder}
     * implementations ServiceLoader instantiates; {@code lal-input-types.txt} every type a rule reads values from,
     * which the reflection metadata walks.
     */
    static void compileLAL(String outputDir, String lalPath) throws Exception {
        LALClassGenerator lalGenerator = new LALClassGenerator();
        File lalRtDir = new File(outputDir, LAL_RT_PACKAGE.replace('.', '/'));
        lalRtDir.mkdirs();
        lalGenerator.setClassOutputDir(lalRtDir);
        LalStaticRuleResolver boot = new LalStaticRuleResolver();

        // Enumerate all LAL YAML files from classpath
        File[] lalFiles = RuleTree.filesOf(lalPath);
        Map<String, File> lalFileMap = new LinkedHashMap<>();
        for (File f : lalFiles) {
            String name = f.getName();
            if (f.isFile() && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                lalFileMap.put(name.substring(0, name.lastIndexOf('.')), f);
            }
        }

        // Loaded a file at a time, so each file's configs are kept by its name, a file of an empty rule list too: its
        // layerDefinitions are registered when the runtime loads it, as upstream does for every listed file.
        Map<String, LALConfigs> byFile = new LinkedHashMap<>();
        for (Map.Entry<String, File> file : lalFileMap.entrySet()) {
            for (LALConfigs configs : LALConfigs.load(lalPath, List.of(file.getKey()))) {
                byFile.put(file.getValue().getName(), configs);
            }
        }
        // In the order one upstream load over every file returns them, as the log analyzer loads its lalFiles (all of
        // them): the order their rules run on a log, which the registry keeps and the boot returns them in
        Map<String, LALConfigs> configsByFile = new LinkedHashMap<>();
        for (LALConfigs configs : LALConfigs.load(lalPath, new ArrayList<>(lalFileMap.keySet()))) {
            // No stamped source names a file without rules: files of one content are interchangeable
            String file = configs.getRules().isEmpty()
                ? byFile.entrySet().stream()
                        .filter(e -> !configsByFile.containsKey(e.getKey())
                            && StateComparison.firstDifference(configs, e.getValue(), "configs") == null)
                        .map(Map.Entry::getKey).findFirst().orElse(null)
                : configs.getRules().get(0).getSourceName();
            if (file == null || !byFile.containsKey(file) || configsByFile.put(file, byFile.get(file)) != null) {
                throw new IllegalStateException("Upstream's LAL loader returned a file of " + lalPath + " the LAL "
                    + "pass did not load alone: " + (file == null ? configs : file));
            }
        }
        if (!configsByFile.keySet().equals(byFile.keySet())) {
            throw new IllegalStateException("Upstream's LAL loader returned the files " + configsByFile.keySet()
                + " of " + lalPath + " loading them together, " + byFile.keySet() + " one at a time");
        }

        int totalRules = 0;
        Properties lalRules = new Properties();
        Set<String> inputTypes = new TreeSet<>();
        final Set<List<String>> registered = new HashSet<>();
        // A rule without a name is compiled, as upstream compiles it: keys keep it apart from one named "null"
        final Set<List<String>> compiledFrom = new HashSet<>();
        for (Map.Entry<String, LALConfigs> file : configsByFile.entrySet()) {
            for (LALConfig rule : file.getValue().getRules()) {
                if (!checkRegistration(rule, registered)) {
                    continue;
                }
                // The DslSourceRef upstream's factory passes: its coordinates name the class ({yaml}_L{line}_{rule},
                // as the JVM OAP names it) and key it for the runtime DSL, an unresolved line included.
                final DslSourceRef sourceRef = DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo());
                try {
                    final Class<?> inputType = boot.inputType(rule);
                    final Class<?> outputType = boot.outputType(rule);
                    final String dslSha256 = sha256Hex(rule.getDsl().getBytes(StandardCharsets.UTF_8));
                    // The runtime DSL takes the class compiled at the rule's coordinates from the name, text and types
                    // upstream passes: two rules agreeing on all of them (one line, two layers) compile to the same class,
                    // the layer being no input of it, so both run instances of one class, as upstream runs two alike.
                    if (!compiledFrom.add(Arrays.asList(sourceRef.describeYaml(), rule.getName(), dslSha256,
                        typeName(inputType), typeName(outputType)))) {
                        log.info("LAL rule {} of layer {} at {} is compiled as the rule of its name, text and types "
                            + "on the same line", rule.getName(), rule.getLayer(), sourceRef.describeYaml());
                        continue;
                    }
                    lalGenerator.setSourceRef(sourceRef);
                    lalGenerator.setClassNameHint(rule.getName());
                    // The holder's content, shown inline by the debugger (upstream DSL.of does the same).
                    lalGenerator.setContent(rule.getDsl());
                    lalGenerator.setInputType(inputType);
                    lalGenerator.setOutputType(outputType);

                    LalExpression compiled = lalGenerator.compile(rule.getDsl());
                    GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled, Set.of(),
                        "LAL rule " + rule.getName() + " (" + sourceRef.describeYaml() + ")");
                    final Class<?> effectiveInputType = lalGenerator.getEffectiveInputType();
                    final int idx = totalRules++;
                    lalRules.setProperty("rule." + idx + ".source", sourceRef.getYamlFile());
                    // The file itself: two files can share a source, which stamps .yaml onto another extension.
                    lalRules.setProperty("rule." + idx + ".file", file.getKey());
                    lalRules.setProperty("rule." + idx + ".line", String.valueOf(sourceRef.getYamlLine()));
                    setRuleName(lalRules, "rule." + idx + ".", rule.getName());
                    lalRules.setProperty("rule." + idx + ".class", compiled.getClass().getName());
                    lalRules.setProperty("rule." + idx + ".inputType", typeName(effectiveInputType));
                    // What the class was compiled from, which the runtime DSL.of requires upstream to pass again.
                    lalRules.setProperty("rule." + idx + ".dslSha256", dslSha256);
                    lalRules.setProperty("rule." + idx + ".requestedInputType", typeName(inputType));
                    lalRules.setProperty("rule." + idx + ".requestedOutputType", typeName(outputType));
                    if (effectiveInputType != null) {
                        inputTypes.add(effectiveInputType.getName());
                    }
                    if (inputType != null) {
                        inputTypes.add(inputType.getName());
                    }
                    log.debug("LAL: compiled rule {} -> {}", rule.getName(),
                        compiled.getClass().getName());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to compile LAL rule " + rule.getName() + " ("
                        + sourceRef.describeYaml() + ")", e);
                }
            }
        }

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> lalV2Classes = scanV2Classes(outputDir, LAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("lal-v2-classes.txt"), lalV2Classes);
        writeEscapedProperties(metaInf.resolve("lal-v2-rules.txt"), lalRules);
        Set<String> ioClasses = new TreeSet<>();
        for (LALSourceTypeProvider provider : boot.sourceTypeProviders()) {
            ioClasses.add(provider.getClass().getName());
            // The layer's logs arrive as this type, whichever rule runs on them.
            inputTypes.add(provider.inputType().getName());
        }
        LalStaticRuleResolver.outputBuilders().forEach(builder -> ioClasses.add(builder.getName()));
        // The renderers JSON-print their input types whichever rules exist: a parser-only rule runs on every entry its
        // layer receives, of either Envoy protocol.
        for (LalInputDebugRenderer renderer : ServiceLoader.load(LalInputDebugRenderer.class)) {
            ioClasses.add(renderer.getClass().getName());
            inputTypes.add(renderer.inputType().getName());
        }
        writeManifest(metaInf.resolve("lal-io-classes.txt"), new ArrayList<>(ioClasses));
        // The log a rule runs on by default; its log.* and parsed.<field> fallbacks read its trace context and tags.
        inputTypes.add(LogData.class.getName());
        writeManifest(metaInf.resolve("lal-input-types.txt"), new ArrayList<>(inputTypes));

        log.info("LAL pre-compilation: {} rules, {} v2 expression classes",
            totalRules, lalV2Classes.size());

        serializeLALConfigData(outputDir, configsByFile);
    }

    private static String typeName(Class<?> type) {
        return type == null ? "" : type.getName();
    }

    /**
     * What upstream {@code LogFilterListener.Factory} refuses among the static rules at boot, which fails the build
     * instead: {@code fallback} off a {@code layer: auto} rule, two rules of one name in a layer (or among the auto
     * rules).
     *
     * @return false for a rule upstream skips at boot with a warning before it resolves or compiles anything
     *     ({@link LalStaticRuleResolver#skippedAtBoot}, every rule file's inline layers registered by now). Not
     *     compiled here either; its config stays in the registry, so the boot still warns.
     */
    static boolean checkRegistration(final LALConfig rule, final Set<List<String>> registered) {
        if (LalStaticRuleResolver.skippedAtBoot(rule)) {
            return false;
        }
        final boolean auto = LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer());
        final String at = "LAL rule " + rule.getName() + " of " + rule.getSourcePath();
        if (rule.isFallback() && !auto) {
            throw new IllegalStateException(at + " declares fallback: true, which only a layer: auto rule may");
        }
        // Upstream keys the rules of a layer by name in a map, where a rule without a name is one apart
        if (!registered.add(Arrays.asList(auto ? null : rule.getLayer(), rule.getName()))) {
            throw new IllegalStateException(at + ": " + (auto ? "two auto-layer rules" : "two rules of layer "
                + rule.getLayer()) + " are named " + rule.getName());
        }
        return true;
    }

    /**
     * Write the LAL configs upstream's loader returned as {@code lal.json}, the build intermediate the rule registry
     * is generated from.
     */
    private static void serializeLALConfigData(String outputDir,
                                               Map<String, LALConfigs> lalConfigMap) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        // Keyed by the file's own name (a stem would lose `.yml` and any dot in the name). The configs carry what
        // upstream LALConfigs.load stamped (sourceName/sourcePath/lineNo), which LogFilterListener builds the
        // DslSourceRef from at boot.
        writeConfigData(mapper, configDataDir.resolve("lal.json"), lalConfigMap,
            new TypeReference<LinkedHashMap<String, LALConfigs>>() {
            });
        log.info("Serialized {} LALConfigs entries from lal to config-data JSON", lalConfigMap.size());
    }
}
