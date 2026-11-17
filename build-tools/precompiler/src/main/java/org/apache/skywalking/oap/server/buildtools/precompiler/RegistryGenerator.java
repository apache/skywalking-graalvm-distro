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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
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
    static final String[] MAL_CATALOGS = {
        "meter-analyzer-config", "otel-rules", "log-mal-rules", "envoy-metrics-rules", "telegraf-rules", "zabbix-rules"
    };
    // Below the class-file limit of 65535 UTF-8 bytes per string constant, at up to 6 bytes per escaped char.
    private static final int MAX_CONSTANT_CHARS = 8000;
    // Statements per generated method, far below the 64KB method-code limit.
    private static final int STATEMENTS_PER_METHOD = 300;

    private final Path outputDir;
    private final Path metaInf;
    private final Path sourceDir;
    private final ClassLoader classLoader = RegistryGenerator.class.getClassLoader();
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Set<String> classNames = new HashSet<>();
    private final List<Path> sources = new ArrayList<>();

    private RegistryGenerator(final Path outputDir, final Path sourceDir) {
        this.outputDir = outputDir;
        this.metaInf = outputDir.resolve("META-INF");
        this.sourceDir = sourceDir;
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
        final List<String> malClasses = generator.mal();
        final List<String> lalClasses = generator.lal();
        final List<String> talClasses = generator.tal();
        final List<String> zabbixClasses = generator.zabbix();
        final String hierarchy = generator.hierarchy();
        final String layers = generator.layers();
        final List<String> oalClasses = generator.oal();
        final String meters = generator.meters();
        generator.index(malClasses, lalClasses, talClasses, zabbixClasses, hierarchy, layers, oalClasses, meters);
        generator.compile();
        generator.copyHolderTypes();
        log.info("Registry: generated {} classes, {} MAL files", generator.sources.size(), malClasses.size());
    }

    // ---- MAL: one class per rule file, the Rule upstream's loader returned plus its expressions and filter ----

    private List<String> mal() throws Exception {
        final Map<String, Properties> perFile = new LinkedHashMap<>();
        for (final String line : Files.readAllLines(metaInf.resolve("mal-v2.manifest"), StandardCharsets.UTF_8)) {
            final String file = line.trim();
            if (!file.isEmpty() && !file.startsWith("#")) {
                perFile.put(file, loadProperties(metaInf.resolve("mal-v2").resolve(file)));
            }
        }
        final List<String> classes = new ArrayList<>();
        for (final String catalog : MAL_CATALOGS) {
            final Path json = metaInf.resolve("config-data").resolve(catalog + ".json");
            if (!Files.exists(json)) {
                continue;
            }
            final List<Rule> rules = mapper.readValue(json.toFile(), new TypeReference<List<Rule>>() {
            });
            for (final Rule rule : rules) {
                final String file = catalog + "/" + rule.getName() + ".yaml";
                final Properties props = perFile.get(file);
                if (props == null) {
                    throw new IllegalStateException("MAL rule file " + file + " has no per-file manifest");
                }
                final JavaClass c = new JavaClass(className("Mal", catalog + "/" + rule.getName()));
                c.imports("java.util.Map", "java.util.function.Supplier",
                    "org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression",
                    "org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalFilter");
                c.method("public static MalFile file()",
                    List.of("return new MalFile(" + literal(catalog) + ", " + c.bean(rule) + ");"));
                final List<String> expressions = new ArrayList<>();
                for (int i = 0; props.getProperty("rule." + i + ".exp") != null; i++) {
                    final String className = props.getProperty("rule." + i + ".class", "");
                    if (!className.isEmpty()) {
                        expressions.add("m.put(" + literal(props.getProperty("rule." + i + ".exp")) + ", "
                            + classRef(className) + "::new);");
                    }
                }
                c.chunkedMethod("public static void expressions(final Map<String, Supplier<MalExpression>> m)",
                    "m", expressions);
                final List<String> filters = new ArrayList<>();
                final String filter = props.getProperty("filter", "");
                final String filterClass = props.getProperty("filter.class", "");
                if (!filter.isEmpty() && !filterClass.isEmpty()) {
                    filters.add("m.putIfAbsent(" + literal(filter) + ", " + classRef(filterClass) + "::new);");
                }
                c.method("public static void filters(final Map<String, Supplier<MalFilter>> m)", filters);
                c.write();
                classes.add(c.name);
            }
        }
        return classes;
    }

    // ---- LAL: a class per rule file, what upstream's loader returned plus each rule's class by its coordinates ----

    private List<String> lal() throws Exception {
        final Map<String, LALConfigs> files = mapper.readValue(
            metaInf.resolve("config-data/lal.json").toFile(), new TypeReference<LinkedHashMap<String, LALConfigs>>() {
            });
        final Properties rules = loadProperties(metaInf.resolve("lal-v2-rules.txt"));
        final Map<String, List<String>> rulesByFile = new LinkedHashMap<>();
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            final String p = "rule." + i + ".";
            final String inputType = rules.getProperty(p + "inputType", "").trim();
            rulesByFile.computeIfAbsent(stem(rules.getProperty(p + "source")), f -> new ArrayList<>()).add(
                "l.add(new LalRule(" + literal(rules.getProperty(p + "source")) + ", "
                    + Integer.parseInt(rules.getProperty(p + "line")) + ", " + literal(rules.getProperty(p + "name"))
                    + ", " + classRef(rules.getProperty(p + "class")) + "::new, "
                    + (inputType.isEmpty() ? "null" : classRef(inputType) + ".class") + "));");
        }
        final List<String> classes = new ArrayList<>();
        for (final Map.Entry<String, LALConfigs> file : files.entrySet()) {
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

    // ---- TAL: a class per rule file, what upstream's loader returned plus every input each rule's class was
    // compiled from ----

    private List<String> tal() throws Exception {
        final Map<String, TALConfigs> files = mapper.readValue(
            metaInf.resolve("config-data/tal.json").toFile(), new TypeReference<LinkedHashMap<String, TALConfigs>>() {
            });
        final Properties rules = loadProperties(metaInf.resolve("tal-v2-rules.txt"));
        final Map<String, List<String>> rulesByFile = new LinkedHashMap<>();
        for (int i = 0; rules.getProperty("rule." + i + ".class") != null; i++) {
            final String p = "rule." + i + ".";
            final List<String> metrics = new ArrayList<>();
            for (int m = 0; rules.getProperty(p + "metric." + m + ".fullName") != null; m++) {
                final String mp = p + "metric." + m + ".";
                metrics.add("new TALMetricDeclaration(" + literal(rules.getProperty(mp + "fullName")) + ", "
                    + literal(rules.getProperty(mp + "function")) + ", " + literal(rules.getProperty(mp + "scopeType"))
                    + ", " + literal(rules.getProperty(mp + "declaredName")) + ", "
                    + numbers(rules.getProperty(mp + "buckets"), "L") + ", " + numbers(rules.getProperty(mp + "ranks"), "")
                    + ", " + Integer.parseInt(rules.getProperty(mp + "labelLimit", "0")) + ")");
            }
            final List<String> functions = new ArrayList<>();
            for (int f = 0; rules.getProperty(p + "function." + f + ".field") != null; f++) {
                final String fp = p + "function." + f + ".";
                functions.add("new TalRule.Function(" + literal(rules.getProperty(fp + "field")) + ", "
                    + literal(rules.getProperty(fp + "key")) + ", " + literal(rules.getProperty(fp + "type")) + ")");
            }
            final String source = rules.getProperty(p + "source");
            rulesByFile.computeIfAbsent(source.substring(source.indexOf('/') + 1), f -> new ArrayList<>()).add(
                "return new TalRule(" + literal(source) + ", " + Integer.parseInt(rules.getProperty(p + "line")) + ", "
                    + literal(rules.getProperty(p + "name")) + ", " + classRef(rules.getProperty(p + "class")) + "::new, "
                    + literal(rules.getProperty(p + "inputType", "")) + ", "
                    + literal(rules.getProperty(p + "inputAlias", "")) + ", "
                    + literal(rules.getProperty(p + "metricPrefix", "")) + ", "
                    + literal(rules.getProperty(p + "dslSha256", "")) + ", "
                    + typedList("TALMetricDeclaration", metrics) + ", " + typedList("TalRule.Function", functions) + ");");
        }
        final List<String> classes = new ArrayList<>();
        for (final Map.Entry<String, TALConfigs> file : files.entrySet()) {
            final JavaClass c = new JavaClass(className("Tal", stem(file.getKey())));
            c.imports("java.util.List", "org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration");
            c.method("public static TalFile file()",
                List.of("return new TalFile(" + literal(file.getKey()) + ", " + c.bean(file.getValue()) + ");"));
            final List<String> adds = new ArrayList<>();
            final List<String> ruleStatements = orNone(rulesByFile.remove(file.getKey()));
            for (int r = 0; r < ruleStatements.size(); r++) {
                c.method("private static TalRule r" + r + "()", List.of(ruleStatements.get(r)));
                adds.add("l.add(r" + r + "());");
            }
            c.method("public static void rules(final List<TalRule> l)", adds);
            c.write();
            classes.add(c.name);
        }
        if (!rulesByFile.isEmpty()) {
            throw new IllegalStateException("TAL rules compiled from files the TAL config data lacks: "
                + rulesByFile.keySet());
        }
        return classes;
    }

    // ---- Zabbix: a class per rule file, the config upstream's loader reads; its expressions are compiled with MAL ----

    private List<String> zabbix() throws Exception {
        final List<String> stems = new ArrayList<>();
        for (final File f : ResourceUtils.getPathFiles("zabbix-rules")) {
            final String name = f.getName();
            if (f.isFile() && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                stems.add(name.substring(0, name.lastIndexOf('.')));
            }
        }
        final List<String> classes = new ArrayList<>();
        for (final ZabbixConfig config : ZabbixConfigs.loadConfigs("zabbix-rules", stems)) {
            final JavaClass c = new JavaClass(className("Zabbix", config.getSourceName()));
            c.imports(ZabbixConfig.class.getName());
            c.method("public static ZabbixConfig config()", List.of("return " + c.bean(config) + ";"));
            c.write();
            classes.add(c.name);
        }
        return classes;
    }

    // ---- Hierarchy: the definition file's data, and the class compiled from each matching rule ----

    private String hierarchy() throws Exception {
        final Map<String, Object> definition;
        try (InputStream is = classLoader.getResourceAsStream("hierarchy-definition.yml")) {
            if (is == null) {
                throw new IllegalStateException("hierarchy-definition.yml is not on the classpath");
            }
            definition = new Yaml().load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
        final Map<String, String> classesByRule = new LinkedHashMap<>();
        for (final String line : Files.readAllLines(metaInf.resolve("hierarchy-v2-rules.txt"), StandardCharsets.UTF_8)) {
            final int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                classesByRule.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        final JavaClass c = new JavaClass("HierarchyRegistry");
        c.imports("java.util.Map", "java.util.LinkedHashMap", "java.util.function.BiFunction",
            "java.util.function.Supplier", "org.apache.skywalking.oap.server.core.query.type.Service");
        final List<String> matchers = new ArrayList<>();
        for (final Map.Entry<String, String> rule : classesByRule.entrySet()) {
            matchers.add("m.put(" + literal(rule.getKey()) + ", " + classRef(rule.getValue()) + "::new);");
        }
        final List<String> body = new ArrayList<>();
        body.add("final Map<String, Supplier<BiFunction<Service, Service, Boolean>>> m = new LinkedHashMap<>();");
        body.addAll(matchers);
        body.add("return new HierarchyRules(" + c.expr(section(definition, "hierarchy")) + ", "
            + c.expr(section(definition, "auto-matching-rules")) + ", " + c.expr(section(definition, "layer-levels"))
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
        try (InputStream is = classLoader.getResourceAsStream("layer-extensions.yml")) {
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
            List.of("return " + typedList("LayerDefinition", items) + ";"));
        c.write();
        return c.name;
    }

    // ---- OAL: a class per script, its define and the metrics, dispatchers and disabled sources it generated ----

    private List<String> oal() throws Exception {
        final Properties scripts = loadProperties(metaInf.resolve("oal-scripts.txt"));
        final List<String> classes = new ArrayList<>();
        for (int i = 0; scripts.getProperty("script." + i + ".define") != null; i++) {
            final String p = "script." + i + ".";
            final JavaClass c = new JavaClass(className("Oal", stem(scripts.getProperty(p + "file"))));
            c.imports("java.util.List", "org.apache.skywalking.oap.server.core.oal.rt.OALDefine");
            c.method("public static OALDefine define()",
                List.of("return " + classRef(scripts.getProperty(p + "define")) + ".INSTANCE;"));
            c.chunkedMethod("public static void metrics(final List<Class<?>> l)", "l",
                classLiterals("l.add(", csv(scripts.getProperty(p + "metrics")), ");"));
            c.chunkedMethod("public static void dispatchers(final List<Class<?>> l)", "l",
                classLiterals("l.add(", csv(scripts.getProperty(p + "dispatchers")), ");"));
            c.method("public static List<String> disabledSources()", List.of("return " + typedList("String",
                csv(scripts.getProperty(p + "disabled")).stream().map(RegistryGenerator::literal)
                                                       .collect(Collectors.toList())) + ";"));
            c.write();
            classes.add(c.name);
        }
        return classes;
    }

    // ---- Meters: the meter classes MeterSystem generated, by class name ----

    private String meters() throws Exception {
        final List<String> puts = new ArrayList<>();
        for (final String manifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            for (final String line : readLines(manifest)) {
                final String className = line.substring(line.indexOf('=') + 1).trim();
                puts.add("m.put(" + literal(className) + ", " + classRef(className) + ".class);");
            }
        }
        final JavaClass c = new JavaClass("MeterRegistry");
        c.imports("java.util.Map");
        c.chunkedMethod("public static void classes(final Map<String, Class<?>> m)", "m", puts);
        c.write();
        return c.name;
    }

    // ---- The index the runtime replacements read ----

    private void index(final List<String> malClasses, final List<String> lalClasses, final List<String> talClasses,
                       final List<String> zabbixClasses, final String hierarchy, final String layers,
                       final List<String> oalClasses, final String meters) throws Exception {
        final JavaClass c = new JavaClass("CompiledRules");
        c.imports("java.util.ArrayList", "java.util.Collections", "java.util.HashMap", "java.util.List",
            "java.util.Map", "java.util.function.Supplier", LayerDefinition.class.getName(), ZabbixConfig.class.getName(),
            "org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression",
            "org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalFilter",
            "org.apache.skywalking.oap.server.core.oal.rt.OALDefine");
        c.javadoc("The rules compiled into this image, read by the runtime replacements in place of rule files. Every "
            + "call builds fresh rule objects, as reading the files did; the lookup maps are built once.");
        c.method("private CompiledRules()", List.of());
        c.method("public static List<MalFile> malFiles()", collect("MalFile", malClasses, ".file()"));
        c.method("public static Map<String, Supplier<MalExpression>> malExpressions()",
            List.of("return MalExpressions.MAP;"));
        c.method("public static Map<String, Supplier<MalFilter>> malFilters()", List.of("return MalFilters.MAP;"));
        c.method("public static List<LalFile> lalFiles()", collect("LalFile", lalClasses, ".file()"));
        c.method("public static List<LalRule> lalRules()", fill("LalRule", lalClasses, ".rules(l);"));
        c.method("public static List<TalFile> talFiles()", collect("TalFile", talClasses, ".file()"));
        c.method("public static List<TalRule> talRules()", fill("TalRule", talClasses, ".rules(l);"));
        c.method("public static List<ZabbixConfig> zabbixConfigs()", collect("ZabbixConfig", zabbixClasses, ".config()"));
        c.method("public static HierarchyRules hierarchy()", List.of("return " + hierarchy + ".rules();"));
        c.method("public static List<LayerDefinition> layerExtensions()", List.of("return " + layers + ".extensions();"));
        c.method("public static List<OALDefine> oalDefines()", collect("OALDefine", oalClasses, ".define()"));
        c.method("public static List<Class<?>> oalMetrics()", fill("Class<?>", oalClasses, ".metrics(l);"));
        c.method("public static List<Class<?>> oalDispatchers()", fill("Class<?>", oalClasses, ".dispatchers(l);"));
        c.method("public static List<String> oalDisabledSources()", fill("String", oalClasses, ".disabledSources());"));
        c.method("public static Map<String, Class<?>> meterClasses()", List.of("return MeterClasses.MAP;"));
        final StringBuilder holders = new StringBuilder();
        holders.append(holder("MalExpressions", "Map<String, Supplier<MalExpression>>",
            malClasses.stream().map(m -> m + ".expressions(m);").collect(Collectors.toList())));
        holders.append(holder("MalFilters", "Map<String, Supplier<MalFilter>>",
            malClasses.stream().map(m -> m + ".filters(m);").collect(Collectors.toList())));
        holders.append(holder("MeterClasses", "Map<String, Class<?>>", List.of(meters + ".classes(m);")));
        c.raw(holders.toString());
        c.write();
    }

    /**
     * @return statements collecting what each class's {@code call} returns into a new list
     */
    private static List<String> collect(final String type, final List<String> classes, final String call) {
        final List<String> statements = new ArrayList<>();
        statements.add("final List<" + type + "> l = new ArrayList<>();");
        classes.forEach(c -> statements.add("l.add(" + c + call + ");"));
        statements.add("return l;");
        return statements;
    }

    /**
     * @return statements letting each class fill a new list through its {@code call}
     */
    private static List<String> fill(final String type, final List<String> classes, final String call) {
        final List<String> statements = new ArrayList<>();
        statements.add("final List<" + type + "> l = new ArrayList<>();");
        classes.forEach(c -> statements.add(call.endsWith("(l);") ? c + call : "l.addAll(" + c + call));
        statements.add("return l;");
        return statements;
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
     * load, or the generated top-level class name as is (generated classes are only in the output directory).
     */
    private String classRef(final String binaryName) {
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
            if (binaryName.contains("$")) {
                throw new IllegalStateException("The registry cannot reference the nested class " + binaryName);
            }
            return binaryName;
        }
    }

    private List<String> classLiterals(final String before, final List<String> names, final String after) {
        return names.stream().map(n -> before + classRef(n) + ".class" + after).collect(Collectors.toList());
    }

    private List<String> readLines(final String manifest) throws Exception {
        final Path path = metaInf.resolve(manifest);
        if (!Files.exists(path)) {
            return List.of();
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream().map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toList());
    }

    private static Properties loadProperties(final Path path) throws Exception {
        final Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /**
     * A section upstream's HierarchyDefinitionService reads without a null check: a missing one would fail its boot.
     * An explicitly empty one is valid.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> section(final Map<String, Object> definition, final String key) {
        if (definition == null || !(definition.get(key) instanceof Map)) {
            throw new IllegalStateException("hierarchy-definition.yml has no " + key + " section");
        }
        return (Map<String, Object>) definition.get(key);
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

    private static String list(final List<String> items) {
        return "RegistrySupport.list(" + String.join(", ", items) + ")";
    }

    private static String typedList(final String type, final List<String> items) {
        return "RegistrySupport.<" + type + ">list(" + String.join(", ", items) + ")";
    }

    private static String numbers(final String csv, final String suffix) {
        if (csv == null || csv.isBlank()) {
            return "List.of()";
        }
        final List<String> values = new ArrayList<>();
        for (final String v : csv.split(",")) {
            values.add(v.trim() + suffix);
        }
        return "List.of(" + String.join(", ", values) + ")";
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
     * A generated source file. Rule objects become factory methods, one per object, so no method grows near the
     * method-code limit whatever a rule file holds.
     */
    private final class JavaClass {
        private final String name;
        private final Set<String> imports = new LinkedHashSet<>();
        private final StringBuilder members = new StringBuilder();
        private String javadoc;
        private int beans;

        private JavaClass(final String name) {
            this.name = name;
        }

        private void imports(final String... types) {
            imports.addAll(List.of(types));
        }

        private void javadoc(final String text) {
            javadoc = text;
        }

        private void raw(final String text) {
            members.append(text);
        }

        private void method(final String signature, final List<String> statements) {
            members.append("    ").append(signature).append(" {\n");
            statements.forEach(s -> members.append("        ").append(s).append('\n'));
            members.append("    }\n\n");
        }

        /**
         * A void method whose statements only fill its parameter, split into helpers of a bounded size.
         */
        private void chunkedMethod(final String signature, final String parameter, final List<String> statements) {
            if (statements.size() <= STATEMENTS_PER_METHOD) {
                method(signature, statements);
                return;
            }
            final String parameterDeclaration = signature.substring(signature.indexOf('(') + 1, signature.lastIndexOf(')'));
            final List<String> calls = new ArrayList<>();
            for (int from = 0; from < statements.size(); from += STATEMENTS_PER_METHOD) {
                final String helper = "part" + calls.size();
                method("private static void " + helper + "(" + parameterDeclaration + ")",
                    statements.subList(from, Math.min(statements.size(), from + STATEMENTS_PER_METHOD)));
                calls.add(helper + "(" + parameter + ");");
            }
            method(signature, calls);
        }

        /**
         * A rule object as a call to a factory method that builds it with the setters of every property whose value
         * differs from a new instance's.
         */
        private String bean(final Object bean) throws Exception {
            final Class<?> type = bean.getClass();
            final Object defaults = type.getConstructor().newInstance();
            final String typeName = type.getCanonicalName();
            final List<String> statements = new ArrayList<>();
            statements.add("final " + typeName + " o = new " + typeName + "();");
            for (final PropertyDescriptor property : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                if (property.getReadMethod() == null || property.getWriteMethod() == null) {
                    continue;
                }
                final Object value = property.getReadMethod().invoke(bean);
                if (Objects.equals(value, property.getReadMethod().invoke(defaults))) {
                    continue;
                }
                statements.add("o." + property.getWriteMethod().getName() + "(" + expr(value) + ");");
            }
            statements.add("return o;");
            final String method = "b" + beans++;
            method("private static " + typeName + " " + method + "()", statements);
            return method + "()";
        }

        private String expr(final Object value) throws Exception {
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
                return list(items);
            }
            if (value instanceof Map) {
                final List<String> items = new ArrayList<>();
                for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    items.add(expr(entry.getKey()));
                    items.add(expr(entry.getValue()));
                }
                return "RegistrySupport.map(" + String.join(", ", items) + ")";
            }
            return bean(value);
        }

        private void write() throws Exception {
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
