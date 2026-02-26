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

package org.apache.skywalking.oap.server.buildtools.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.skywalking.oap.server.library.module.ModuleDefine;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;

/**
 * Build-time utility that discovers accepted {@link ModuleProvider} implementations
 * via {@link ServiceLoader} and filters them against {@link AcceptedModules}.
 */
public final class ProviderDiscovery {

    /**
     * Discover all accepted providers available on the current classpath.
     * Uses ServiceLoader to find ModuleProvider implementations, then filters
     * by the accepted (moduleName, providerName) pairs.
     *
     * @return list of accepted ModuleProvider instances found on classpath
     */
    public static List<ModuleProvider> discoverAccepted() {
        Map<String, Set<String>> accepted = AcceptedModules.byModule();
        List<ModuleProvider> result = new ArrayList<>();
        Set<AcceptedModules.ModuleProviderPair> found = new HashSet<>();

        // Read META-INF/services file directly and load each provider class
        // independently, so providers whose dependencies are excluded from
        // *-for-graalvm shaded JARs are skipped without affecting the rest.
        for (String providerClassName : loadServiceNames()) {
            ModuleProvider provider;
            try {
                Class<?> clazz = Class.forName(providerClassName);
                provider = (ModuleProvider) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception | NoClassDefFoundError e) {
                System.err.println("  SKIP provider " + providerClassName
                    + " (class load failed): " + e.getMessage());
                continue;
            }
            String moduleName = resolveModuleName(provider);
            if (moduleName == null) {
                continue;
            }
            String providerName = provider.name();
            Set<String> acceptedProviders = accepted.get(moduleName);
            if (acceptedProviders != null && acceptedProviders.contains(providerName)) {
                result.add(provider);
                found.add(new AcceptedModules.ModuleProviderPair(moduleName, providerName));
            }
        }

        // Warn about accepted pairs not found on classpath
        for (AcceptedModules.ModuleProviderPair pair : AcceptedModules.ACCEPTED) {
            if (!found.contains(pair)) {
                System.err.println("WARNING: Accepted provider not found on classpath: "
                    + pair.moduleName() + "/" + pair.providerName());
            }
        }

        return result;
    }

    /**
     * Discover config class FQCNs from all accepted providers on the current classpath.
     * Providers with null ConfigCreator or null config type are skipped.
     *
     * @return ordered set of config class FQCNs
     */
    public static Set<String> discoverConfigClasses() {
        Set<String> configClasses = new LinkedHashSet<>();
        for (ModuleProvider provider : discoverAccepted()) {
            try {
                ModuleProvider.ConfigCreator<?> creator = provider.newConfigCreator();
                if (creator == null) {
                    System.out.println("  SKIP (null ConfigCreator): " + provider.getClass().getName());
                    continue;
                }
                Class<?> configType = creator.type();
                if (configType == null) {
                    System.out.println("  SKIP (null config type): " + provider.getClass().getName());
                    continue;
                }
                configClasses.add(configType.getName());
            } catch (Exception e) {
                System.err.println("  ERROR discovering config for " + provider.getClass().getName()
                    + ": " + e.getMessage());
            }
        }
        return configClasses;
    }

    private static String resolveModuleName(ModuleProvider provider) {
        try {
            Class<? extends ModuleDefine> moduleClass = provider.module();
            ModuleDefine moduleInstance = moduleClass.getDeclaredConstructor().newInstance();
            return moduleInstance.name();
        } catch (Exception e) {
            System.err.println("  ERROR resolving module name for " + provider.getClass().getName()
                + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Read provider class names from META-INF/services without loading any classes.
     * This allows individual provider loading to fail gracefully.
     */
    private static List<String> loadServiceNames() {
        String serviceFile = "META-INF/services/" + ModuleProvider.class.getName();
        Set<String> names = new LinkedHashSet<>();
        try {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader()
                .getResources(serviceFile);
            while (urls.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        if (comment >= 0) {
                            line = line.substring(0, comment);
                        }
                        line = line.trim();
                        if (!line.isEmpty()) {
                            names.add(line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + serviceFile, e);
        }
        return new ArrayList<>(names);
    }

    private ProviderDiscovery() {
    }
}
