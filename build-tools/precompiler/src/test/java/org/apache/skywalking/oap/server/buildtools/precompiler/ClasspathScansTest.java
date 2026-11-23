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
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClasspathScansTest {

    @Test
    void everyAnnotationAnUpstreamAnnotationListenerListensForGetsAManifest() throws Exception {
        final Set<String> annotations = ClasspathScans.listenedAnnotations(
            ClassPath.from(Precompiler.class.getClassLoader()).getTopLevelClassesRecursive("org.apache.skywalking"))
            .stream().map(Class::getName).collect(Collectors.toSet());
        // DefaultScopeDefine.Listener, StreamAnnotationListener, DisableRegister and its SingleDisableScanListener
        assertEquals(Set.of(
            "org.apache.skywalking.oap.server.core.source.ScopeDeclaration",
            "org.apache.skywalking.oap.server.core.analysis.Stream",
            "org.apache.skywalking.oap.server.core.analysis.MultipleDisable",
            "org.apache.skywalking.oap.server.core.analysis.Disable"), annotations);
    }
}
