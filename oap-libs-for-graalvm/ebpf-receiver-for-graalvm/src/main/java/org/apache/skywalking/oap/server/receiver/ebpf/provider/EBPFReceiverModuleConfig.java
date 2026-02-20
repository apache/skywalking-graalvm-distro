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

package org.apache.skywalking.oap.server.receiver.ebpf.provider;

import lombok.Getter;
import lombok.Setter;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;

/**
 * GraalVM replacement for upstream EBPFReceiverModuleConfig.
 * Original: skywalking/oap-server/server-receiver-plugin/skywalking-ebpf-receiver-plugin/src/main/java/.../ebpf/provider/EBPFReceiverModuleConfig.java
 * Repackaged into ebpf-receiver-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: Added @Setter at class level. Upstream only has @Getter.
 * Why: Enables YamlConfigLoaderUtils to set config fields via Lombok setters instead of
 * reflection (Field.setAccessible + field.set), which requires reflect-config.json in GraalVM native image.
 */
@Getter
@Setter
public class EBPFReceiverModuleConfig extends ModuleConfig {

    /**
     * The continuous profiling policy cache time, Unit is second. Default value is 60 seconds.
     */
    @Setter
    private int continuousPolicyCacheTimeout = 60;

    private String gRPCHost;
    private int gRPCPort;
    private int maxConcurrentCallsPerConnection;
    private int maxMessageSize;
    private int gRPCThreadPoolSize;
    private boolean gRPCSslEnabled = false;
    private String gRPCSslKeyPath;
    private String gRPCSslCertChainPath;
    private String gRPCSslTrustedCAsPath;

}
