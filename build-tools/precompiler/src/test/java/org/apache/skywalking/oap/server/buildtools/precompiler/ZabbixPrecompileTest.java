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
import java.util.List;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.DSL;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.FilterExpression;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zabbix rule files compile as the Zabbix receiver compiles them at boot, through upstream's {@code ZabbixMetrics}:
 * a file's expressions compile for its host patterns, so the build fails where the boot fails and passes where it
 * passes.
 */
class ZabbixPrecompileTest {
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
    void aHostPatternThatIsNoRegularExpressionFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> MalPass.compileZabbixRules("zabbix-fixture-bad-pattern", List.of("agent"), meterSystem));
        assertTrue(e.getMessage().contains("as the Zabbix receiver does"), e.getMessage());
    }

    @Test
    void aFileWithoutHostPatternsCompilesNothingAndPasses() {
        assertEquals(1, MalPass.compileZabbixRules("zabbix-fixture-no-hosts", List.of("agent"), meterSystem));
        assertEquals(List.of(), DSL.COMPILED);
    }
}
