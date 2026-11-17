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

package org.apache.skywalking.oap.server.core.analysis;

import java.util.List;
import java.util.ServiceLoader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;

/**
 * GraalVM replacement for upstream LayerExtensionLoader.
 * Original: skywalking/oap-server/server-core/src/main/java/.../analysis/LayerExtensionLoader.java
 * Repackaged into server-core-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: registers the {@code layer-extensions.yml} entries the precompiler read at build time, from the generated
 * {@link CompiledRules} registry, instead of reading the file at boot; the {@link LayerExtension} SPI still runs, it
 * is compiled code.
 * Why: The distro reads no rule file at runtime; layers are part of the compiled rules (MAL, LAL and TAL rules name
 * them).
 */
@Slf4j
public final class LayerExtensionLoader {

    private LayerExtensionLoader() {
    }

    public static void load() {
        for (final LayerDefinition def : CompiledRules.layerExtensions()) {
            def.register();
        }
        loadFromSpi();
        log.info("Layer registry after compiled layer-extensions + SPI extension loading: {}",
            Layer.describeRegistry());
    }

    /** Kept for API parity with upstream: the shape of {@code layer-extensions.yml}. */
    @Data
    public static class LayerExtensionsFile {
        private List<LayerDefinition> layers;
    }

    static void loadFromSpi() {
        for (final LayerExtension extension : ServiceLoader.load(LayerExtension.class)) {
            log.info("Applying LayerExtension SPI: {}", extension.getClass().getName());
            extension.contribute();
        }
    }
}
