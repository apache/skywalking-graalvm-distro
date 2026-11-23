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

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.skywalking.oap.server.admin.uimanagement.UIManagementModuleConfig;
import org.apache.skywalking.oap.server.library.util.YamlConfigLoaderUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The generated config loader reports a setting a provider does not have as upstream's does, also for a provider whose
 * config class has no setting at all, so a misspelled key is not taken silently.
 */
class GeneratedConfigLoaderWarningTest {
    @Test
    void aSettingOfAProviderWithoutSettingsIsReportedUnsupported() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final AbstractAppender capture = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                warnings.add(event.getMessage().getFormattedMessage());
            }
        };
        capture.start();
        final Logger logger = (Logger) LogManager.getLogger(YamlConfigLoaderUtils.class);
        final Level level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.WARN);
        logger.addAppender(capture);
        try {
            final Properties given = new Properties();
            given.setProperty("obsoleteSetting", "x");
            YamlConfigLoaderUtils.copyProperties(new UIManagementModuleConfig(), given, "ui-management", "default");
        } finally {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel(logger.getName(), level);
        }
        assertEquals(List.of("obsoleteSetting setting is not supported in default provider of ui-management module"),
            warnings);
    }
}
