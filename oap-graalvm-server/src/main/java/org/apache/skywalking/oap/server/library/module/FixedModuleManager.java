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

package org.apache.skywalking.oap.server.library.module;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A ModuleManager that uses fixed module wiring instead of ServiceLoader discovery.
 * Modules and providers are explicitly registered before initialization.
 */
public class FixedModuleManager extends ModuleManager {

    private final Map<String, ModuleWiringBridge.ModuleBinding> bindings = new LinkedHashMap<>();
    private final Map<String, ModuleDefine> loadedModules = new LinkedHashMap<>();

    public FixedModuleManager(String description) {
        super(description);
    }

    /**
     * Register a module with its chosen provider.
     * The module name is derived from {@link ModuleDefine#name()}.
     */
    public void register(ModuleDefine moduleDefine, ModuleProvider provider) {
        bindings.put(moduleDefine.name(), new ModuleWiringBridge.ModuleBinding(moduleDefine, provider));
    }

    /**
     * Initialize all registered modules using fixed wiring (no ServiceLoader).
     */
    public void initFixed(ApplicationConfiguration configuration)
        throws ModuleNotFoundException, ProviderNotFoundException, ServiceNotProvidedException,
        CycleDependencyException, ModuleConfigException, ModuleStartException {
        ModuleWiringBridge.initAll(this, bindings, configuration);
        // Populate our loaded modules map for find()/has() lookups
        for (Map.Entry<String, ModuleWiringBridge.ModuleBinding> entry : bindings.entrySet()) {
            loadedModules.put(entry.getKey(), entry.getValue().moduleDefine());
        }
    }

    @Override
    public boolean has(String moduleName) {
        return loadedModules.containsKey(moduleName);
    }

    @Override
    public ModuleProviderHolder find(String moduleName) throws ModuleNotFoundRuntimeException {
        ModuleDefine module = loadedModules.get(moduleName);
        if (module != null) {
            return module;
        }
        throw new ModuleNotFoundRuntimeException(moduleName + " missing.");
    }
}
