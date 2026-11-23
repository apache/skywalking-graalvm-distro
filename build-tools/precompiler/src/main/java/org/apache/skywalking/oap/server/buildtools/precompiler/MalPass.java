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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.meter.analyzer.v2.MetricConvert;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.ZabbixMetrics;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;

import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.scanV2Classes;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeConfigData;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeEscapedProperties;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;

/**
 * The MAL pass: compiles every MAL rule file, Zabbix's per host pattern, as upstream's MetricConvert does, on the
 * build-time MeterSystem.
 */
@Slf4j
final class MalPass {
    private static final String MAL_RT_PACKAGE =
        "org.apache.skywalking.oap.meter.analyzer.v2.compiler.rt.";

    /**
     * Pre-compile all MAL (Meter Analysis Language) rules using v2 ANTLR4 + Javassist engine.
     *
     * Configures the v2 DSL's static MALClassGenerator to write .class files to the output
     * directory, then runs MetricConvert which internally calls DSL.parse() → MALClassGenerator.compile().
     * MeterSystem generates its own Javassist meter function storage classes (orthogonal to MAL).
     */
    static MeterSystem compileMAL(String outputDir,
                                          Map<String, Class<? extends AcceptableValue>> functionRegister)
        throws Exception {
        log.info("MAL: built function register with {} entries", functionRegister.size());

        // Configure v2 DSL and FilterExpression generators to write .class files
        // to the proper package directory structure within outputDir
        File malRtDir = new File(outputDir, MAL_RT_PACKAGE.replace('.', '/'));
        malRtDir.mkdirs();
        DSL.setClassOutputDir(malRtDir);
        FilterExpression.setClassOutputDir(malRtDir);
        DSL.clearCompiled();
        FilterExpression.clearCompiled();

        MeterSystem.setFunctionRegister(functionRegister);
        MeterSystem.setOutputDirectory(outputDir);

        MeterSystem meterSystem = new MeterSystem(null);
        int totalRules = 0;
        Map<String, List<Rule>> rulesByPath = new LinkedHashMap<>();
        for (RuleCatalog catalog : RuleCatalog.malRules()) {
            String path = catalog.getDirectory();
            List<Rule> rules = loadAndCompileRules(path, RuleTree.ruleNames(path), meterSystem);
            totalRules += rules.size();
            rulesByPath.put(path, rules);
        }
        // Zabbix rules, which the registry carries as ZabbixConfigs
        totalRules += compileZabbixRules(RuleCatalog.ZABBIX_RULES.getDirectory(), meterSystem);

        // Write manifests
        Path metaInf = Path.of(outputDir, "META-INF");

        // MeterSystem exported classes (Javassist meter function storage classes)
        writeManifest(metaInf.resolve("mal-meter-classes.txt"), meterSystem.getExportedClasses());

        // Scan for v2 generated MalExpression/MalFilter .class files
        List<String> malV2Classes = scanV2Classes(outputDir, MAL_RT_PACKAGE);
        writeManifest(metaInf.resolve("mal-v2-classes.txt"), malV2Classes);

        writeMalRules(metaInf);

        log.info("MAL pre-compilation: {} rules, {} meter classes, {} v2 expression classes, {} filters",
            totalRules,
            meterSystem.getExportedClasses().size(),
            DSL.COMPILED.size(),
            FilterExpression.COMPILED.size());

        // The config data as JSON, which the rule registry is generated from
        serializeMALConfigData(outputDir, rulesByPath);
        return meterSystem;
    }

    /**
     * Load rules from a resource directory and compile them through the MAL pipeline.
     * Returns the loaded rules for config data serialization. A file that does not load or a rule that does not
     * compile fails the build: the image would boot without it, where upstream fails to boot.
     */
    static List<Rule> loadAndCompileRules(String path, List<String> enabledPatterns,
                                                  MeterSystem meterSystem) {
        if (enabledPatterns.isEmpty()) {
            log.info("MAL: no rule files under {}", path);
            return new ArrayList<>();
        }
        List<Rule> loadedRules;
        try {
            loadedRules = Rules.loadRules(path, enabledPatterns);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load MAL rules from " + path, e);
        }
        for (Rule rule : loadedRules) {
            try {
                new MetricConvert(rule, meterSystem);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to compile MAL rule " + rule.getName() + " of " + path, e);
            }
        }
        log.info("MAL: compiled {} rules from {}", loadedRules.size(), path);
        return loadedRules;
    }

    /**
     * Compiles the Zabbix rule files as the Zabbix receiver does at boot: upstream's loader reads them and upstream's
     * {@code ZabbixMetrics} initialises its host groups over them, compiling each file's expressions once a host
     * pattern of it reaches them (once per coordinate and text, as the runtime registry holds them). A file without a
     * host pattern compiles nothing; a host pattern that is no regular expression fails the build, as it fails the
     * boot.
     *
     * @return the number of rule files
     */
    static int compileZabbixRules(final String path, final MeterSystem meterSystem) throws IOException {
        return compileZabbixRules(path, RuleTree.ruleNames(path), meterSystem);
    }

    static int compileZabbixRules(final String path, final List<String> names, final MeterSystem meterSystem) {
        final List<ZabbixConfig> configs;
        try {
            configs = ZabbixConfigs.loadConfigs(path, names);
        } catch (ModuleStartException e) {
            throw new IllegalStateException("Failed to load Zabbix rules from " + path, e);
        }
        try {
            new ZabbixMetrics(configs, meterSystem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise the Zabbix rules of " + path + " as the Zabbix "
                + "receiver does", e);
        }
        log.info("MAL: compiled {} rules from {}", configs.size(), path);
        return configs.size();
    }

    /**
     * {@code mal-v2-rules.txt}: every MAL expression and filter the pass compiled, with the coordinates, metric name
     * and text {@code MetricConvert} passed, by which the rule registry keys the classes.
     */
    private static void writeMalRules(Path metaInf) throws IOException {
        Properties rules = new Properties();
        int i = 0;
        for (DSL.Compiled compiled : DSL.COMPILED) {
            String p = "expression." + i++ + ".";
            rules.setProperty(p + "source", compiled.sourceRef().getYamlFile());
            rules.setProperty(p + "line", String.valueOf(compiled.sourceRef().getYamlLine()));
            rules.setProperty(p + "name", compiled.metricName());
            rules.setProperty(p + "text", compiled.expression());
            rules.setProperty(p + "class", compiled.className());
        }
        i = 0;
        for (FilterExpression.Compiled compiled : FilterExpression.COMPILED) {
            String p = "filter." + i++ + ".";
            rules.setProperty(p + "source", compiled.sourceRef().getYamlFile());
            rules.setProperty(p + "line", String.valueOf(compiled.sourceRef().getYamlLine()));
            rules.setProperty(p + "text", compiled.literal());
            rules.setProperty(p + "class", compiled.className());
        }
        writeEscapedProperties(metaInf.resolve("mal-v2-rules.txt"), rules);
    }

    /**
     * Serialize what upstream's loader read from the MAL rule files (Rules) as JSON, which the rule registry is
     * generated from.
     */
    private static void serializeMALConfigData(String outputDir,
                                               Map<String, List<Rule>> rulesByPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path configDataDir = Path.of(outputDir, "META-INF", "config-data");
        Files.createDirectories(configDataDir);

        // Since 11.0.0 upstream loads meter-analyzer-config through the same Rules path, so it is no special case
        for (Map.Entry<String, List<Rule>> entry : rulesByPath.entrySet()) {
            String path = entry.getKey();
            List<Rule> rules = entry.getValue();
            writeConfigData(mapper, configDataDir.resolve(path + ".json"), rules, new TypeReference<List<Rule>>() {
            });
            log.info("Serialized {} Rule entries from {} to config-data JSON", rules.size(), path);
        }
    }
}
