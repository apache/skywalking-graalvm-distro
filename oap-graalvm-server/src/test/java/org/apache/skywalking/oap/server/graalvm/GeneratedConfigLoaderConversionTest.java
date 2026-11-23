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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.alarm.provider.AlarmModuleConfig;
import org.apache.skywalking.oap.server.library.util.YamlConfigLoaderUtils;
import org.apache.skywalking.oap.server.receiver.envoy.EnvoyMetricReceiverConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The generated config binder converts a value as upstream's {@code Field.set} does: a primitive field takes a value
 * widening to it and refuses a narrowing one with {@code Field.set}'s exception, a reference field an instance of its
 * type or null, and a final field is written through {@code Field.set} itself. Pinned against upstream's own
 * {@code YamlConfigLoaderUtils} with the same inputs.
 */
class GeneratedConfigLoaderConversionTest {
    @Test
    void eachValueConvertsAsUpstreamsFieldSetConvertsIt() throws Exception {
        final List<Object[]> cases = List.of(
            new Object[] {"restPort", 1}, new Object[] {"restPort", (short) 2}, new Object[] {"restPort", (byte) 3},
            new Object[] {"restPort", 'a'}, new Object[] {"restPort", 4L}, new Object[] {"restPort", 1.5},
            new Object[] {"restPort", "5"}, new Object[] {"restIdleTimeOut", 6}, new Object[] {"restIdleTimeOut", 7L},
            new Object[] {"restIdleTimeOut", 1.5}, new Object[] {"restIdleTimeOut", 1.5f},
            new Object[] {"restSSLEnabled", true}, new Object[] {"restSSLEnabled", "true"},
            new Object[] {"role", "Receiver"}, new Object[] {"role", 8},
            new Object[] {"downsampling", new ArrayList<>(List.of("Minute"))}, new Object[] {"downsampling", "Hour"});
        for (final Object[] given : cases) {
            assertSameOutcome(config -> {
                final Properties properties = new Properties();
                properties.put(given[0], given[1]);
                return properties;
            }, Arrays.toString(given) + " of " + given[1].getClass().getName());
        }
    }

    /**
     * {@code Field.set} hands a final list its own list back unchanged: the elements it held stay.
     */
    @Test
    void theFinalListGivenItsOwnListKeepsItsElements() throws Exception {
        assertSameOutcome(config -> {
            final Properties properties = new Properties();
            properties.put("downsampling", config.getDownsampling());
            return properties;
        }, "downsampling given its own list");
    }

    /**
     * A key only the defaults of the properties hold is named, and its value read from the properties themselves, null:
     * upstream assigns that null, to a final list too, and refuses it for a primitive.
     */
    @Test
    void aKeyOfTheDefaultsIsAssignedNullAsUpstreamAssignsIt() throws Exception {
        for (final String key : List.of("downsampling", "role", "restPort")) {
            assertSameOutcome(config -> {
                final Properties defaults = new Properties();
                defaults.put(key, "Minute");
                return new Properties(defaults);
            }, key + " of the defaults");
        }
    }

    /**
     * A final field of another type takes only a value of its type, as {@code Field.set}: a string is refused, and the
     * boot stops, as upstream's.
     */
    @Test
    void aFinalFieldIsRefusedAValueOfAnotherTypeAsUpstreamRefusesIt() throws Exception {
        final Properties bad = new Properties();
        bad.put("listenerMetricsAdapter", "bad");
        try (UpstreamClasses upstream = new UpstreamClasses(YamlConfigLoaderUtils.class)) {
            final IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
                () -> UpstreamClasses.invoke(copy(upstream), new EnvoyMetricReceiverConfig(), bad, "envoy-metric",
                    "default"));
            final IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
                () -> YamlConfigLoaderUtils.copyProperties(new EnvoyMetricReceiverConfig(), bad, "envoy-metric",
                    "default"));
            assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    /**
     * A key naming a static final field goes through {@code Field.set} as upstream's does, which refuses to write a
     * constant with {@code IllegalAccessException}: the boot stops, as upstream's.
     */
    @Test
    void aKeyNamingAStaticFinalFieldIsRefusedAsUpstreamRefusesIt() throws Exception {
        final Properties constant = new Properties();
        constant.put("DEFAULT_HTTP_TIMEOUT_SECONDS", 30L);
        try (UpstreamClasses upstream = new UpstreamClasses(YamlConfigLoaderUtils.class)) {
            final IllegalAccessException expected = assertThrows(IllegalAccessException.class,
                () -> UpstreamClasses.invoke(copy(upstream), new AlarmModuleConfig(), constant, "alarm", "default"));
            final IllegalAccessException actual = assertThrows(IllegalAccessException.class,
                () -> YamlConfigLoaderUtils.copyProperties(new AlarmModuleConfig(), constant, "alarm", "default"));
            assertEquals(expected.getMessage(), actual.getMessage());
        }
    }

    /**
     * Binds the properties into a config seeded with a non-empty {@code downsampling}, through upstream's binder and the
     * generated one, and requires the same exception or the same fields.
     */
    private static void assertSameOutcome(final Function<CoreModuleConfig, Properties> properties,
                                          final String message) throws Exception {
        try (UpstreamClasses upstream = new UpstreamClasses(YamlConfigLoaderUtils.class)) {
            final CoreModuleConfig upstreamConfig = seeded();
            final CoreModuleConfig distroConfig = seeded();
            final String expected = outcome(() -> UpstreamClasses.invoke(copy(upstream), upstreamConfig,
                properties.apply(upstreamConfig), "core", "default"), upstreamConfig);
            final String actual = outcome(() -> {
                YamlConfigLoaderUtils.copyProperties(distroConfig, properties.apply(distroConfig), "core", "default");
                return null;
            }, distroConfig);
            assertEquals(expected, actual, message);
        }
    }

    private static Method copy(final UpstreamClasses upstream) throws Exception {
        return upstream.of(YamlConfigLoaderUtils.class)
                       .getMethod("copyProperties", Object.class, Properties.class, String.class, String.class);
    }

    private static CoreModuleConfig seeded() {
        final CoreModuleConfig config = new CoreModuleConfig();
        config.getDownsampling().add("Hour");
        return config;
    }

    /**
     * @return the exception the call threw, or the fields it set
     */
    private static String outcome(final UpstreamCall call, final CoreModuleConfig config) {
        try {
            call.run();
            return config.getRestPort() + " " + config.getRestIdleTimeOut() + " " + config.isRestSSLEnabled() + " "
                + config.getRole() + " " + config.getDownsampling();
        } catch (final Exception e) {
            // A refused value leaves a final list as it was, as Field.set does
            return e.getClass().getName() + ": " + e.getMessage() + " " + config.getDownsampling();
        }
    }

    @FunctionalInterface
    private interface UpstreamCall {
        Object run() throws Exception;
    }
}
