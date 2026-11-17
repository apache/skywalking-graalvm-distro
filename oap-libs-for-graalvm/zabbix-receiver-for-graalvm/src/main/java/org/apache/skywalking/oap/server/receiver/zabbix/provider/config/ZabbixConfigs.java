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

package org.apache.skywalking.oap.server.receiver.zabbix.provider.config;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;

/**
 * GraalVM replacement for upstream ZabbixConfigs.
 * Original: skywalking/oap-server/server-receiver-plugin/skywalking-zabbix-receiver-plugin/src/main/java/.../
 * provider/config/ZabbixConfigs.java
 * Repackaged into zabbix-receiver-for-graalvm via maven-shade-plugin (replaces original .class in shaded JAR).
 *
 * Change: returns the configs upstream's loader read from zabbix-rules/ at build time, from the generated
 * {@link CompiledRules} registry, instead of reading the YAML at boot.
 * Why: The distro reads no rule file at runtime; the configs' expressions are compiled with the MAL rules, and the
 * rest of each file is data compiled with them.
 */
public class ZabbixConfigs {
    public static List<ZabbixConfig> loadConfigs(String path, List<String> fileNames) throws ModuleStartException {
        if (CollectionUtils.isEmpty(fileNames)) {
            return Collections.emptyList();
        }
        return CompiledRules.zabbixConfigs().stream()
                            .filter(config -> fileNames.contains(config.getSourceName()))
                            .collect(Collectors.toList());
    }
}
