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

package org.apache.skywalking.oap.log.analyzer.v2.provider.log.listener;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Map;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LogAnalyzerModuleConfig;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;

/**
 * Upstream {@code LogFilterListener.Factory}'s own decisions on a static LAL rule, for the precompiler: whether the
 * boot skips it, and the input and output types the boot compiles it with. The factory's package-private members are
 * called directly, so a change to them fails the precompiler's compilation; its private resolvers are looked up when
 * this class loads, so a change to them fails the precompile step.
 */
public final class LalStaticRuleResolver {
    private static final MethodHandle RESOLVE_INPUT_TYPE;
    private static final MethodHandle RESOLVE_OUTPUT_TYPE;
    private static final MethodHandle OUTPUT_BUILDER_NAMES;
    private static final VarHandle SPI_PROVIDERS;

    static {
        try {
            final MethodHandles.Lookup factory =
                MethodHandles.privateLookupIn(LogFilterListener.Factory.class, MethodHandles.lookup());
            final MethodType resolve = MethodType.methodType(Class.class, LALConfig.class, LALSourceTypeProvider.class);
            RESOLVE_INPUT_TYPE = factory.findStatic(LogFilterListener.Factory.class, "resolveInputType", resolve);
            RESOLVE_OUTPUT_TYPE = factory.findStatic(LogFilterListener.Factory.class, "resolveOutputType", resolve);
            OUTPUT_BUILDER_NAMES = factory.findStatic(
                LogFilterListener.Factory.class, "loadOutputBuilderNames", MethodType.methodType(Map.class));
            SPI_PROVIDERS = factory.findVarHandle(LogFilterListener.Factory.class, "spiProviders", Map.class);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Upstream LogFilterListener.Factory changed: update LalStaticRuleResolver", e);
        }
    }

    private final Map<Layer, LALSourceTypeProvider> sourceTypeProviders;

    /**
     * Builds upstream's factory as the log analyzer does, which scans the {@link LALSourceTypeProvider}s and refuses
     * one whose input type LAL does not take.
     */
    @SuppressWarnings("unchecked")
    public LalStaticRuleResolver() throws Exception {
        this.sourceTypeProviders = (Map<Layer, LALSourceTypeProvider>) SPI_PROVIDERS.get(
            new LogFilterListener.Factory(null, new LogAnalyzerModuleConfig()));
    }

    /**
     * @return whether upstream's {@code loadStaticRules} skips the rule with a warning before it resolves or compiles
     *     it: one of a layer no source registered, or a {@code layer: auto} rule that saves logs without a layer
     */
    public static boolean skippedAtBoot(final LALConfig rule) {
        return !LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer())
            && !LogFilterListener.Factory.isRegisteredLayer(rule.getLayer())
            || LogFilterListener.Factory.savesWithoutLayer(rule) != null;
    }

    public Class<?> inputType(final LALConfig rule) throws ModuleStartException {
        return resolve(RESOLVE_INPUT_TYPE, rule);
    }

    public Class<?> outputType(final LALConfig rule) throws ModuleStartException {
        return resolve(RESOLVE_OUTPUT_TYPE, rule);
    }

    public Collection<LALSourceTypeProvider> sourceTypeProviders() {
        return sourceTypeProviders.values();
    }

    /**
     * @return the {@code LALOutputBuilder} implementations an {@code outputType} short name resolves to
     */
    @SuppressWarnings("unchecked")
    public static Collection<Class<?>> outputBuilders() {
        try {
            return ((Map<String, Class<?>>) OUTPUT_BUILDER_NAMES.invoke()).values();
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private Class<?> resolve(final MethodHandle resolver, final LALConfig rule) throws ModuleStartException {
        final LALSourceTypeProvider provider = LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer())
            ? null : sourceTypeProviders.get(LogFilterListener.Factory.resolveLayer(rule));
        try {
            return (Class<?>) resolver.invoke(rule, provider);
        } catch (final ModuleStartException | RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
