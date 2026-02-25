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

package org.apache.skywalking.oap.server.library.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * GraalVM replacement for upstream VirtualThreads.
 * Original: skywalking/oap-server/server-library/library-util/src/main/java/.../util/VirtualThreads.java
 *
 * <p>Change: Calls JDK 25 virtual thread APIs directly instead of reflection.
 * This distro targets JDK 25 exclusively, so no reflection needed.
 * <p>Why: Reflection-based method handles are not needed when the target JDK is fixed,
 * and direct calls are GraalVM native-image friendly.
 */
@Slf4j
public final class VirtualThreads {

    static final int MINIMUM_JDK_VERSION = 25;
    static final String ENV_VIRTUAL_THREADS_ENABLED = "SW_VIRTUAL_THREADS_ENABLED";

    private static final boolean SUPPORTED;

    static {
        final String envValue = System.getenv(ENV_VIRTUAL_THREADS_ENABLED);
        final boolean disabledByEnv = "false".equalsIgnoreCase(envValue);

        if (disabledByEnv) {
            log.info("Virtual threads disabled by environment variable {}={}",
                     ENV_VIRTUAL_THREADS_ENABLED, envValue);
            SUPPORTED = false;
        } else {
            SUPPORTED = true;
            log.info("Virtual threads available (JDK {})", Runtime.version().feature());
        }
    }

    private VirtualThreads() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    public static ExecutorService createExecutor(final String namePrefix,
                                                 final Supplier<ExecutorService> platformExecutorSupplier) {
        return createExecutor(namePrefix, true, platformExecutorSupplier);
    }

    public static ExecutorService createExecutor(final String namePrefix,
                                                 final boolean enableVirtualThreads,
                                                 final Supplier<ExecutorService> platformExecutorSupplier) {
        if (enableVirtualThreads && SUPPORTED) {
            return createVirtualThreadExecutor(namePrefix);
        }
        return platformExecutorSupplier.get();
    }

    public static ScheduledExecutorService createScheduledExecutor(
            final String namePrefix,
            final Supplier<ScheduledExecutorService> platformExecutorSupplier) {
        if (SUPPORTED) {
            final ExecutorService vtExecutor = createVirtualThreadExecutor(namePrefix);
            return new VirtualThreadScheduledExecutor(vtExecutor);
        }
        return platformExecutorSupplier.get();
    }

    private static ExecutorService createVirtualThreadExecutor(final String namePrefix) {
        final ThreadFactory factory = Thread.ofVirtual()
            .name("vt:" + namePrefix + "-", 0)
            .factory();
        final ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
        log.info("Created virtual-thread-per-task executor [{}]", namePrefix);
        return executor;
    }
}
