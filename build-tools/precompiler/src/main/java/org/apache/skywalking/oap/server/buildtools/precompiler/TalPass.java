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
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.buildtools.common.AcceptedModules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.TalMetrics;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALClassGenerator;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALFunctionRegistry;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricShapes;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.TALSourceTypeProvider;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.source.TALFunctionProvider;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.yaml.snakeyaml.Yaml;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;

import static com.google.common.io.Files.getNameWithoutExtension;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.scanV2Classes;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.setRuleName;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.sha256Hex;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeConfigData;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeEscapedProperties;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;

/**
 * The TAL pass: compiles the TAL rules of the modules the image ships and creates their metrics on the MeterSystem MAL
 * compiled on.
 */
@Slf4j
final class TalPass {
    private static final String TAL_RT_PACKAGE =
        "org.apache.skywalking.oap.trace.analyzer.v2.compiler.rt.";

    static void registerLayers(final String talPath, final ModuleManager shipped) throws Exception {
        for (File f : RuleTree.filesOf(talPath)) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                TALConfigs.load(talPath, List.of(getNameWithoutExtension(f.getName())), shipped);
            }
        }
    }

    /**
     * Pre-compile every TAL (Trace Analysis Language) rule file under {@code talPath}, whatever rules the
     * operator wrote there, resolving each rule's input and layer as upstream
     * {@code SpanFilterListener.Factory.compile} does, against a function registry bound as the factory binds its
     * own, and naming its class from the same {@link DslSourceRef}.
     * Every file is loaded through upstream's loader before any rule compiles, as upstream's {@code loadRules} does, so
     * a layer one file declares is registered for the rules of every file. A rule that fails here would fail the boot,
     * so the build fails.
     *
     * <p>{@code tal-v2-rules.txt} carries, per rule, the coordinates, the class, the input type and alias, the
     * registry key of every function object the class holds, and the metric declarations the same-FQCN
     * {@code TalDSL} sets, claims and creates at runtime; the meter classes the declarations need are generated here
     * through {@code createMetrics} and listed in {@code tal-meter-classes.txt}. {@code tal-io-classes.txt} lists the
     * input types, providers and function objects, for reflection metadata.
     */
    static void compileTAL(String outputDir, MeterSystem meterSystem, String talPath, ModuleManager modules)
        throws Exception {
        File talRtDir = new File(outputDir, TAL_RT_PACKAGE.replace('.', '/'));
        talRtDir.mkdirs();

        Map<String, TALSourceTypeProvider> inputProviders = new LinkedHashMap<>();
        for (TALSourceTypeProvider provider : ServiceLoader.load(TALSourceTypeProvider.class)) {
            inputProviders.put(provider.alias(), provider);
        }
        // Discovered and bound in the factory's order (ServiceLoader's), which the registry keys depend on; no module
        // manager, so every module's functions are bound, as a file is skipped at runtime when its modules are not.
        TALFunctionRegistry registry = TALFunctionRegistry.discover();
        registry.bind(null, inputProviders.values());
        log.info("TAL: input providers {}, function modules {}", inputProviders.keySet(), registry.modules());

        // Each name talFiles can list, loaded through upstream's loader with the modules the image ships, which picks
        // the file of the name (or fails where it cannot), keyed by the name of the file it read. A file requiring a
        // module the image does not ship is never loaded at boot: it registers no layer, compiles no rule and is not
        // carried.
        Map<String, TALConfigs> configsByFile = new LinkedHashMap<>();
        for (String stem : talStems(talPath)) {
            List<TALConfigs> loaded = TALConfigs.load(talPath, List.of(stem), modules);
            if (!loaded.isEmpty()) {
                TALConfigs configs = loaded.get(0);
                configsByFile.put(configs.getRules().isEmpty() ? onlyTalFile(talPath, stem).getName()
                    : configs.getRules().get(0).getSourceName(), configs);
                continue;
            }
            // Skipped for its modules as upstream's loader decides it, on the requiredModules it bound (not
            // requiredModulesOf's own read of the key, which differs for a value of another shape)
            File file = onlyTalFile(talPath, stem);
            TALConfigs typed = talFileConfigs(file.toPath());
            if (!typed.requiredModulesLoaded(modules)) {
                log.info("TAL: {} requires the modules {}, which the image does not all ship; upstream's boot skips it",
                    stem, typed.getRequiredModules());
                continue;
            }
            // Upstream's loader reads a file without rules (passing it through RuleSetMerger), registers its
            // layerDefinitions and returns nothing for it: the registry carries the file, so the boot does the same.
            if (typed.getRules() == null) {
                configsByFile.put(file.getName(), typed);
            }
        }

        TALMetricShapes metricShapes = new TALMetricShapes();
        int meterClassesBefore = meterSystem.getExportedClasses().size();
        Properties talRules = new Properties();
        Set<String> ioClasses = new TreeSet<>();
        // What each recorded rule's class was compiled from, to the wiring recorded with it
        Map<List<String>, String> wiringByInputs = new HashMap<>();
        Map<String, Boolean> nativeByFile = new HashMap<>();
        Map<String, String> nativeRuleSources = new HashMap<>();
        int totalRules = 0;
        for (Map.Entry<String, TALConfigs> file : configsByFile.entrySet()) {
            TALConfigs configs = file.getValue();
            for (TALConfig rule : configs.getRules() == null ? List.<TALConfig>of() : configs.getRules()) {
                List<String> declaredInputs = rule.inputTypes();
                if (declaredInputs.size() != 1) {
                    throw new IllegalStateException("TAL rule " + rule.getName() + " declares "
                        + declaredInputs.size() + " input types; the span root can only be typed to one");
                }
                TALSourceTypeProvider input = resolveTalInput(rule, declaredInputs.get(0), inputProviders);
                Class<?> inputType = input.inputType();
                resolveTalLayer(rule);
                // Over every rule as upstream's factory sees it, before rules alike share a class
                checkTalActivation(file.getKey(), rule.getName(), input.alias(), nativeByFile, nativeRuleSources);

                DslSourceRef sourceRef = DslSourceRef.ofRule(rule.getSourcePath(), rule.getLineNo());
                String dslSha256 = sha256Hex(rule.getDsl().getBytes(StandardCharsets.UTF_8));
                // The runtime TalDSL takes the class recorded at the rule's coordinates for the name, text, input and
                // prefix upstream passes: a second rule agreeing on all of them (one line, two layers) runs that class,
                // as upstream runs its own alike class, only when the wiring recorded with it is its own too.
                List<String> inputs = Arrays.asList(sourceRef.describeYaml(), rule.getName(), dslSha256,
                    inputType.getName(), input.alias(), rule.getMetricPrefix() == null ? "" : rule.getMetricPrefix());
                String recordedWiring = wiringByInputs.get(inputs);
                TALClassGenerator generator = new TALClassGenerator();
                generator.setClassOutputDir(recordedWiring == null ? talRtDir : null);
                generator.setInputType(inputType);
                generator.setClassNameHint(rule.getName());
                generator.setSourceRef(sourceRef);
                generator.setMetricPrefix(rule.getMetricPrefix());
                generator.setContent(rule.getDsl());
                generator.setInputAlias(input.alias());
                generator.setFunctionRegistry(registry);
                TalExpression compiled = generator.compile(rule.getDsl());
                if (recordedWiring != null) {
                    String wiring = talWiring(talFunctionFields(rule, compiled, registry, inputProviders.keySet()),
                        generator.getMetricDeclarations());
                    if (!wiring.equals(recordedWiring)) {
                        throw new IllegalStateException("Two TAL rules named " + rule.getName() + " start at "
                            + sourceRef.describeYaml() + " with the same DSL text, input and metric prefix, but "
                            + "compile to classes wired differently, which the image cannot tell apart; give one of "
                            + "them a line of its own");
                    }
                    log.info("TAL rule {} of layer {} at {} runs the class of the rule of its name, text, input and "
                        + "prefix on the same line", rule.getName(), rule.getLayer(), sourceRef.describeYaml());
                    continue;
                }
                metricShapes.claim(sourceRef + "#" + rule.getName(), generator.getMetricDeclarations());
                List<String> created = meterSystem.recordCalls(false, () -> generator.createMetrics(meterSystem));
                // The runtime rebuilds each declaration from the fields recorded below: rebuild it so here, and a field
                // upstream adds would show as a difference instead of resetting to its default at boot.
                List<TALMetricDeclaration> rebuilt = new ArrayList<>();
                for (TALMetricDeclaration d : generator.getMetricDeclarations()) {
                    TALMetricDeclaration copy = new TALMetricDeclaration(d.fullName, d.function, d.scopeType,
                        d.declaredName, d.buckets, d.ranks, d.labelLimit);
                    String difference = StateComparison.firstDifference(d, copy, "TAL metric " + d.fullName);
                    if (difference != null) {
                        throw new IllegalStateException("TAL rule " + rule.getName() + ": the registry does not "
                            + "carry " + difference);
                    }
                    rebuilt.add(copy);
                }
                List<String> replayed = meterSystem.recordCalls(true, () -> TalMetrics.create(meterSystem, rebuilt));
                if (!created.equals(replayed)) {
                    throw new IllegalStateException("TAL rule " + rule.getName() + ": upstream createMetrics makes "
                        + created + ", but the runtime replays " + replayed + " from the recorded declarations; "
                        + "update TalMetrics");
                }

                // Keyed by the DslSourceRef the runtime TalDSL is given. Coordinates alone are not an identity: two
                // rules can share a line in flow-style YAML, so every input the class was compiled from is recorded,
                // and the runtime TalDSL requires all of them to match.
                String prefix = "rule." + totalRules++ + ".";
                talRules.setProperty(prefix + "source", sourceRef.getYamlFile());
                talRules.setProperty(prefix + "file", file.getKey());
                talRules.setProperty(prefix + "line", String.valueOf(sourceRef.getYamlLine()));
                setRuleName(talRules, prefix, rule.getName());
                talRules.setProperty(prefix + "class", compiled.getClass().getName());
                talRules.setProperty(prefix + "inputType", inputType.getName());
                talRules.setProperty(prefix + "inputAlias", input.alias());
                talRules.setProperty(prefix + "metricPrefix", rule.getMetricPrefix() == null ? "" : rule.getMetricPrefix());
                talRules.setProperty(prefix + "dslSha256", dslSha256);
                ioClasses.add(inputType.getName());
                List<String[]> functions = talFunctionFields(rule, compiled, registry, inputProviders.keySet());
                wiringByInputs.put(inputs, talWiring(functions, generator.getMetricDeclarations()));
                GeneratedInstanceCheck.requireNoStateAfterConstruction(compiled,
                    functions.stream().map(f -> f[0]).collect(Collectors.toSet()),
                    "TAL rule " + rule.getName() + " (" + sourceRef.describeYaml() + ")");
                for (int f = 0; f < functions.size(); f++) {
                    String functionPrefix = prefix + "function." + f + ".";
                    talRules.setProperty(functionPrefix + "field", functions.get(f)[0]);
                    talRules.setProperty(functionPrefix + "key", functions.get(f)[1]);
                    talRules.setProperty(functionPrefix + "type", functions.get(f)[2]);
                    ioClasses.add(functions.get(f)[2]);
                }
                List<TALMetricDeclaration> declarations = generator.getMetricDeclarations();
                for (int m = 0; m < declarations.size(); m++) {
                    TALMetricDeclaration d = declarations.get(m);
                    String metricPrefix = prefix + "metric." + m + ".";
                    talRules.setProperty(metricPrefix + "fullName", d.fullName);
                    talRules.setProperty(metricPrefix + "function", d.function);
                    talRules.setProperty(metricPrefix + "scopeType", d.scopeType);
                    talRules.setProperty(metricPrefix + "declaredName", d.declaredName);
                    talRules.setProperty(metricPrefix + "buckets", d.buckets.stream().map(String::valueOf)
                                                                     .collect(Collectors.joining(",")));
                    talRules.setProperty(metricPrefix + "ranks", d.ranks.stream().map(String::valueOf)
                                                                   .collect(Collectors.joining(",")));
                    talRules.setProperty(metricPrefix + "labelLimit", String.valueOf(d.labelLimit));
                }
                log.info("TAL: compiled rule {} ({}) -> {}, {} metrics", rule.getName(), sourceRef,
                    compiled.getClass().getName(), declarations.size());
            }
        }

        Path metaInf = Path.of(outputDir, "META-INF");
        List<String> talV2Classes = scanV2Classes(outputDir, TAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("tal-v2-classes.txt"), talV2Classes);
        writeEscapedProperties(metaInf.resolve("tal-v2-rules.txt"), talRules);
        List<String> exported = meterSystem.getExportedClasses();
        writeManifest(metaInf.resolve("tal-meter-classes.txt"),
            new ArrayList<>(exported.subList(meterClassesBefore, exported.size())));
        // The SPI implementations ServiceLoader instantiates, and every object the registry binds: it lists their
        // methods at boot, whether or not a rule calls one.
        for (TALSourceTypeProvider provider : inputProviders.values()) {
            ioClasses.add(provider.getClass().getName());
            provider.helperFunctions().forEach(helper -> ioClasses.add(helper.getClass().getName()));
        }
        for (TALFunctionProvider provider : ServiceLoader.load(TALFunctionProvider.class)) {
            ioClasses.add(provider.getClass().getName());
        }
        writeManifest(metaInf.resolve("tal-io-classes.txt"), new ArrayList<>(ioClasses));
        log.info("TAL pre-compilation: {} files, {} rules, {} v2 expression classes, {} meter classes",
            configsByFile.size(), totalRules, talV2Classes.size(), exported.size() - meterClassesBefore);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = metaInf.resolve("config-data");
        Files.createDirectories(configDataDir);
        writeConfigData(mapper, configDataDir.resolve("tal.json"), configsByFile,
            new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
    }

    /**
     * Every compiled TAL file is active, in {@code nativeTalFiles} (its rules are {@code sw-native}) or in
     * {@code talFiles}. Fails on a file that list would refuse, as upstream's boot does: a native file holding a rule of
     * another input, and two native rules of one name (upstream's factory keys them by name in a map, where a rule
     * without a name is one apart). The TAL pass compiles no file requiring a module the image does not ship.
     *
     * @param nativeByFile      whether each file seen so far holds native rules
     * @param nativeRuleSources the file of each native rule seen so far, by name
     */
    static void checkTalActivation(String file, String ruleName, String inputAlias, Map<String, Boolean> nativeByFile,
                                   Map<String, String> nativeRuleSources) {
        String source = RuleCatalog.TAL.getDirectory() + "/" + file;
        boolean isNative = TALFunctionProvider.SW_NATIVE_INPUT_TYPE.equals(inputAlias);
        Boolean fileIsNative = nativeByFile.putIfAbsent(source, isNative);
        if (fileIsNative != null && fileIsNative != isNative) {
            throw new IllegalStateException("TAL file " + source + " mixes " + TALFunctionProvider.SW_NATIVE_INPUT_TYPE
                + " rules with rules of another input; native rules and span rules of other inputs go in separate files");
        }
        if (isNative && nativeRuleSources.containsKey(ruleName)) {
            throw new IllegalStateException("The TAL native rules of " + nativeRuleSources.get(ruleName) + " and "
                + source + " are both named " + ruleName);
        }
        if (isNative) {
            nativeRuleSources.put(ruleName, source);
        }
    }

    /**
     * @return the function fields and metric declarations the runtime wires a TAL rule's class with, as text
     */
    private static String talWiring(List<String[]> functions, List<TALMetricDeclaration> declarations) {
        StringBuilder wiring = new StringBuilder();
        functions.forEach(f -> wiring.append(String.join("|", f)).append('\n'));
        for (TALMetricDeclaration d : declarations) {
            wiring.append(d.fullName).append('|').append(d.function).append('|').append(d.scopeType).append('|')
                  .append(d.declaredName).append('|').append(d.buckets).append('|').append(d.ranks).append('|')
                  .append(d.labelLimit).append('\n');
        }
        return wiring.toString();
    }

    /**
     * Same checks and messages as upstream {@code SpanFilterListener.Factory.resolveLayer}.
     */
    private static void resolveTalLayer(TALConfig rule) {
        if (rule.getLayer() == null || rule.getLayer().isEmpty()) {
            throw new IllegalStateException("TAL rule " + rule.getName() + " declares no layer");
        }
        try {
            Layer.valueOf(rule.getLayer());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TAL rule " + rule.getName() + " declares layer '" + rule.getLayer()
                + "' which is not registered; declare it under layerDefinitions or name a registered layer", e);
        }
    }

    private static TALSourceTypeProvider resolveTalInput(TALConfig rule, String declared,
                                                         Map<String, TALSourceTypeProvider> inputProviders) {
        TALSourceTypeProvider byAlias = inputProviders.get(declared);
        if (byAlias != null) {
            return byAlias;
        }
        for (TALSourceTypeProvider provider : inputProviders.values()) {
            if (provider.inputType().getName().equals(declared)) {
                return provider;
            }
        }
        throw new IllegalStateException("TAL rule " + rule.getName() + " declares inputType '" + declared
            + "' which no TALSourceTypeProvider claims; registered aliases are " + inputProviders.keySet());
    }

    /**
     * The function objects upstream's generator set on the compiled instance, every instance field the class declares
     * besides its static logger, each as {field, registry key, object class}. The key is the one the registry holds
     * that very object under, so the runtime TalDSL re-binds the same object from the factory's registry.
     */
    static List<String[]> talFunctionFields(TALConfig rule, TalExpression compiled, TALFunctionRegistry registry,
                                            Collection<String> inputAliases) throws IllegalAccessException {
        List<String[]> functions = new ArrayList<>();
        for (Field field : compiled.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // The generator's function slots are _fn<N>; every other field goes through GeneratedInstanceCheck.
            if (!field.getName().matches("_fn\\d+")) {
                continue;
            }
            field.setAccessible(true);
            Object owner = field.get(compiled);
            if (owner == null) {
                throw new IllegalStateException("TAL rule " + rule.getName() + ": the generator left function slot "
                    + field.getName() + " of " + compiled.getClass().getName() + " unset");
            }
            String byClass = owner.getClass().getName();
            List<String> candidates = new ArrayList<>();
            candidates.add(byClass);
            inputAliases.forEach(alias -> candidates.add(byClass + "/" + alias));
            List<String> keys = candidates.stream().filter(k -> registry.instance(k) == owner)
                                          .collect(Collectors.toList());
            if (keys.size() != 1) {
                throw new IllegalStateException("TAL rule " + rule.getName() + ": the " + byClass + " in field "
                    + field.getName() + " is held by the function registry under " + keys.size() + " of the keys "
                    + candidates);
            }
            functions.add(new String[] {field.getName(), keys.get(0), byClass});
        }
        return functions;
    }

    /**
     * @return the names {@code talFiles} can list a file under {@code talPath} by: its name without the extension
     */
    static Set<String> talStems(final String talPath) throws IOException {
        Set<String> stems = new TreeSet<>();
        for (File f : RuleTree.filesOf(talPath)) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                stems.add(getNameWithoutExtension(f.getName()));
            }
        }
        return stems;
    }

    /**
     * The file of a name upstream's loader returned no rule of, so no stamped source name says which file it read.
     */
    private static File onlyTalFile(final String talPath, final String stem) throws IOException {
        List<File> files = Arrays.stream(RuleTree.filesOf(talPath))
                                 .filter(f -> f.isFile() && getNameWithoutExtension(f.getName()).equals(stem))
                                 .collect(Collectors.toList());
        if (files.size() != 1) {
            throw new IllegalStateException("TAL rule files " + files.stream().map(File::getName).sorted()
                .collect(Collectors.toList()) + " under " + talPath + " all name the rule file " + stem
                + ", which holds no rule, so upstream's loader does not tell which one it reads; keep one");
        }
        return files.get(0);
    }

    /**
     * @return the configs of a file as upstream's loader binds them (empty for a document of comments only)
     */
    static TALConfigs talFileConfigs(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            TALConfigs configs = new Yaml().loadAs(reader, TALConfigs.class);
            // A document of comments only binds nothing, as upstream reads it
            return configs == null ? new TALConfigs() : configs;
        }
    }

    /**
     * @return a module manager that has exactly the modules the image ships ({@link AcceptedModules#shippedModuleNames})
     */
    static ModuleManager shippedModules() {
        return modules(AcceptedModules.shippedModuleNames());
    }

    /**
     * @return a module manager that has exactly these modules, asked as upstream's hash-map-backed one is: a null name
     *     is no module it has
     */
    static ModuleManager modules(Set<String> names) {
        return new ModuleManager("precompiler") {
            @Override
            public boolean has(String moduleName) {
                return moduleName != null && names.contains(moduleName);
            }
        };
    }
}
