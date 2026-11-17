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
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rule the image would boot without fails the build instead, as upstream fails the boot on it.
 */
class PrecompileFailureTest {
    @TempDir
    Path out;

    @AfterEach
    void tearDown() {
        MeterSystem.reset();
    }

    @Test
    void aMalRuleThatDoesNotCompileFailsTheBuild() throws Exception {
        MeterSystem.setFunctionRegister(Precompiler.meterFunctionRegister(
            ClassPath.from(getClass().getClassLoader())
                     .getTopLevelClassesRecursive("org.apache.skywalking.oap.server.core.analysis.meter.function")));
        MeterSystem.setOutputDirectory(out.toString());
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.loadAndCompileRules("mal-fixture-broken", List.of("*"), new MeterSystem(null)));
        assertTrue(messages(e).contains("broken"), messages(e));
    }

    @Test
    void aLalRuleNamingAnUnknownInputTypeFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.compileLAL(out.toString(), "lal-fixture-bad-input"));
        assertTrue(messages(e).contains("no.such.InputType"), messages(e));
    }

    @Test
    void aLalRuleNamingAnUnknownOutputTypeFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.compileLAL(out.toString(), "lal-fixture-bad-output"));
        assertTrue(messages(e).contains("no.such.OutputBuilder"), messages(e));
    }

    @Test
    void aMissingHierarchyDefinitionFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.compileHierarchy(out.toString(), "no-such-hierarchy-definition.yml"));
        assertTrue(messages(e).contains("no-such-hierarchy-definition.yml"), messages(e));
    }

    @Test
    void aHierarchyDefinitionWithoutMatchingRulesFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.compileHierarchy(out.toString(), "hierarchy-fixture-no-rules.yml"));
        assertTrue(messages(e).contains("auto-matching-rules"), messages(e));
    }

    private static String messages(final Throwable e) {
        final StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append(" / ");
        }
        return messages.toString();
    }
}
