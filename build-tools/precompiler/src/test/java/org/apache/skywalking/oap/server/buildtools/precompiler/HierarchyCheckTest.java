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

import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hierarchy definition the hierarchy service would refuse at boot fails the build.
 */
class HierarchyCheckTest {

    /** The layers Core has when its hierarchy service starts, as the build has them when it checks. */
    @BeforeAll
    static void registerLayerExtensions() throws Exception {
        LayerExtensionLoader.load();
    }

    @Test
    void theStagedHierarchyPasses() {
        assertDoesNotThrow(() -> Precompiler.checkHierarchy("hierarchy-definition.yml"));
    }

    @Test
    void aLevelOfAnUnregisteredLayerFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.checkHierarchy("hierarchy-fixture-unknown-level.yml"));
        assertTrue(e.getMessage().contains("invalid-layer is not a registered layer"), e.getMessage());
    }

    @Test
    void aMalformedEntryFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.checkHierarchy("hierarchy-fixture-malformed.yml"));
        assertTrue(e.getMessage().contains("GENERAL is not an integer level"), e.getMessage());
    }

    @Test
    void aLevelNoHierarchyEntryReadsIsNotChecked() {
        assertDoesNotThrow(() -> Precompiler.checkHierarchy("hierarchy-fixture-unused-level.yml"));
    }

    @Test
    void layersRelatedWithoutAMatchingRulePass() {
        assertDoesNotThrow(() -> Precompiler.checkHierarchy("hierarchy-fixture-no-matcher.yml"));
    }

    @Test
    void aLowerLayerNotNamingAMatchingRuleFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.checkHierarchy("hierarchy-fixture-matcher-number.yml"));
        assertTrue(e.getMessage().contains("GENERAL.K8S_SERVICE does not name a matching rule"), e.getMessage());
    }

    @Test
    void anUpperLayerNotAboveItsLowerLayerFailsTheBuild() {
        final Exception e = assertThrows(IllegalStateException.class,
            () -> Precompiler.checkHierarchy("hierarchy-fixture-level-order.yml"));
        assertTrue(e.getMessage().contains("GENERAL layer-level should be greater than K8S_SERVICE"), e.getMessage());
    }
}
