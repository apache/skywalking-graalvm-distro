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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricRuleConfig;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.core.dsl.DslSourceRef;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.graalvm.rules.ZabbixHostPatterns;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.util.PropertyPlaceholderHelper;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.span.listener.SpanFilterListener;
import org.yaml.snakeyaml.Yaml;

/**
 * Generates the static rule registry the runtime replacements read instead of rule YAML, JSON or manifests: a class
 * per rule file that holds, as code, what upstream's loader read from the file and constructs the classes compiled
 * from it, and the {@code CompiledRules} index over them. Its input is what the engine passes wrote under
 * {@code META-INF} (build intermediates the generated jar leaves out) and the rule files upstream still reads as data
 * (hierarchy, layer extensions, Zabbix). The sources are compiled in process into the output directory, next to the
 * hand-written holder types of {@code org.apache.skywalking.oap.server.graalvm.rules}.
 */
@Slf4j
final class RegistryGenerator {
    static final String PACKAGE = "org.apache.skywalking.oap.server.graalvm.rules";
    private static final String NATIVE_ALIAS = "sw-native";
    private static final String DISPATCHER_PACKAGE = "org.apache.skywalking.oap.server.core.source.oal.rt.dispatcher.";
    private static final String ACCEPTABLE_VALUE =
        "org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue";
    // Below the class-file limit of 65535 UTF-8 bytes per string constant, at up to 6 bytes per escaped char.
    private static final int MAX_CONSTANT_CHARS = 8000;
    // Statements per generated method, far below the 64KB method-code limit.
    private static final int STATEMENTS_PER_METHOD = 300;

    private final Path outputDir;
    private final Path metaInf;
    private final Path sourceDir;
    private final ClassLoader classLoader = RegistryGenerator.class.getClassLoader();
    private ClassLoader generatedClasses;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Set<String> classNames = new HashSet<>();
    private final List<Path> sources = new ArrayList<>();
    /**
     * The rule-selecting settings, {@code module.provider.field}, each naming every rule file compiled for it: every
     * compiled rule file is active, the setting is filled with them at boot, and set by hand is refused.
     */
    private final Map<String, List<String>> activation = new LinkedHashMap<>();
    /** The bundled OAL scripts the rules overlay excluded. */
    private final List<String> oalExcluded = new ArrayList<>();

    private final StagedRuleNames stagedRuleNames;

    /**
     * The staged rule files of a catalog, by name ({@link RuleTree#ruleNames}).
     */
    interface StagedRuleNames {
        List<String> of(String catalog) throws IOException;
    }

    RegistryGenerator(final Path outputDir, final Path sourceDir) {
        this(outputDir, sourceDir, RuleTree::ruleNames);
    }

    /**
     * @param stagedRuleNames the staged rule files of a catalog, by name, every one of which is active
     */
    RegistryGenerator(final Path outputDir, final Path sourceDir, final StagedRuleNames stagedRuleNames) {
        this.outputDir = outputDir;
        this.metaInf = outputDir.resolve("META-INF");
        this.sourceDir = sourceDir;
        this.stagedRuleNames = stagedRuleNames;
        // Every setting gets its list, an empty one when the overlay left a catalog no file, as upstream accepts it
        for (final RuleCatalog.Setting setting : RuleCatalog.allSettings()) {
            activation.put(setting.key(), new ArrayList<>());
        }
    }

    static void generate(final Path outputDir, final Path sourceDir) throws Exception {
        final RegistryGenerator generator = new RegistryGenerator(outputDir, sourceDir);
        final Path packageDir = sourceDir.resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(packageDir);
        try (Stream<Path> stale = Files.list(packageDir)) {
            for (final Path p : stale.collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
        final MalCompiled malCompiled = generator.new MalCompiled();
        final List<String> malClasses = generator.mal(malCompiled);
        final List<String> lalClasses = generator.lal();
        final List<String> talClasses = generator.tal();
        final List<String> zabbixClasses = generator.zabbix(malCompiled);
        malCompiled.requireAllWritten();
        final String hierarchy = generator.hierarchy();
        final String layers = generator.layers();
        final List<String> oalClasses = generator.oal();
        final String meters = generator.meters();
        generator.index(malClasses, lalClasses, talClasses, zabbixClasses, hierarchy, layers, oalClasses, meters);
        generator.compile();
        generator.copyHolderTypes();
        log.info("Registry: generated {} classes, {} MAL files", generator.sources.size(), malClasses.size());
    }

    /**
     * @return the rule-selecting settings, {@code module.provider.field}, with the files filled in so far
     */
    Map<String, List<String>> activation() {
        return activation;
    }

    // ---- MAL: one class per rule file, the Rule upstream's loader returned plus its expressions and filter ----

    private List<String> mal(final MalCompiled compiled) throws Exception {
        final List<String> classes = new ArrayList<>();
        for (final RuleCatalog catalog : RuleCatalog.malRules()) {
            final List<Rule> rules = mapper.readValue(
                metaInf.resolve("config-data").resolve(catalog.getDirectory() + ".json").toFile(),
                new TypeReference<List<Rule>>() {
                });
            // Every staged file is active, a file yielding no rule too: upstream's loader reads it (and records it for the
            // rule catalog) and returns nothing for it
            activation.get(catalog.setting().key()).addAll(stagedRuleNames.of(catalog.getDirectory()));
            for (final Rule rule : rules) {
                final JavaClass c = new JavaClass(className("Mal", catalog.getDirectory() + "/" + rule.getName()));
                c.method("public static MalFile file()",
                    List.of("return new MalFile(" + literal(catalog.getDirectory()) + ", " + c.bean(rule) + ");"));
                compiled.write(c, rule.getSourcePath(), rule);
                c.write();
                classes.add(c.name);
            }
        }
        return classes;
    }

    /**
     * The MAL expressions and filters the build compiled ({@code mal-v2-rules.txt}), each written into the class of
     * the rule file {@code MetricConvert} compiled it for, so a rule file whose expressions the build did not compile
     * all, or a compiled one no rule file claims, fails the build.
     */
    private final class MalCompiled {
        private final Map<String, List<String>> expressions = new LinkedHashMap<>();
        private final Map<String, Set<List<String>>> expressionKeys = new LinkedHashMap<>();
        private final Map<String, List<String>> filters = new LinkedHashMap<>();

        private MalCompiled() throws Exception {
            final Properties rules = loadProperties(metaInf.resolve("mal-v2-rules.txt"));
            for (int i = 0; rules.getProperty("expression." + i + ".class") != null; i++) {
                final String p = "expression." + i + ".";
                expressions.computeIfAbsent(required(rules, p + "source"), k -> new ArrayList<>()).add(
                    "l.add(new CompiledMalExpression(" + literal(required(rules, p + "source")) + ", "
                        + Integer.parseInt(required(rules, p + "line")) + ", " + literal(required(rules, p + "name"))
                        + ", " + literal(required(rules, p + "text")) + ", " + classRef(rules.getProperty(p + "class"))
                        + "::new));");
                expressionKeys.computeIfAbsent(required(rules, p + "source"), k -> new LinkedHashSet<>()).add(
                    Arrays.asList(required(rules, p + "line"), required(rules, p + "name"), required(rules, p + "text")));
            }
            for (int i = 0; rules.getProperty("filter." + i + ".class") != null; i++) {
                final String p = "filter." + i + ".";
                filters.computeIfAbsent(required(rules, p + "source"), k -> new ArrayList<>()).add(
                    "l.add(new CompiledMalFilter(" + literal(required(rules, p + "source")) + ", "
                        + Integer.parseInt(required(rules, p + "line")) + ", " + literal(required(rules, p + "text"))
                        + ", " + classRef(rules.getProperty(p + "class")) + "::new));");
            }
        }

        /**
         * @param source the rule file's source path, which {@code MetricConvert} passes in each coordinate
         * @param config the rule file, null when the boot compiles none of it (a Zabbix file no host pattern reaches)
         */
        private void write(final JavaClass c, final String source, final MetricRuleConfig config) {
            final List<String> fileExpressions = orNone(expressions.remove(source));
            final Set<List<String>> compiledKeys = Optional.ofNullable(expressionKeys.remove(source)).orElse(Set.of());
            final Set<List<String>> passed = effectiveKeys(config);
            if (!compiledKeys.equals(passed)) {
                throw new IllegalStateException("MAL rule file " + source + " has the metric rules " + passed
                    + " (line, metric name, expression, as MetricConvert passes them), the build compiled "
                    + compiledKeys);
            }
            final String filter = config == null ? null : config.getFilter();
            final List<String> fileFilters = orNone(filters.remove(source));
            if (fileFilters.size() != (filter == null || filter.isEmpty() ? 0 : 1)) {
                throw new IllegalStateException("MAL rule file " + source + " has the filter " + filter
                    + ", the build compiled " + fileFilters.size() + " filters for it");
            }
            c.imports("java.util.List");
            c.chunkedMethod("public static void expressions(final List<CompiledMalExpression> l)", "l", fileExpressions);
            c.method("public static void filters(final List<CompiledMalFilter> l)", fileFilters);
        }

        private void requireAllWritten() {
            final Set<String> unclaimed = new LinkedHashSet<>(expressions.keySet());
            unclaimed.addAll(filters.keySet());
            if (!unclaimed.isEmpty()) {
                throw new IllegalStateException("The build compiled MAL expressions or filters for " + unclaimed
                    + ", which no rule file of the registry holds");
            }
        }
    }

    /**
     * @return the keys {@code MetricConvert} passes the DSL for the file's metric rules, distinct: each rule's line as
     *     its {@code DslSourceRef.ofRule} records it (a line the index could not resolve, such as a rule a merge key
     *     brings in, as -1), its name joined to the file's {@code metricPrefix} and its expression with the file's
     *     {@code expPrefix} and {@code expSuffix} injected ({@code MetricConvert.formatExp}), which may make two rules
     *     of a line alike
     */
    static Set<List<String>> effectiveKeys(final MetricRuleConfig config) {
        if (config == null || config.getMetricsRules() == null) {
            return Set.of();
        }
        return config.getMetricsRules().stream()
                     .map(rule -> Arrays.asList(
                         String.valueOf(DslSourceRef.ofRule(config.getSourcePath(), rule.getLineNo()).getYamlLine()),
                         // as MetricConvert.formatMetricName
                         new StringJoiner("_").add(config.getMetricPrefix()).add(rule.getName()).toString(),
                         MetricConvert.formatExp(config.getExpPrefix(), config.getExpSuffix(), rule.getExp())))
                     .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---- LAL: a class per rule file, what upstream's loader returned plus each rule's class by its coordinates ----

    List<String> lal() throws Exception {
        final Map<String, LALConfigs> files = mapper.readValue(
            metaInf.resolve("config-data/lal.json").toFile(), new TypeReference<LinkedHashMap<String, LALConfigs>>() {
            });
        final Properties rules = loadProperties(metaInf.resolve("lal-v2-rules.txt"));
        // Every staged file is active, a file yielding no rule too: upstream's loader reads it (and records it for the
        // rule catalog) and returns nothing for it
        activation.get(RuleCatalog.LAL.setting().key()).addAll(stagedRuleNames.of(RuleCatalog.LAL.getDirectory()));
        final Map<String, List<String>> rulesByFile = new LinkedHashMap<>();
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            final String p = "rule." + i + ".";
            final String inputType = required(rules, p + "inputType").trim();
            rulesByFile.computeIfAbsent(required(rules, p + "file"), f -> new ArrayList<>()).add(
                "l.add(new LalRule(" + literal(required(rules, p + "source")) + ", "
                    + Integer.parseInt(required(rules, p + "line")) + ", " + literal(ruleName(rules, p))
                    + ", " + classRef(rules.getProperty(p + "class")) + "::new, "
                    + (inputType.isEmpty() ? "null" : classRef(inputType) + ".class") + ", "
                    + literal(required(rules, p + "dslSha256")) + ", "
                    + literal(required(rules, p + "requestedInputType")) + ", "
                    + literal(required(rules, p + "requestedOutputType")) + "));");
        }
        final List<String> classes = new ArrayList<>();
        for (final Map.Entry<String, LALConfigs> file : files.entrySet()) {
            // Keyed by the file name: the loader stamps it as each rule's source, which the classes are found by.
            final JavaClass c = new JavaClass(className("Lal", file.getKey()));
            c.imports("java.util.List");
            c.method("public static LalFile file()",
                List.of("return new LalFile(" + literal(file.getKey()) + ", " + c.bean(file.getValue()) + ");"));
            c.chunkedMethod("public static void rules(final List<LalRule> l)", "l",
                orNone(rulesByFile.remove(file.getKey())));
            c.write();
            classes.add(c.name);
        }
        if (!rulesByFile.isEmpty()) {
            throw new IllegalStateException("LAL rules compiled from files the LAL config data lacks: "
                + rulesByFile.keySet());
        }
        return classes;
    }

    /**
     * @return the rule's name, null for a rule without one ({@code BuildIntermediates#setRuleName})
     */
    private static String ruleName(final Properties props, final String prefix) {
        return Boolean.parseBoolean(props.getProperty(prefix + "unnamed")) ? null : required(props, prefix + "name");
    }

    /**
     * A key the engine pass wrote: the manifests are written in the same run, so one missing is a broken pass.
     */
    private static String required(final Properties props, final String key) {
        final String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("The build manifest has no " + key);
        }
        return value;
    }

    // ---- TAL: a class per rule file, what upstream's loader returned plus every input each rule's class was
    // compiled from ----

    private List<String> tal() throws Exception {
        final Map<String, TALConfigs> files = mapper.readValue(
            metaInf.resolve("config-data/tal.json").toFile(), new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
        final Properties rules = loadProperties(metaInf.resolve("tal-v2-rules.txt"));
        final Map<String, List<Function<JavaClass, String>>> rulesByFile = new LinkedHashMap<>();
        final Set<String> nativeFiles = new HashSet<>();
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            final String p = "rule." + i + ".";
            if (NATIVE_ALIAS.equals(required(rules, p + "inputAlias"))) {
                nativeFiles.add(required(rules, p + "file"));
            }
            rulesByFile.computeIfAbsent(required(rules, p + "file"), f -> new ArrayList<>()).add(talRule(rules, p));
        }
        // The native files run in the listed order (loadInOrder): upstream's, then the others by name.
        final List<String> upstreamOrder = upstreamNativeTalOrder();
        final Map<String, TALConfigs> otlpFiles = new TreeMap<>();
        final List<String> nativeTal = new ArrayList<>();
        for (final Map.Entry<String, TALConfigs> file : files.entrySet()) {
            // A file with no compiled rule to classify it keeps the list upstream's application.yml puts it in: a
            // bundled native file overridden without rules is still read only with the native rules (the agent
            // analyzer loaded); any other such file is a talFiles one
            final boolean classified = rulesByFile.containsKey(file.getKey());
            if (nativeFiles.contains(file.getKey()) || !classified && upstreamOrder.contains(stem(file.getKey()))) {
                nativeTal.add(file.getKey());
            } else {
                otlpFiles.put(file.getKey(), file.getValue());
            }
        }
        final List<String> otlp =
            talFilesInLoadOrder(RuleCatalog.TAL.getDirectory(), otlpFiles, TalPass.shippedModules());
        nativeTal.sort(Comparator.comparingInt((String f) -> upstreamOrder.contains(stem(f))
            ? upstreamOrder.indexOf(stem(f)) : Integer.MAX_VALUE).thenComparing(f -> f));
        // The registry lists the files in this order, which the runtime TALConfigs returns them in.
        final List<String> classes = new ArrayList<>();
        for (final String file : Stream.concat(otlp.stream(), nativeTal.stream()).collect(Collectors.toList())) {
            final JavaClass c = new JavaClass(className("Tal", stem(file)));
            c.imports("java.util.List", "org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration");
            c.method("public static TalFile file()",
                List.of("return new TalFile(" + literal(file) + ", " + c.bean(files.get(file)) + ", "
                    + c.listOf("String", TALConfigs.requiredModulesOf(RuleCatalog.TAL.getDirectory(),
                        List.of(stem(file))).stream().map(RegistryGenerator::literal).collect(Collectors.toList()))
                    + ");"));
            final List<String> adds = new ArrayList<>();
            final List<Function<JavaClass, String>> ruleStatements =
                Optional.ofNullable(rulesByFile.remove(file)).orElse(List.of());
            for (int r = 0; r < ruleStatements.size(); r++) {
                c.method("private static TalRule r" + r + "()", List.of(ruleStatements.get(r).apply(c)));
                adds.add("l.add(r" + r + "());");
            }
            c.chunkedMethod("public static void rules(final List<TalRule> l)", "l", adds);
            c.write();
            classes.add(c.name);
        }
        if (!rulesByFile.isEmpty()) {
            throw new IllegalStateException("TAL rules compiled from files the TAL config data lacks: "
                + rulesByFile.keySet());
        }
        activation.put(RuleCatalog.talFiles().key(),
            otlp.stream().map(RegistryGenerator::stem).collect(Collectors.toList()));
        activation.put(RuleCatalog.nativeTalFiles().key(),
            nativeTal.stream().map(RegistryGenerator::stem).collect(Collectors.toList()));
        return classes;
    }

    /**
     * @param rules  {@code tal-v2-rules.txt}
     * @param prefix the rule's prefix in it
     * @return the statement returning the rule's {@code TalRule}, written into the class of its file once that is made:
     *     each metric declaration a factory of its own and every list bounded, so no generated method grows near the
     *     method-code limit however many metrics, bounds or functions a rule has
     */
    Function<JavaClass, String> talRule(final Properties rules, final String prefix) {
        final List<Function<JavaClass, String>> metrics = new ArrayList<>();
        for (int m = 0; rules.getProperty(prefix + "metric." + m + ".fullName") != null; m++) {
            final String mp = prefix + "metric." + m + ".";
            final String head = literal(required(rules, mp + "fullName")) + ", "
                + literal(required(rules, mp + "function")) + ", " + literal(required(rules, mp + "scopeType"))
                + ", " + literal(required(rules, mp + "declaredName"));
            final List<String> buckets = numbers(required(rules, mp + "buckets"), "L");
            final List<String> ranks = numbers(required(rules, mp + "ranks"), "");
            final int labelLimit = Integer.parseInt(required(rules, mp + "labelLimit"));
            metrics.add(c -> c.factory("TALMetricDeclaration", "return new TALMetricDeclaration(" + head + ", "
                + c.listOf("Long", buckets) + ", " + c.listOf("Integer", ranks) + ", " + labelLimit + ");"));
        }
        final List<String> functions = new ArrayList<>();
        for (int f = 0; rules.getProperty(prefix + "function." + f + ".field") != null; f++) {
            final String fp = prefix + "function." + f + ".";
            functions.add("new TalRule.Function(" + literal(rules.getProperty(fp + "field")) + ", "
                + literal(required(rules, fp + "key")) + ", " + literal(required(rules, fp + "type")) + ")");
        }
        final String head = literal(required(rules, prefix + "source")) + ", "
            + Integer.parseInt(required(rules, prefix + "line")) + ", "
            + literal(ruleName(rules, prefix)) + ", " + classRef(rules.getProperty(prefix + "class")) + "::new, "
            + literal(required(rules, prefix + "inputType")) + ", "
            + literal(required(rules, prefix + "inputAlias")) + ", "
            + literal(required(rules, prefix + "metricPrefix")) + ", "
            + literal(required(rules, prefix + "dslSha256"));
        return c -> "return new TalRule(" + head + ", "
            + c.listOf("TALMetricDeclaration", metrics.stream().map(m -> m.apply(c)).collect(Collectors.toList()))
            + ", " + c.listOf("TalRule.Function", functions) + ");";
    }

    /**
     * The files of {@code talFiles} in the order one upstream {@code TALConfigs.load} over them returns them, as the
     * trace analyzer loads them at boot with the modules the image ships: the order their rules run on a span. A file
     * of layerDefinitions alone, which that load registers and returns nothing for, follows them. Fails on two rules of
     * one name in one layer, as upstream's boot does. The TAL pass compiled no file requiring a module the image does
     * not ship.
     *
     * @param talPath the rule directory
     * @param files   the {@code talFiles} the TAL pass compiled, by file name
     * @param shipped the modules the image ships
     * @return their file names
     */
    static List<String> talFilesInLoadOrder(final String talPath, final Map<String, TALConfigs> files,
                                            final ModuleManager shipped) throws Exception {
        final List<TALConfigs> loaded = TALConfigs.load(
            talPath, files.keySet().stream().map(RegistryGenerator::stem).collect(Collectors.toList()), shipped);
        checkTalRuleNames(loaded);
        final Map<String, TALConfigs> left = new TreeMap<>(files);
        final List<String> order = new ArrayList<>();
        for (final TALConfigs configs : loaded) {
            // No stamped source names a file without rules: files of one content are interchangeable.
            final String file = configs.getRules().isEmpty()
                ? left.entrySet().stream()
                      .filter(e -> StateComparison.firstDifference(configs, e.getValue(), "configs") == null)
                      .map(Map.Entry::getKey).findFirst().orElse(null)
                : configs.getRules().get(0).getSourceName();
            if (file == null || left.remove(file) == null) {
                throw new IllegalStateException("Upstream's TAL loader returned a file of " + talPath
                    + " the TAL pass did not compile: " + (file == null ? configs : file));
            }
            order.add(file);
        }
        for (final Map.Entry<String, TALConfigs> file : left.entrySet()) {
            if (file.getValue().getRules() != null) {
                throw new IllegalStateException("Upstream's TAL loader returned nothing for " + talPath + "/"
                    + file.getKey() + ", which the TAL pass compiled");
            }
            order.add(file.getKey());
        }
        return order;
    }

    /**
     * As upstream's {@code SpanFilterListener.Factory} refuses at boot: two rules of one name in one layer among the
     * files {@code talFiles} loads.
     *
     * @param talFiles the files, in the order upstream's loader returns them
     */
    static void checkTalRuleNames(final List<TALConfigs> talFiles) {
        final Map<String, String> loadedFrom = new HashMap<>();
        for (final TALConfigs file : talFiles) {
            for (final TALConfig rule : file.getRules()) {
                final String earlier = loadedFrom.putIfAbsent(
                    SpanFilterListener.ruleKey(Layer.valueOf(rule.getLayer()), rule.getName()), rule.getSourcePath());
                if (earlier != null) {
                    throw new IllegalStateException("two TAL rules of layer " + rule.getLayer() + " are named "
                        + rule.getName() + ", in " + earlier + " and " + rule.getSourcePath()
                        + "; a rule's name is unique within its layer");
                }
            }
        }
    }

    /**
     * @return the native TAL files upstream's {@code application.yml} lists by default, in that order
     */
    private List<String> upstreamNativeTalOrder() throws Exception {
        try (InputStream is = classLoader.getResourceAsStream("application.yml")) {
            if (is == null) {
                throw new IllegalStateException("No application.yml on the precompiler's class path: its default "
                    + "trace-analyzer.default.nativeTalFiles orders the native TAL files");
            }
            return nativeTalOrder(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
    }

    /**
     * @param applicationYml an {@code application.yml}
     * @return the files its {@code trace-analyzer.default.nativeTalFiles} lists by default, split as the trace analyzer
     *     splits the setting
     */
    static List<String> nativeTalOrder(final Reader applicationYml) {
        Object value = new Yaml().load(applicationYml);
        for (final String key : List.of("trace-analyzer", "default", "nativeTalFiles")) {
            value = value instanceof Map ? ((Map<?, ?>) value).get(key) : null;
        }
        if (value == null) {
            throw new IllegalStateException("application.yml sets no trace-analyzer.default.nativeTalFiles, whose "
                + "default orders the native TAL files");
        }
        final String listed = PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(String.valueOf(value), name -> null);
        if (listed.contains("${")) {
            throw new IllegalStateException("trace-analyzer.default.nativeTalFiles in application.yml has no default, "
                + "which orders the native TAL files: " + value);
        }
        final TraceAnalyzerModuleConfig config = new TraceAnalyzerModuleConfig();
        config.setNativeTalFiles(listed);
        return config.nativeTalFiles();
    }

    // ---- Zabbix: a class per rule file, the config upstream's loader reads and what MAL compiled from it ----

    private List<String> zabbix(final MalCompiled compiled) throws Exception {
        final List<String> classes = new ArrayList<>();
        final String directory = RuleCatalog.ZABBIX_RULES.getDirectory();
        // Every staged file is active, a file yielding no config too, which upstream's loader returns nothing for
        activation.get(RuleCatalog.ZABBIX_RULES.setting().key()).addAll(stagedRuleNames.of(directory));
        for (final ZabbixConfig config : ZabbixConfigs.loadConfigs(directory, RuleTree.ruleNames(directory))) {
            final JavaClass c = new JavaClass(className("Zabbix", config.getSourceName()));
            c.imports(ZabbixConfig.class.getName());
            c.method("public static ZabbixConfig config()", List.of("return " + c.bean(config) + ";"));
            final boolean reached = ZabbixHostPatterns.compiledAtBoot(config);
            compiled.write(c, config.getSourcePath(), reached ? config : null);
            c.write();
            classes.add(c.name);
        }
        return classes;
    }

    // ---- Hierarchy: the definition file's data, and the class compiled from each matching rule ----

    private String hierarchy() throws Exception {
        final Map<String, Object> definition;
        try (InputStream is = classLoader.getResourceAsStream(RuleCatalog.HIERARCHY_DEFINITION)) {
            if (is == null) {
                throw new IllegalStateException(RuleCatalog.HIERARCHY_DEFINITION + " is not on the classpath");
            }
            definition = new Yaml().load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        final Properties classesByRule = loadProperties(metaInf.resolve("hierarchy-v2-rules.txt"));
        final JavaClass c = new JavaClass("HierarchyRegistry");
        c.imports("java.util.Map", "java.util.LinkedHashMap", "java.util.function.BiFunction",
            "java.util.function.Supplier", "org.apache.skywalking.oap.server.core.query.type.Service");
        final String file = RuleCatalog.HIERARCHY_DEFINITION;
        final List<String> matchers = new ArrayList<>();
        for (final String rule : HierarchyPass.section(definition, file, "auto-matching-rules").keySet()) {
            final String className = classesByRule.getProperty(rule);
            if (className == null) {
                throw new IllegalStateException("hierarchy-definition.yml auto-matching-rules: " + rule
                    + " has no compiled class");
            }
            matchers.add("m.put(" + literal(rule) + ", " + classRef(className) + "::new);");
        }
        c.chunkedMethod("private static void matchers(final Map<String, Supplier<BiFunction<Service, Service, Boolean>>> m)",
            "m", matchers);
        final List<String> body = new ArrayList<>();
        body.add("final Map<String, Supplier<BiFunction<Service, Service, Boolean>>> m = new LinkedHashMap<>();");
        body.add("matchers(m);");
        body.add("return new HierarchyRules(" + c.expr(HierarchyPass.section(definition, file, "hierarchy")) + ", "
            + c.expr(HierarchyPass.section(definition, file, "auto-matching-rules")) + ", "
            + c.expr(HierarchyPass.section(definition, file, "layer-levels"))
            + ", m);");
        c.method("public static HierarchyRules rules()", body);
        c.write();
        return c.name;
    }

    // ---- Layer extensions: the entries upstream's LayerExtensionLoader registers from layer-extensions.yml ----

    private String layers() throws Exception {
        final List<String> items = new ArrayList<>();
        final JavaClass c = new JavaClass("LayerRegistry");
        c.imports("java.util.List", LayerDefinition.class.getName());
        try (InputStream is = classLoader.getResourceAsStream(RuleCatalog.LAYER_EXTENSIONS)) {
            if (is != null) {
                final LayerExtensionLoader.LayerExtensionsFile file = new Yaml().loadAs(
                    new InputStreamReader(is, StandardCharsets.UTF_8), LayerExtensionLoader.LayerExtensionsFile.class);
                if (file != null && file.getLayers() != null) {
                    for (final LayerDefinition definition : file.getLayers()) {
                        items.add(c.bean(definition));
                    }
                }
            }
        }
        c.method("public static List<LayerDefinition> extensions()",
            List.of("return " + c.listOf("LayerDefinition", items) + ";"));
        c.write();
        return c.name;
    }

    // ---- OAL: a class per script, its define and the metrics, dispatchers and disabled sources it generated ----

    private List<String> oal() throws Exception {
        final Properties scripts = loadProperties(metaInf.resolve("oal-scripts.txt"));
        final List<String> classes = new ArrayList<>();
        for (int i = 0; scripts.getProperty("script." + i + ".define") != null; i++) {
            final String p = "script." + i + ".";
            final JavaClass c = new JavaClass(className("Oal", stem(required(scripts, p + "file"))));
            c.imports("java.util.ArrayList", "java.util.List", OALDefine.class.getName());
            final String define = scripts.getProperty(p + "define");
            c.chunkedMethod("private static void metrics(final List<Class<?>> l)", "l",
                classLiterals("l.add(", csv(required(scripts, p + "metrics")), ");"));
            c.chunkedMethod("private static void dispatchers(final List<Class<?>> l)", "l",
                classLiterals("l.add(", csv(required(scripts, p + "dispatchers")), ");"));
            c.method("public static OalScript script()", List.of(
                "final List<Class<?>> metrics = new ArrayList<>();",
                "metrics(metrics);",
                "final List<Class<?>> dispatchers = new ArrayList<>();",
                "dispatchers(dispatchers);",
                // An overlay's own script has no define class: the define is recreated from the same inputs.
                "return new OalScript(" + (define.isEmpty()
                    ? "new OALDefine("
                        + literal(required(scripts, p + "file")) + ", "
                        + literal(required(scripts, p + "sourcePackage")) + ", "
                        + literal(required(scripts, p + "dispatcherPackage").substring(DISPATCHER_PACKAGE.length()))
                        + ") {\n        }"
                    : classRef(define) + ".INSTANCE")
                    + ", metrics, dispatchers, " + c.listOf("String", csv(required(scripts, p + "disabled"))
                        .stream().map(RegistryGenerator::literal).collect(Collectors.toList()))
                    + ", " + define.isEmpty() + ");"));
            c.write();
            classes.add(c.name);
        }
        oalExcluded.addAll(csv(required(scripts, "excluded")));
        return classes;
    }

    // ---- Meters: the meter classes MeterSystem generated, by the metric name the rule passes (the class name
    // lowercases it in the build's locale, which the boot's need not share), and the meter functions, by name ----

    private String meters() throws Exception {
        final List<String> puts = new ArrayList<>();
        for (final String manifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            for (final String line : readLines(manifest)) {
                final String className = line.substring(line.indexOf('=') + 1).trim();
                puts.add("m.put(" + literal(line.substring(0, line.indexOf('=')).trim()) + ", " + classRef(className)
                    + ".class);");
            }
        }
        final List<String> functions = new ArrayList<>();
        for (final String line : Files.readAllLines(metaInf.resolve("annotation-scan/MeterFunction.txt"),
            StandardCharsets.UTF_8)) {
            final int eq = line.indexOf('=');
            functions.add("m.put(" + literal(line.substring(0, eq)) + ", " + classRef(line.substring(eq + 1)) + ".class);");
        }
        final JavaClass c = new JavaClass("MeterRegistry");
        c.imports("java.util.Map", ACCEPTABLE_VALUE);
        c.chunkedMethod("public static void classes(final Map<String, Class<?>> m)", "m", puts);
        c.method("public static void functions(final Map<String, Class<? extends AcceptableValue>> m)", functions);
        c.write();
        return c.name;
    }

    // ---- The index the runtime replacements read ----

    private void index(final List<String> malClasses, final List<String> lalClasses, final List<String> talClasses,
                       final List<String> zabbixClasses, final String hierarchy, final String layers,
                       final List<String> oalClasses, final String meters) throws Exception {
        final JavaClass c = new JavaClass("CompiledRules");
        c.imports("java.util.ArrayList", "java.util.Collections", "java.util.HashMap", "java.util.List",
            "java.util.Map", LayerDefinition.class.getName(), ZabbixConfig.class.getName(), ACCEPTABLE_VALUE);
        c.method("private CompiledRules()", List.of());
        c.listMethod("public static List<MalFile> malFiles()", "MalFile", adds(malClasses, ".file()"));
        c.method("public static List<CompiledMalExpression> malExpressions()", List.of("return MalExpressions.LIST;"));
        c.method("public static List<CompiledMalFilter> malFilters()", List.of("return MalFilters.LIST;"));
        c.listMethod("public static List<LalFile> lalFiles()", "LalFile", adds(lalClasses, ".file()"));
        c.listMethod("public static List<LalRule> lalRules()", "LalRule", fills(lalClasses, ".rules(l);"));
        c.listMethod("public static List<TalFile> talFiles()", "TalFile", adds(talClasses, ".file()"));
        c.listMethod("public static List<TalRule> talRules()", "TalRule", fills(talClasses, ".rules(l);"));
        c.listMethod("public static List<ZabbixConfig> zabbixConfigs()", "ZabbixConfig", adds(zabbixClasses, ".config()"));
        c.method("public static HierarchyRules hierarchy()", List.of("return " + hierarchy + ".rules();"));
        c.method("public static List<LayerDefinition> layerExtensions()", List.of("return " + layers + ".extensions();"));
        c.listMethod("public static List<OalScript> oalScripts()", "OalScript", adds(oalClasses, ".script()"));
        c.method("public static List<String> oalExcludedScripts()", List.of("return " + c.listOf("String",
            oalExcluded.stream().map(RegistryGenerator::literal).collect(Collectors.toList())) + ";"));
        c.method("public static Map<String, Class<?>> meterClasses()", List.of("return MeterClasses.MAP;"));
        c.method("public static Map<String, Class<? extends AcceptableValue>> meterFunctions()",
            List.of("return MeterFunctions.MAP;"));
        final List<String> settings = new ArrayList<>();
        for (final RuleCatalog.Setting setting : RuleCatalog.allSettings()) {
            final List<String> files = activation.get(setting.key());
            settings.add(literal(setting.key()));
            settings.add(literal(String.join(",", files)));
        }
        c.javadoc("The rules compiled into this image, read by the runtime replacements in place of rule files. Every "
            + "call builds fresh rule objects, as reading the files did; the lookup maps and lists are built once. "
            + "Every compiled rule file is active: activation() holds, for each setting that selects rule files "
            + "(module.provider.field), all the files compiled for it.");
        c.method("public static Map<String, String> activation()",
            List.of("return RegistrySupport.map(" + String.join(", ", settings) + ");"));
        final StringBuilder holders = new StringBuilder();
        final List<String> compiledMal = new ArrayList<>(malClasses);
        compiledMal.addAll(zabbixClasses);
        c.listMethod("private static List<CompiledMalExpression> malExpressionList()", "CompiledMalExpression",
            fills(compiledMal, ".expressions(l);"));
        c.listMethod("private static List<CompiledMalFilter> malFilterList()", "CompiledMalFilter",
            fills(compiledMal, ".filters(l);"));
        holders.append(listHolder("MalExpressions", "CompiledMalExpression", "malExpressionList()"));
        holders.append(listHolder("MalFilters", "CompiledMalFilter", "malFilterList()"));
        holders.append(holder("MeterClasses", "Map<String, Class<?>>", List.of(meters + ".classes(m);")));
        holders.append(holder("MeterFunctions", "Map<String, Class<? extends AcceptableValue>>",
            List.of(meters + ".functions(m);")));
        c.raw(holders.toString());
        c.write();
    }

    /**
     * @return statements adding what each class's {@code call} returns to {@code l}
     */
    private static List<String> adds(final List<String> classes, final String call) {
        return classes.stream().map(c -> "l.add(" + c + call + ");").collect(Collectors.toList());
    }

    /**
     * @return statements letting each class fill {@code l} through its {@code call}
     */
    private static List<String> fills(final List<String> classes, final String call) {
        return classes.stream().map(c -> c + call).collect(Collectors.toList());
    }

    private static String holder(final String name, final String type, final List<String> fills) {
        final StringBuilder sb = new StringBuilder();
        sb.append("    private static final class ").append(name).append(" {\n");
        sb.append("        private static final ").append(type).append(" MAP = build();\n\n");
        sb.append("        private static ").append(type).append(" build() {\n");
        sb.append("            final ").append(type).append(" m = new HashMap<>();\n");
        fills.forEach(f -> sb.append("            ").append(f).append('\n'));
        sb.append("            return Collections.unmodifiableMap(m);\n        }\n    }\n\n");
        return sb.toString();
    }

    /**
     * @param build a call of the index's own that builds the list
     */
    private static String listHolder(final String name, final String element, final String build) {
        final StringBuilder sb = new StringBuilder();
        sb.append("    private static final class ").append(name).append(" {\n");
        sb.append("        private static final List<").append(element).append("> LIST = Collections.unmodifiableList(")
          .append(build).append(");\n    }\n\n");
        return sb.toString();
    }

    // ---- Compilation ----

    private void compile() throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The rule registry is compiled in process and needs a JDK, not a JRE");
        }
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
            diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            // An explicit source path, or javac takes the engines' debug .java dumps in the output directory as
            // sources of the classes it resolves there.
            final List<String> options = List.of(
                "-encoding", "UTF-8", "--release", String.valueOf(Runtime.version().feature()), "-proc:none",
                "-implicit:none", "-nowarn", "-Xlint:none", "-sourcepath", sourceDir.toString(),
                "-classpath", classpath(), "-d", outputDir.toString());
            final Boolean ok = compiler.getTask(null, files, diagnostics, options, null,
                files.getJavaFileObjectsFromPaths(sources)).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("The generated rule registry does not compile:\n"
                    + diagnostics.getDiagnostics().stream().map(Object::toString).limit(20)
                                 .collect(Collectors.joining("\n")));
            }
        }
    }

    /**
     * The classes the precompiler runs with (exec-maven-plugin's class loader, or the test JVM's class path) plus the
     * generated classes, which are on neither.
     */
    private String classpath() throws URISyntaxException {
        final Set<String> entries = new LinkedHashSet<>();
        entries.add(outputDir.toString());
        for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader) {
                for (final URL url : ((URLClassLoader) cl).getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        for (final String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * The hand-written holder types the generated classes build, compiled with the precompiler, go into the generated
     * jar with them.
     */
    private void copyHolderTypes() throws Exception {
        final Path classes = Path.of(MalFile.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Path packagePath = Path.of(PACKAGE.replace('.', '/'));
        final Path from = classes.resolve(packagePath);
        if (!Files.isDirectory(from)) {
            throw new IllegalStateException("The registry holder types are not a class directory: " + classes);
        }
        final Path to = outputDir.resolve(packagePath);
        try (Stream<Path> holders = Files.list(from)) {
            for (final Path holder : holders.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
                Files.copy(holder, to.resolve(holder.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ---- Source helpers ----

    /**
     * A class name for a rule file, unique even where two paths map to the same identifier or differ in case only
     * (class files of a case-insensitive file system).
     */
    private String className(final String prefix, final String path) {
        final String base = prefix + "_" + path.replaceAll("[^A-Za-z0-9]", "_");
        String name = base;
        for (int i = 2; !classNames.add(name.toLowerCase(Locale.ROOT)); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    /**
     * The source name of a class the generated code references: the canonical name of a class the precompiler can
     * load, or of a generated top-level class (generated classes are only in the output directory), which may have a
     * {@code $} in its name; a generated nested class cannot be referenced.
     */
    String classRef(final String binaryName) {
        try {
            final String canonical = Class.forName(binaryName, false, classLoader).getCanonicalName();
            if (canonical == null) {
                throw new IllegalStateException(binaryName + " has no canonical name to reference it by");
            }
            return canonical;
        } catch (ClassNotFoundException e) {
            if (!Files.isRegularFile(outputDir.resolve(binaryName.replace('.', '/') + ".class"))) {
                throw new IllegalStateException("The registry references " + binaryName + ", which was not generated");
            }
        }
        // Loaded, not initialized, so its own metadata says whether it is nested: a top-level class may have a $ in its
        // name (a metric or a rule named so)
        final Class<?> generated;
        try {
            generated = Class.forName(binaryName, false, generatedClasses());
        } catch (ClassNotFoundException | LinkageError | MalformedURLException notLoadable) {
            throw new IllegalStateException("The registry references " + binaryName + ", which does not load",
                notLoadable);
        }
        if (generated.getEnclosingClass() != null || generated.getCanonicalName() == null) {
            throw new IllegalStateException("The registry cannot reference the nested class " + binaryName);
        }
        return generated.getCanonicalName();
    }

    /**
     * @return a class loader over the generated classes, under the precompiler's
     */
    private ClassLoader generatedClasses() throws MalformedURLException {
        if (generatedClasses == null) {
            generatedClasses = new URLClassLoader(new URL[] {outputDir.toUri().toURL()}, classLoader);
        }
        return generatedClasses;
    }

    private List<String> classLiterals(final String before, final List<String> names, final String after) {
        return names.stream().map(n -> before + classRef(n) + ".class" + after).collect(Collectors.toList());
    }

    private List<String> readLines(final String manifest) throws Exception {
        return Files.readAllLines(metaInf.resolve(manifest), StandardCharsets.UTF_8).stream().map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toList());
    }

    private static Properties loadProperties(final Path path) throws Exception {
        final Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static String stem(final String path) {
        final String name = path.substring(path.lastIndexOf('/') + 1);
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    private static List<String> csv(final String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private static List<String> orNone(final List<String> statements) {
        return statements == null ? List.of() : statements;
    }

    private static List<String> numbers(final String csv, final String suffix) {
        final List<String> values = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (final String v : csv.split(",")) {
                values.add(v.trim() + suffix);
            }
        }
        return values;
    }

    static String literal(final String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_CONSTANT_CHARS) {
            return quote(value);
        }
        // Concatenating constants would fold them back into one: join at runtime instead.
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < value.length(); i += MAX_CONSTANT_CHARS) {
            parts.add(quote(value.substring(i, Math.min(value.length(), i + MAX_CONSTANT_CHARS))));
        }
        return "String.join(\"\", " + String.join(", ", parts) + ")";
    }

    private static String quote(final String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20 || ch > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The generated code rebuilds a rule bean from a new instance through the setters it emits: rebuild it so here and
     * require it to equal the bean, or state upstream's loader set (a field no getter/setter pair reaches, a setter
     * without a getter) would be lost at runtime without a sign.
     */
    static void requireCarried(final Object bean, final List<PropertyDescriptor> carried)
        throws ReflectiveOperationException {
        final Object rebuilt = bean.getClass().getConstructor().newInstance();
        for (final PropertyDescriptor property : carried) {
            property.getWriteMethod().invoke(rebuilt, property.getReadMethod().invoke(bean));
        }
        final String difference = StateComparison.firstDifference(bean, rebuilt, bean.getClass().getName());
        if (difference != null) {
            throw new IllegalStateException("The rule registry cannot carry " + difference + ": it writes rule data "
                + "through getter/setter pairs only; teach RegistryGenerator to write it, or the runtime would lose it");
        }
    }

    /**
     * A generated source file. Rule objects become factory methods, one per object, so no method grows near the
     * method-code limit whatever a rule file holds.
     */
    final class JavaClass {
        private final String name;
        private final Set<String> imports = new LinkedHashSet<>();
        private final StringBuilder members = new StringBuilder();
        private String javadoc;
        private int beans;
        private int parts;

        JavaClass(final String name) {
            this.name = name;
        }

        void imports(final String... types) {
            imports.addAll(List.of(types));
        }

        private void javadoc(final String text) {
            javadoc = text;
        }

        private void raw(final String text) {
            members.append(text);
        }

        void method(final String signature, final List<String> statements) {
            members.append("    ").append(signature).append(" {\n");
            statements.forEach(s -> members.append("        ").append(s).append('\n'));
            members.append("    }\n\n");
        }

        /**
         * A void method whose statements only fill its parameter, split into helpers of a bounded size.
         */
        void chunkedMethod(final String signature, final String parameter, final List<String> statements) {
            if (statements.size() <= STATEMENTS_PER_METHOD) {
                method(signature, statements);
                return;
            }
            final String parameterDeclaration = signature.substring(signature.indexOf('(') + 1, signature.lastIndexOf(')'));
            final List<String> calls = new ArrayList<>();
            for (int from = 0; from < statements.size(); from += STATEMENTS_PER_METHOD) {
                final String helper = "part" + parts++;
                method("private static void " + helper + "(" + parameterDeclaration + ")",
                    statements.subList(from, Math.min(statements.size(), from + STATEMENTS_PER_METHOD)));
                calls.add(helper + "(" + parameter + ");");
            }
            method(signature, calls);
        }

        /**
         * A method returning a new list of the element type that the statements fill ({@code l.add(...)}, ...), split
         * into helpers of a bounded size.
         */
        void listMethod(final String signature, final String elementType, final List<String> fills) {
            final String type = elementType == null ? "java.util.List" : "java.util.List<" + elementType + ">";
            final String fill = "fill" + parts++;
            chunkedMethod("private static void " + fill + "(final " + type + " l)", "l", fills);
            method(signature, List.of("final " + type + " l = new java.util.ArrayList<>();", fill + "(l);",
                "return l;"));
        }

        /**
         * @param elementType the element type, or null for a list whose type its use infers (a setter's parameter)
         * @return a new mutable list of the items, as {@code RegistrySupport.list} builds it, built by a factory method
         *     whose additions are split into helpers of a bounded size where they are many
         */
        String listOf(final String elementType, final List<String> items) {
            if (items.size() == 1 && "null".equals(items.get(0))) {
                // A lone null would be the varargs array itself
                return elementType == null ? "(java.util.List) RegistrySupport.list((Object) null)"
                    : "RegistrySupport.<" + elementType + ">list((" + elementType + ") null)";
            }
            if (items.size() <= STATEMENTS_PER_METHOD) {
                return "RegistrySupport." + (elementType == null ? "" : "<" + elementType + ">") + "list("
                    + String.join(", ", items) + ")";
            }
            final String factory = "list" + parts++;
            listMethod("private static " + (elementType == null ? "java.util.List" : "java.util.List<" + elementType + ">")
                + " " + factory + "()", elementType,
                items.stream().map(item -> "l.add(" + item + ");").collect(Collectors.toList()));
            return factory + "()";
        }

        /**
         * @return a call to a new factory method of the type made of the statements
         */
        String factory(final String type, final String... statements) {
            final String factory = "v" + parts++;
            method("private static " + type + " " + factory + "()", List.of(statements));
            return factory + "()";
        }

        /**
         * @return a new mutable map of the keys and values, as {@code RegistrySupport.map} builds it, built by a factory
         *     method whose entries are split into helpers of a bounded size where they are many
         */
        String mapOf(final List<String> keysAndValues) {
            if (keysAndValues.size() / 2 <= STATEMENTS_PER_METHOD) {
                return "RegistrySupport.map(" + String.join(", ", keysAndValues) + ")";
            }
            final String factory = "map" + parts++;
            final String fill = "fill" + parts++;
            final List<String> puts = new ArrayList<>();
            for (int i = 0; i < keysAndValues.size(); i += 2) {
                puts.add("m.put(" + keysAndValues.get(i) + ", " + keysAndValues.get(i + 1) + ");");
            }
            chunkedMethod("private static void " + fill + "(final java.util.Map m)", "m", puts);
            method("private static java.util.Map " + factory + "()", List.of(
                "final java.util.Map m = new java.util.LinkedHashMap<>();", fill + "(m);", "return m;"));
            return factory + "()";
        }

        /**
         * A rule object as a call to a factory method that builds it with the setters of every property whose value
         * differs from a new instance's.
         */
        String bean(final Object bean) throws Exception {
            final Class<?> type = bean.getClass();
            final Object defaults = type.getConstructor().newInstance();
            final String typeName = type.getCanonicalName();
            final List<String> statements = new ArrayList<>();
            statements.add("final " + typeName + " o = new " + typeName + "();");
            final List<PropertyDescriptor> carried = new ArrayList<>();
            for (final PropertyDescriptor property : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                if (property.getReadMethod() == null || property.getWriteMethod() == null) {
                    continue;
                }
                final Object value = property.getReadMethod().invoke(bean);
                // By state, not equals, which may leave out a field the loader stamped (a line, a source path); and
                // by the backing field too, where a getter reads two states alike (an inherited null and false).
                if (StateComparison.firstDifference(
                    value, property.getReadMethod().invoke(defaults), property.getName()) == null
                    && !backingFieldDiffers(bean, defaults, property.getName())) {
                    continue;
                }
                carried.add(property);
                statements.add("o." + property.getWriteMethod().getName() + "(" + expr(value) + ");");
            }
            requireCarried(bean, carried);
            statements.add("return o;");
            final String method = "b" + beans++;
            method("private static " + typeName + " " + method + "()", statements);
            return method + "()";
        }

        private static boolean backingFieldDiffers(final Object bean, final Object defaults, final String name)
            throws IllegalAccessException {
            for (Class<?> c = bean.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (final Field field : c.getDeclaredFields()) {
                    if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        return StateComparison.firstDifference(field.get(bean), field.get(defaults), name) != null;
                    }
                }
            }
            return false;
        }

        String expr(final Object value) throws Exception {
            if (value == null) {
                return "null";
            }
            if (value instanceof String) {
                return literal((String) value);
            }
            if (value instanceof Boolean || value instanceof Integer) {
                return value.toString();
            }
            if (value instanceof Long) {
                return value + "L";
            }
            if (value instanceof Double) {
                return value + "d";
            }
            if (value instanceof Enum) {
                return ((Enum<?>) value).getDeclaringClass().getCanonicalName() + "." + ((Enum<?>) value).name();
            }
            if (value instanceof List) {
                final List<String> items = new ArrayList<>();
                for (final Object item : (List<?>) value) {
                    items.add(expr(item));
                }
                return listOf(null, items);
            }
            if (value instanceof Map) {
                final List<String> items = new ArrayList<>();
                for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    items.add(expr(entry.getKey()));
                    items.add(expr(entry.getValue()));
                }
                return mapOf(items);
            }
            return bean(value);
        }

        void write() throws Exception {
            final StringBuilder sb = new StringBuilder();
            sb.append("package ").append(PACKAGE).append(";\n\n");
            imports.forEach(i -> sb.append("import ").append(i).append(";\n"));
            sb.append('\n');
            sb.append("/**\n * ").append(javadoc == null ? "Generated by the precompiler's RegistryGenerator." : javadoc)
              .append("\n */\n");
            sb.append("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            sb.append("public final class ").append(name).append(" {\n\n").append(members).append("}\n");
            final Path file = sourceDir.resolve(PACKAGE.replace('.', '/')).resolve(name + ".java");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            sources.add(file);
        }
    }
}
