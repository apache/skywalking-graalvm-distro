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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every compiled TAL file is active, in {@code nativeTalFiles} or {@code talFiles} by its input, so the registry fails
 * the build on what upstream's boot refuses in {@code nativeTalFiles}.
 */
class TalActivationCheckTest {
    @TempDir
    Path out;

    private MeterSystem meterSystem;

    @BeforeEach
    void setUp() throws Exception {
        Precompiler.declareScopes();
        MeterSystem.setFunctionRegister(Precompiler.meterFunctionRegister(
            ClassPath.from(getClass().getClassLoader())
                     .getTopLevelClassesRecursive("org.apache.skywalking.oap.server.core.analysis.meter.function")));
        MeterSystem.setOutputDirectory(out.toString());
        meterSystem = new MeterSystem(null);
        Files.createDirectories(out.resolve("META-INF"));
    }

    @AfterEach
    void tearDown() {
        MeterSystem.reset();
    }

    /** The files compile, as alternatives would; compiled into one image, all of them would be active. */
    private void compileAndCheck(final String talPath) throws Exception {
        Precompiler.compileTAL(out.toString(), meterSystem, talPath);
        final Properties rules = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("META-INF/tal-v2-rules.txt"), StandardCharsets.UTF_8)) {
            rules.load(reader);
        }
        RegistryGenerator.checkTalActivation(rules);
    }

    @Test
    void aFileMixingNativeAndOtherInputsFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> compileAndCheck("tal-fixture-mixed"));
        assertTrue(e.getMessage().contains("TAL file tal/mixed.yaml mixes sw-native rules"), e.getMessage());
    }

    @Test
    void twoNativeRulesOfOneNameFailTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> compileAndCheck("tal-fixture-native-dup"));
        assertTrue(e.getMessage().contains("native rules"), e.getMessage());
        assertTrue(e.getMessage().contains("are both named same-alias"), e.getMessage());
    }

}
