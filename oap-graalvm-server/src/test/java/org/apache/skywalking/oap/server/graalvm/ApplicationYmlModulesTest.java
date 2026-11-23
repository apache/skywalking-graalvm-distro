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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.buildtools.common.AcceptedModules;
import org.apache.skywalking.oap.server.library.module.ModuleDefine;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot loads the modules {@code application.yml} names through the module SPI, as upstream's
 * {@code ModuleManager} does, so the classpath the distributions ship has to carry exactly the providers the distro
 * accepts and the shipped {@code application.yml} has to name them.
 */
class ApplicationYmlModulesTest {
    private static final Path DISTRO =
        Path.of(System.getProperty("user.dir")).getParent().toAbsolutePath().normalize();
    private static final Path REPACKAGED_LIBS = DISTRO.resolve("oap-libs-for-graalvm");
    private static final String DISTRO_PACKAGE = "org.apache.skywalking.oap.server.graalvm.";
    private static final Pattern SELECTOR = Pattern.compile("\\$\\{SW_[A-Z0-9_]+:([^}]*)}");
    private static final Pattern SHADED_ARTIFACT =
        Pattern.compile("<include>org\\.apache\\.skywalking:([^<]+)</include>");
    private static final Path UPSTREAM_APPLICATION_YML =
        DISTRO.resolve("skywalking/oap-server/server-starter/src/main/resources/application.yml");
    private static final String RUNTIME_RULE_MODULE = "receiver-runtime-rule";
    private static final String STAND_IN_MODULE = "graalvm-unsupported-admin";

    /**
     * A module is turned off by its selector ({@code SW_RECEIVER_PPROF=-}); without one, or without the environment
     * variable, it could not be.
     */
    @Test
    void everyModuleOfTheShippedApplicationYmlHasASelectorItsEnvironmentVariableCanTurnOff() throws IOException {
        final List<String> wrong = new ArrayList<>();
        loadShippedApplicationYml().forEach((module, section) -> {
            final Matcher selector = SELECTOR.matcher(String.valueOf(section.get("selector")));
            if (!selector.matches()
                || (!"-".equals(selector.group(1)) && !providerOptions(section).contains(selector.group(1)))) {
                wrong.add(module + ".selector = " + section.get("selector"));
            }
        });
        assertTrue(wrong.isEmpty(), "Selectors not of the form ${SW_...:<one of the module's providers, or ->}: "
            + wrong);
    }

    @Test
    void theRuntimeClasspathCarriesExactlyTheAcceptedUpstreamProviders() throws Exception {
        final Set<String> accepted = new TreeSet<>(acceptedProviders());

        final Set<String> shipped = new TreeSet<>();
        try (URLClassLoader runtime = runtimeClassLoader()) {
            final Class<?> providerType = runtime.loadClass(ModuleProvider.class.getName());
            for (final Object provider : ServiceLoader.load(providerType, runtime)) {
                if (!provider.getClass().getName().startsWith(DISTRO_PACKAGE)) {
                    shipped.add(provider.getClass().getName());
                }
            }
        }

        assertEquals(accepted, shipped);
    }

    /**
     * The build asks {@code AcceptedModules.shippedModuleNames()} which modules a rule file's {@code requiredModules}
     * can find at boot: exactly the modules the runtime class path defines, the distro's stand-in included.
     */
    @Test
    void theBuildToolsShipExactlyTheModulesTheRuntimeClasspathDefines() throws Exception {
        final Set<String> defined = new TreeSet<>();
        try (URLClassLoader runtime = runtimeClassLoader()) {
            final Class<?> defineType = runtime.loadClass(ModuleDefine.class.getName());
            for (final Object module : ServiceLoader.load(defineType, runtime)) {
                defined.add((String) defineType.getMethod("name").invoke(module));
            }
        }

        assertTrue(defined.contains(STAND_IN_MODULE) && !defined.contains(RUNTIME_RULE_MODULE), defined.toString());
        assertEquals(defined, AcceptedModules.shippedModuleNames());
    }

    /**
     * Every provider option of every module in the shipped {@code application.yml} resolves the way
     * {@code ModuleManager} and {@code ModuleDefine#prepare} resolve it, and every module shipped is there.
     */
    @Test
    void everyProviderOfTheShippedApplicationYmlResolvesToOneShippedProvider() throws Exception {
        final Map<String, List<String>> options = new TreeMap<>();
        loadShippedApplicationYml().forEach((module, section) -> options.put(module, providerOptions(section)));

        final Map<String, List<String>> resolved = new TreeMap<>();
        try (URLClassLoader runtime = runtimeClassLoader()) {
            final Class<?> defineType = runtime.loadClass(ModuleDefine.class.getName());
            final Class<?> providerType = runtime.loadClass(ModuleProvider.class.getName());
            for (final Object module : ServiceLoader.load(defineType, runtime)) {
                final String name = (String) defineType.getMethod("name").invoke(module);
                final List<String> providers = new ArrayList<>();
                for (final Object provider : ServiceLoader.load(providerType, runtime)) {
                    if (providerType.getMethod("module").invoke(provider).equals(module.getClass())) {
                        providers.add((String) providerType.getMethod("name").invoke(provider));
                    }
                }
                providers.sort(String::compareTo);
                resolved.merge(name, providers, (a, b) -> {
                    throw new IllegalStateException("Two module definitions named " + name);
                });
            }
        }

        assertEquals(options, resolved);
    }

    /**
     * The build tools take the shipped providers from {@code AcceptedModules}; a provider missing there gets no config
     * loader branch and the boot fails with "Unknown config type".
     */
    @Test
    void theBuildToolsAcceptExactlyTheInventorysAcceptedProviders() throws Exception {
        final Comparator<AcceptedModules.ModuleProviderPair> byName =
            Comparator.comparing(AcceptedModules.ModuleProviderPair::moduleName)
                      .thenComparing(AcceptedModules.ModuleProviderPair::providerName);
        final Set<AcceptedModules.ModuleProviderPair> inventory = new TreeSet<>(byName);
        for (final String providerClass : acceptedProviders()) {
            final ModuleProvider provider =
                (ModuleProvider) Class.forName(providerClass).getDeclaredConstructor().newInstance();
            inventory.add(new AcceptedModules.ModuleProviderPair(
                provider.module().getDeclaredConstructor().newInstance().name(), provider.name()));
        }
        final Set<AcceptedModules.ModuleProviderPair> accepted = new TreeSet<>(byName);
        accepted.addAll(AcceptedModules.ACCEPTED);
        assertEquals(inventory, accepted);
    }

    /**
     * The shipped {@code application.yml} is upstream's for the modules and providers the image ships, but for the
     * declared differences: the rule-selecting settings are refused (rules are compiled in), the configuration selector
     * defaults to {@code k8s-configmap}, the providers the image does not ship are left out, and
     * {@code graalvm-unsupported-admin}, switched by upstream's variable, stands in for {@code receiver-runtime-rule}.
     */
    @Test
    void theShippedApplicationYmlIsUpstreamsButForTheDeclaredDifferences() throws IOException {
        final Map<String, Map<String, Object>> upstream = loadApplicationYml(UPSTREAM_APPLICATION_YML);
        final Map<String, Map<String, Object>> shipped = loadShippedApplicationYml();
        final Set<String> refused = CompiledRuleActivation.SETTINGS.stream()
            .map(s -> s.module + "." + s.provider + "." + s.field).collect(Collectors.toSet());

        final Set<String> missing = new TreeSet<>(upstream.keySet());
        missing.removeAll(shipped.keySet());
        assertEquals(Set.of(RUNTIME_RULE_MODULE), missing, "upstream modules the shipped application.yml leaves out");

        final List<String> differences = new ArrayList<>();
        shipped.forEach((module, section) -> {
            if (STAND_IN_MODULE.equals(module)) {
                if (!Objects.equals(upstream.get(RUNTIME_RULE_MODULE).get("selector"), section.get("selector"))) {
                    differences.add(module + ".selector = " + section.get("selector"));
                }
                return;
            }
            final Map<String, Object> upstreamSection = upstream.get(module);
            if (upstreamSection == null) {
                differences.add(module + ": not an upstream module");
                return;
            }
            final Object selector = "configuration".equals(module)
                ? "${SW_CONFIGURATION:k8s-configmap}" : upstreamSection.get("selector");
            if (!Objects.equals(selector, section.get("selector"))) {
                differences.add(module + ".selector = " + section.get("selector"));
            }
            for (final String provider : providerOptions(section)) {
                if (!upstreamSection.containsKey(provider)) {
                    differences.add(module + "." + provider + ": not an upstream provider");
                    continue;
                }
                final Map<String, Object> expected = new TreeMap<>(settings(upstreamSection.get(provider)));
                expected.keySet().removeIf(setting -> refused.contains(module + "." + provider + "." + setting));
                final Map<String, Object> actual = new TreeMap<>(settings(section.get(provider)));
                if (!expected.equals(actual)) {
                    differences.add(module + "." + provider + ": " + actual + " instead of " + expected);
                }
            }
        });
        assertTrue(differences.isEmpty(), "Differences from upstream's application.yml: " + differences);
    }

    private static List<String> acceptedProviders() throws IOException {
        final Properties inventory = new Properties();
        try (InputStream in = ApplicationYmlModulesTest.class.getClassLoader()
                                                             .getResourceAsStream("provider-inventory.properties")) {
            inventory.load(in);
        }
        final List<String> accepted = new ArrayList<>();
        inventory.forEach((key, status) -> {
            if ("ACCEPTED".equals(status)) {
                accepted.add(((String) key).substring("provider.".length()));
            }
        });
        return accepted;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings(final Object provider) {
        return provider == null ? Map.of() : (Map<String, Object>) provider;
    }

    private static List<String> providerOptions(final Map<String, Object> section) {
        return section.keySet().stream()
                      .filter(key -> !"selector".equals(key))
                      .sorted()
                      .collect(Collectors.toList());
    }

    private static Map<String, Map<String, Object>> loadShippedApplicationYml() throws IOException {
        return loadApplicationYml(DISTRO.resolve("oap-graalvm-server/src/main/resources/application.yml"));
    }

    private static Map<String, Map<String, Object>> loadApplicationYml(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml().load(reader);
        }
    }

    /**
     * The classes the assemblies package, over the platform class loader: this module's classes and its runtime
     * dependencies (written by maven-dependency-plugin), so the module SPI and every class it loads resolve as on the
     * distribution's classpath, without the test classpath's provided jars. In a reactor build a {@code *-for-graalvm}
     * dependency is its {@code target/classes}, holding only the replacement classes until shade adds its upstream jar;
     * that jar follows it here, so the replacements still win.
     */
    static URLClassLoader runtimeClassLoader() throws IOException {
        final String file = Objects.requireNonNull(System.getProperty("runtime.classpath.file"),
            "runtime.classpath.file is set by the surefire configuration in oap-graalvm-server/pom.xml");
        final List<URL> urls = new ArrayList<>();
        urls.add(DISTRO.resolve("oap-graalvm-server/target/classes").toUri().toURL());
        for (final String entry : Files.readString(Path.of(file)).trim().split(File.pathSeparator)) {
            final Path path = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isDirectory(path) || path.getFileName().toString().endsWith(".jar")) {
                urls.add(path.toUri().toURL());
            }
            if (Files.isDirectory(path) && path.startsWith(REPACKAGED_LIBS)) {
                urls.add(shadedUpstreamJar(REPACKAGED_LIBS.relativize(path).getName(0).toString()).toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /**
     * The upstream jar a {@code *-for-graalvm} module shades (its one artifact-set include), found on the test
     * classpath, where it is a provided dependency.
     */
    private static Path shadedUpstreamJar(final String module) throws IOException {
        final Matcher include =
            SHADED_ARTIFACT.matcher(Files.readString(REPACKAGED_LIBS.resolve(module).resolve("pom.xml")));
        assertTrue(include.find(), module + " shades no org.apache.skywalking artifact");
        final String artifactId = include.group(1);
        final String jarName = Pattern.quote(artifactId) + "-\\d.*\\.jar";
        // Maven repository layout: <artifactId>/<version>/<artifactId>-<version>.jar
        return Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                     .map(Path::of)
                     .filter(path -> path.getNameCount() > 2
                         && path.getFileName().toString().matches(jarName)
                         && path.getParent().getParent().getFileName().toString().equals(artifactId))
                     .findFirst()
                     .orElseThrow(() -> new IllegalStateException(
                         module + " shades " + artifactId + ", which is not on the test classpath"));
    }
}
