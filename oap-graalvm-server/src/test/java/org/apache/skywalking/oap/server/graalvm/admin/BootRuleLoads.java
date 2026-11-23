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

package org.apache.skywalking.oap.server.graalvm.admin;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfigs;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.Rules;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.RuleCatalog;

/**
 * The rule loads of a boot, through the replacement loaders, with every compiled file of a catalog as its setting is
 * filled: what the read-only rule catalog then lists, from upstream's {@link StaticRuleRegistry}.
 */
public final class BootRuleLoads {
    private BootRuleLoads() {
    }

    /**
     * Empties the registry and loads the MAL and LAL catalogs of the given modules, as their providers do at boot.
     */
    public static void bootWith(final Set<RuleCatalog> loaded) throws Exception {
        final Field content = StaticRuleRegistry.class.getDeclaredField("staticContent");
        content.setAccessible(true);
        ((Map<?, ?>) content.get(StaticRuleRegistry.active())).clear();
        final Map<String, String> activation = CompiledRules.activation();
        for (final RuleCatalog catalog : RuleCatalog.malRules()) {
            if (loaded.contains(catalog)) {
                Rules.loadRules(catalog.getDirectory(), files(activation, catalog));
            }
        }
        if (loaded.contains(RuleCatalog.LAL)) {
            LALConfigs.load(RuleCatalog.LAL.getDirectory(), files(activation, RuleCatalog.LAL));
        }
    }

    private static List<String> files(final Map<String, String> activation, final RuleCatalog catalog) {
        return Arrays.stream(activation.get(catalog.setting().key()).split(",")).map(String::trim)
                     .filter(name -> !name.isEmpty()).collect(Collectors.toList());
    }
}
