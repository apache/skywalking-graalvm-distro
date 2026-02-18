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

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import static org.apache.skywalking.oap.server.library.util.YamlConfigLoaderUtils.copyProperties;

/**
 * Bridge class in the same package as ModuleDefine/ModuleProvider to access
 * package-private APIs. Used by the fixed-wiring starter to bypass SPI discovery.
 */
@Slf4j
public class ModuleWiringBridge {

    /**
     * Wire a ModuleDefine with a specific ModuleProvider directly, bypassing ServiceLoader.
     * Replicates the logic in {@link ModuleDefine#prepare} but with a pre-selected provider.
     */
    public static void wireAndPrepare(ModuleManager moduleManager,
                                      ModuleDefine module,
                                      ModuleProvider provider,
                                      ApplicationConfiguration.ModuleConfiguration configuration,
                                      TerminalFriendlyTable bootingParameters)
        throws ModuleConfigException, ModuleStartException, ServiceNotProvidedException {

        // Wire provider to module (package-private setters)
        provider.setManager(moduleManager);
        provider.setModuleDefine(module);
        provider.setBootingParameters(bootingParameters);

        log.info("Prepare the {} provider in {} module.", provider.name(), module.name());

        // Initialize config
        try {
            final ModuleProvider.ConfigCreator creator = provider.newConfigCreator();
            if (creator != null) {
                final Class typeOfConfig = creator.type();
                if (typeOfConfig != null) {
                    final ModuleConfig config = (ModuleConfig) typeOfConfig.getDeclaredConstructor().newInstance();
                    copyProperties(
                        config,
                        configuration.getProviderConfiguration(provider.name()),
                        module.name(),
                        provider.name()
                    );
                    creator.onInitialized(config);
                }
            }
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException |
                 InstantiationException e) {
            throw new ModuleConfigException(
                module.name() + " module config transport to config bean failure.", e);
        }

        // Prepare the provider
        provider.prepare();
    }

    /**
     * Initialize all fixed-wired modules: prepare, start, and notify in dependency order.
     *
     * @param moduleManager  the module manager
     * @param moduleBindings ordered map of module name -> (ModuleDefine, ModuleProvider) pairs
     * @param configuration  the application configuration
     */
    public static void initAll(ModuleManager moduleManager,
                               Map<String, ModuleBinding> moduleBindings,
                               ApplicationConfiguration configuration)
        throws ModuleNotFoundException, ProviderNotFoundException, ServiceNotProvidedException,
        CycleDependencyException, ModuleConfigException, ModuleStartException {

        Map<String, ModuleDefine> loadedModules = new LinkedHashMap<>();
        TerminalFriendlyTable bootingParameters = moduleManager.getBootingParameters();

        // Phase 1: Prepare all modules
        for (Map.Entry<String, ModuleBinding> entry : moduleBindings.entrySet()) {
            String moduleName = entry.getKey();
            ModuleBinding binding = entry.getValue();

            wireAndPrepare(
                moduleManager,
                binding.moduleDefine(),
                binding.provider(),
                configuration.getModuleConfiguration(moduleName),
                bootingParameters
            );
            loadedModules.put(moduleName, binding.moduleDefine());
        }

        // Phase 2: Start and notify (using BootstrapFlow for dependency ordering)
        BootstrapFlow bootstrapFlow = new BootstrapFlow(loadedModules);
        bootstrapFlow.start(moduleManager);
        bootstrapFlow.notifyAfterCompleted();
    }

    /**
     * A binding of a ModuleDefine to its chosen ModuleProvider.
     */
    public record ModuleBinding(ModuleDefine moduleDefine, ModuleProvider provider) {
    }
}
