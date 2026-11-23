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
 *
 */

package org.apache.skywalking.oap.server.graalvm.lal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.CompiledLalRuleLookup;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.log.analyzer.v2.provider.log.listener.LogFilterListener;
import org.apache.skywalking.oap.log.analyzer.v2.spi.LALSourceTypeProvider;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.analysis.LayerDefinition;
import org.apache.skywalking.oap.server.core.analysis.LayerExtensionLoader;
import org.apache.skywalking.oap.server.graalvm.CarriedRuleFiles;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalRule;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfigs;

/**
 * Upstream's own boot decisions for a LAL rule, as the oracle the image is checked against: its package-private
 * {@code LogFilterListener.Factory} predicates, with every layer the image registers (layer-extensions.yml, the SPI,
 * every MAL, LAL and TAL file's inline definitions, a TAL file only when the image ships the modules it requires; the
 * precompiler registers the same before compiling LAL).
 */
public final class UpstreamLalBoot {
    private static final Method IS_REGISTERED_LAYER;
    private static final Method SAVES_WITHOUT_LAYER;
    private static final Method RESOLVE_INPUT;
    private static final Method RESOLVE_OUTPUT;
    private static final Map<Layer, LALSourceTypeProvider> PROVIDERS = new HashMap<>();

    static {
        try {
            IS_REGISTERED_LAYER = LogFilterListener.Factory.class.getDeclaredMethod("isRegisteredLayer", String.class);
            SAVES_WITHOUT_LAYER = LogFilterListener.Factory.class.getDeclaredMethod("savesWithoutLayer", LALConfig.class);
            RESOLVE_INPUT = LogFilterListener.Factory.class.getDeclaredMethod(
                "resolveInputType", LALConfig.class, LALSourceTypeProvider.class);
            RESOLVE_OUTPUT = LogFilterListener.Factory.class.getDeclaredMethod(
                "resolveOutputType", LALConfig.class, LALSourceTypeProvider.class);
            IS_REGISTERED_LAYER.setAccessible(true);
            SAVES_WITHOUT_LAYER.setAccessible(true);
            RESOLVE_INPUT.setAccessible(true);
            RESOLVE_OUTPUT.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException("upstream LogFilterListener.Factory changed: update this oracle", e);
        }
        for (final LALSourceTypeProvider provider : ServiceLoader.load(LALSourceTypeProvider.class)) {
            PROVIDERS.put(provider.layer(), provider);
        }
    }

    private UpstreamLalBoot() {
    }

    public static void registerLayers() throws Exception {
        LayerExtensionLoader.load();
        for (final MalFile file : CompiledRules.malFiles()) {
            if (file.getRule().getLayerDefinitions() != null) {
                file.getRule().getLayerDefinitions().forEach(LayerDefinition::register);
            }
        }
        final List<String> lal = new ArrayList<>();
        CompiledRules.lalFiles().forEach(f -> lal.add(stem(f.getName())));
        LALConfigs.load("lal", lal);
        // As the boot: a TAL file requiring a module the image does not ship is skipped, its layers unregistered. The
        // test class path also has upstream modules the image leaves out.
        final List<String> tal = new ArrayList<>();
        CompiledRules.talFiles().forEach(f -> tal.add(stem(f.getFileName())));
        TALConfigs.load("tal", tal, CarriedRuleFiles.SHIPPED);
    }

    /**
     * @return whether upstream skips the rule at boot, with a warning, before resolving or compiling it
     */
    static boolean skips(final LALConfig rule) throws Exception {
        if (!LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer())
            && !(Boolean) IS_REGISTERED_LAYER.invoke(null, rule.getLayer())) {
            return true;
        }
        return SAVES_WITHOUT_LAYER.invoke(null, rule) != null;
    }

    /**
     * @return the input type upstream's boot passes the rule's {@code DSL.of}, resolved by its own
     *     {@code LogFilterListener.Factory}
     */
    static Class<?> inputType(final LALConfig rule) throws Exception {
        return (Class<?>) RESOLVE_INPUT.invoke(null, rule, provider(rule));
    }

    /**
     * @return the output type upstream's boot passes the rule's {@code DSL.of}
     */
    static Class<?> outputType(final LALConfig rule) throws Exception {
        return (Class<?>) RESOLVE_OUTPUT.invoke(null, rule, provider(rule));
    }

    /**
     * @return the class the runtime DSL runs for a rule upstream's boot does not skip, found as it finds it; null when
     *     none was compiled for it
     */
    static LalRule compiledRule(final LALConfig rule) throws Exception {
        return CompiledLalRuleLookup.find(rule, inputType(rule), outputType(rule));
    }

    /**
     * @return what the build compiled the rule's class from, which tells it from every other compiled rule (the
     *     registry's factories build new objects on every call)
     */
    static List<String> compiledFrom(final LalRule rule) {
        // A list keeps a rule without a name apart from one named "null".
        return Arrays.asList(rule.getSource(), String.valueOf(rule.getLine()), rule.getName(), rule.getDslSha256(),
            rule.getRequestedInputType(), rule.getRequestedOutputType());
    }

    private static LALSourceTypeProvider provider(final LALConfig rule) {
        return LALConfig.LAYER_AUTO.equalsIgnoreCase(rule.getLayer()) ? null : PROVIDERS.get(Layer.nameOf(rule.getLayer()));
    }

    private static String stem(final String file) {
        return file.substring(0, file.lastIndexOf('.'));
    }
}
