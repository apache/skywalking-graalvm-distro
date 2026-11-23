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

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.RunningMode;
import org.apache.skywalking.oap.server.core.status.ServerStatusService;
import org.apache.skywalking.oap.server.core.version.Version;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oap.server.starter.config.ApplicationConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable.Row;

/**
 * Boots as upstream {@code OAPServerBootstrap} does: {@link ModuleManager} loads the modules {@code application.yml}
 * names, with the providers their selectors pick, found through the module SPI (native image registers the providers
 * at build time). The distro adds the compiled-rule checks around loading the configuration.
 */
public class GraalVMOAPServerStartUp {

    /**
     * Log4j2 reads its configuration when the first logger is created, so this class creates none before it points
     * Log4j2 at the native image's {@code config/log4j2.xml}; the JVM finds it on the class path, as upstream.
     */
    public static void main(String[] args) {
        final Path configDirectory = ResourceUtils.configDirectory();
        if (configDirectory != null && System.getProperty("log4j2.configurationFile") == null) {
            final Path log4jConfig = configDirectory.resolve("log4j2.xml");
            if (Files.isRegularFile(log4jConfig)) {
                System.setProperty("log4j2.configurationFile", log4jConfig.toUri().toString());
            }
        }
        start();
    }

    public static void start() {
        final Logger log = LoggerFactory.getLogger(GraalVMOAPServerStartUp.class);
        ModuleManager manager = new ModuleManager("SkyWalking GraalVM OAP");
        final TerminalFriendlyTable bootingParameters = manager.getBootingParameters();

        String mode = System.getProperty("mode");
        RunningMode.setMode(mode);

        ApplicationConfigLoader configLoader = new ApplicationConfigLoader(bootingParameters);

        bootingParameters.addRow(new Row("Running Mode", mode));
        bootingParameters.addRow(new Row("Version", Version.CURRENT.toString()));
        bootingParameters.addRow(new Row("Distro", "GraalVM"));

        try {
            CompiledRulesCheck.verify();
            CompiledRuleActivation.verify();
            ApplicationConfiguration configuration = configLoader.load();
            CompiledRuleActivation.apply(configuration);

            manager.init(configuration);

            manager.find(CoreModule.NAME)
                   .provider()
                   .getService(ServerStatusService.class)
                   .bootedNow(configLoader.getResolvedConfigurations(), System.currentTimeMillis());

            if (RunningMode.isInitMode()) {
                log.info("OAP starts up in init mode successfully, exit now...");
                System.exit(0);
            }
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            System.exit(1);
        } finally {
            log.info(bootingParameters.toString());
        }
    }
}
