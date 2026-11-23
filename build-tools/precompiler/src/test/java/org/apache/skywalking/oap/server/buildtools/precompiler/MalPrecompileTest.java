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

import com.google.common.reflect.ClassPath;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rule;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;
import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry holds a rule file's expressions by the keys {@code MetricConvert} passes the DSL, its prefix and suffix
 * injected, which the build checks the compiled expressions against: two rules alike only once the prefix is injected
 * are one expression, as upstream compiles one.
 */
class MalPrecompileTest {
    @TempDir
    Path out;

    private MeterSystem meterSystem;

    @BeforeEach
    void setUp() throws Exception {
        Precompiler.declareScopes();
        MeterSystem.setFunctionRegister(ClasspathScans.meterFunctionRegister(
            ClassPath.from(getClass().getClassLoader())
                     .getTopLevelClassesRecursive("org.apache.skywalking.oap.server.core.analysis.meter.function")));
        MeterSystem.setOutputDirectory(out.toString());
        meterSystem = new MeterSystem(null);
        DSL.setClassOutputDir(out.toFile());
        FilterExpression.setClassOutputDir(out.toFile());
        DSL.clearCompiled();
        FilterExpression.clearCompiled();
    }

    @AfterEach
    void tearDown() {
        MeterSystem.reset();
        DSL.clearCompiled();
        FilterExpression.clearCompiled();
    }

    @Test
    void twoMalRulesAlikeOnceThePrefixIsInjectedAreOneExpression() {
        final List<Rule> rules = MalPass.loadAndCompileRules(
            "mal-fixture-prefix-twins", List.of("*"), meterSystem);
        assertEquals(1, rules.size());
        assertEquals(2, rules.get(0).getMetricsRules().size());
        assertEquals(1, compiled().size(), compiled().toString());
        assertEquals(compiled(), RegistryGenerator.effectiveKeys(rules.get(0)));
    }

    @Test
    void twoZabbixMetricsAlikeOnceThePrefixIsInjectedAreOneExpression() throws Exception {
        assertEquals(1, MalPass.compileZabbixRules("zabbix-fixture-prefix-twins", List.of("agent"), meterSystem));
        final List<ZabbixConfig> configs = ZabbixConfigs.loadConfigs("zabbix-fixture-prefix-twins", List.of("agent"));
        assertEquals(2, configs.get(0).getMetrics().size());
        assertEquals(1, compiled().size(), compiled().toString());
        assertEquals(compiled(), RegistryGenerator.effectiveKeys(configs.get(0)));
    }

    /**
     * Rules a merge key brings in have no line the index finds: {@code MetricConvert} records theirs as -1, and the
     * registry expects them there.
     */
    @Test
    void malRulesAMergeKeyBringsInAreExpectedAtTheUnresolvedLine() {
        final List<Rule> rules = MalPass.loadAndCompileRules("mal-fixture-merged", List.of("*"), meterSystem);
        assertEquals(0, rules.get(0).getMetricsRules().get(0).getLineNo());
        assertEquals("-1", compiled().iterator().next().get(0));
        assertEquals(compiled(), RegistryGenerator.effectiveKeys(rules.get(0)));
    }

    @Test
    void zabbixMetricsAMergeKeyBringsInAreExpectedAtTheUnresolvedLine() throws Exception {
        assertEquals(1, MalPass.compileZabbixRules("zabbix-fixture-merged", List.of("agent"), meterSystem));
        final List<ZabbixConfig> configs = ZabbixConfigs.loadConfigs("zabbix-fixture-merged", List.of("agent"));
        assertEquals("-1", compiled().iterator().next().get(0));
        assertEquals(compiled(), RegistryGenerator.effectiveKeys(configs.get(0)));
    }

    /**
     * A top-level class may have a {@code $} in its name: the meter class of metric {@code review_cost$bytes} is one,
     * and the registry references it as such, deciding nesting from the class, not from its name.
     */
    @Test
    void aGeneratedTopLevelClassWithADollarInItsNameIsReferenced() throws Exception {
        MalPass.loadAndCompileRules("mal-fixture-dollar", List.of("*"), meterSystem);
        final String meterClass = meterSystem.getExportedClasses().stream()
            .filter(line -> line.startsWith("review_cost$bytes="))
            .map(line -> line.substring(line.indexOf('=') + 1)).findFirst().orElseThrow();
        final RegistryGenerator generator = new RegistryGenerator(out, out.resolve("src"));
        assertEquals(meterClass, generator.classRef(meterClass));
        final String expressionClass = DSL.COMPILED.get(0).className();
        assertEquals(expressionClass, generator.classRef(expressionClass));
    }

    private static Set<List<String>> compiled() {
        return DSL.COMPILED.stream()
                           .map(c -> Arrays.asList(String.valueOf(c.sourceRef().getYamlLine()), c.metricName(),
                               c.expression()))
                           .collect(Collectors.toSet());
    }
}
