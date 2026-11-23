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

package org.apache.skywalking.oap.server.graalvm;

import java.util.Set;
import org.apache.skywalking.oap.server.buildtools.common.AcceptedModules;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;
import org.yaml.snakeyaml.Yaml;

/**
 * Which staged rule files the image carries, as the build decides it: a rule file is YAML (OAL scripts aside), every
 * one is active, one yielding no rule too (upstream's loader reads it), but a TAL file whose {@code requiredModules}
 * name a module the image does not ship is never loaded at boot, so the build carries none of it.
 */
public final class CarriedRuleFiles {
    private static final Set<String> SHIPPED_MODULES = AcceptedModules.shippedModuleNames();

    /**
     * The modules the image ships, asked as upstream's hash-map-backed module manager is (a null name is none of them).
     */
    public static final ModuleManager SHIPPED = new ModuleManager("shipped") {
        @Override
        public boolean has(final String moduleName) {
            return moduleName != null && SHIPPED_MODULES.contains(moduleName);
        }
    };

    private CarriedRuleFiles() {
    }

    public static boolean isRuleFileName(final String fileName) {
        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
    }

    /**
     * @return a TAL file bound as upstream's loader binds it, null for a document of comments only
     */
    public static TALConfigs talConfigs(final String text) {
        return new Yaml().loadAs(text, TALConfigs.class);
    }

    /**
     * @param configs a TAL file as upstream's loader binds it, null for one of comments only
     * @return whether the image carries it: upstream's boot loads it with the modules the image ships (a file of comments
     *     only requires none), whether or not it yields rules
     */
    public static boolean isCarriedTalFile(final TALConfigs configs) {
        return configs == null || configs.requiredModulesLoaded(SHIPPED);
    }
}
