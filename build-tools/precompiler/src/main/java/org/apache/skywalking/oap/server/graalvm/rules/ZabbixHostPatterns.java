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

package org.apache.skywalking.oap.server.graalvm.rules;

import org.apache.skywalking.oap.server.receiver.zabbix.provider.config.ZabbixConfig;

/**
 * Which Zabbix rule files the Zabbix receiver compiles: its {@code ZabbixMetrics} builds a converter, compiling the
 * file's expressions and filter, for each host pattern of the file, so a file without one compiles nothing at boot.
 */
public final class ZabbixHostPatterns {
    private ZabbixHostPatterns() {
    }

    /**
     * @return whether the receiver compiles the file's expressions at boot: it has a host pattern
     */
    public static boolean compiledAtBoot(final ZabbixConfig config) {
        return config.getEntities() != null && config.getEntities().getHostPatterns() != null
            && !config.getEntities().getHostPatterns().isEmpty();
    }
}
