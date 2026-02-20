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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ModuleManager that uses fixed module wiring instead of ServiceLoader discovery.
 * Modules and providers are explicitly registered before initialization.
 * Uses the direct-wiring prepare() overload added in the library-module-for-graalvm
 * replacement of ModuleDefine.
 */
public class FixedModuleManager extends ModuleManager {

    private final List<ModuleBinding> bindings = new ArrayList<>();

    public FixedModuleManager(String description) {
        super(description);
    }

    /**
     * Register a module with its chosen provider.
     */
    public void register(ModuleDefine moduleDefine, ModuleProvider provider) {
        bindings.add(new ModuleBinding(moduleDefine, provider));
    }

    /**
     * Initialize all registered modules using fixed wiring (no ServiceLoader).
     * Calls the direct-wiring prepare() overload on ModuleDefine, then uses
     * BootstrapFlow for dependency-ordered start and notify.
     */
    public void initFixed(ApplicationConfiguration configuration)
        throws ModuleNotFoundException, ProviderNotFoundException, ServiceNotProvidedException,
        CycleDependencyException, ModuleConfigException, ModuleStartException {

        Map<String, ModuleDefine> loadedModules = new LinkedHashMap<>();
        TerminalFriendlyTable bootingParameters = getBootingParameters();

        // Phase 1: Prepare all modules using direct provider wiring
        for (ModuleBinding binding : bindings) {
            String moduleName = binding.moduleDefine.name();
            binding.moduleDefine.prepare(
                this,
                binding.provider,
                configuration.getModuleConfiguration(moduleName),
                bootingParameters
            );
            loadedModules.put(moduleName, binding.moduleDefine);
        }

        // Phase 2: Start and notify (using BootstrapFlow for dependency ordering)
        BootstrapFlow bootstrapFlow = new BootstrapFlow(loadedModules);
        bootstrapFlow.start(this);
        bootstrapFlow.notifyAfterCompleted();
    }

    @Override
    public boolean has(String moduleName) {
        return bindings.stream().anyMatch(b -> b.moduleDefine.name().equals(moduleName));
    }

    @Override
    public ModuleProviderHolder find(String moduleName) throws ModuleNotFoundRuntimeException {
        for (ModuleBinding binding : bindings) {
            if (binding.moduleDefine.name().equals(moduleName)) {
                return binding.moduleDefine;
            }
        }
        throw new ModuleNotFoundRuntimeException(moduleName + " missing.");
    }

    private record ModuleBinding(ModuleDefine moduleDefine, ModuleProvider provider) {
    }
}
