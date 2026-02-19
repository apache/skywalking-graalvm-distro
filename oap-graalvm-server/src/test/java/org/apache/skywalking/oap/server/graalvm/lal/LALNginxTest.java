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

package org.apache.skywalking.oap.server.graalvm.lal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.provider.LALConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Comparison test for lal/nginx.yaml — two rules exercising text parsing,
 * conditional tag extraction, timestamp formatting, and metrics generation.
 *
 * nginx-access-log: tag("LOG_KIND") guard, text regexp, conditional tag extraction.
 * nginx-error-log: tag("LOG_KIND") guard, text regexp, timestamp w/ format, metrics.
 */
class LALNginxTest extends LALScriptComparisonBase {

    private static String ACCESS_LOG_DSL;
    private static String ERROR_LOG_DSL;

    @BeforeAll
    static void loadRules() throws Exception {
        final List<LALConfig> rules = loadLALRules("nginx.yaml");
        assertEquals(2, rules.size());
        for (final LALConfig rule : rules) {
            if ("nginx-access-log".equals(rule.getName())) {
                ACCESS_LOG_DSL = rule.getDsl();
            } else if ("nginx-error-log".equals(rule.getName())) {
                ERROR_LOG_DSL = rule.getDsl();
            }
        }
    }

    // ── nginx-access-log tests ──

    /**
     * TAG matches NGINX_ACCESS_LOG, text body matches regexp → tag extracted.
     *
     * <pre>
     * 192.168.1.1 - - [15/Jan/2024:10:30:00 +0000] "GET /index.html HTTP/1.1" 200 1234
     * </pre>
     */
    @Test
    void accessLog_matchingTagAndText_extractsStatusCode() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "NGINX_ACCESS_LOG");

        final LogData logData = buildTextLogData(
            "nginx-svc", "nginx-inst",
            "192.168.1.1 - - [15/Jan/2024:10:30:00 +0000] "
                + "\"GET /index.html HTTP/1.1\" 200 1234",
            tags);

        runAndCompare(ACCESS_LOG_DSL, logData);
    }

    /**
     * TAG does not match NGINX_ACCESS_LOG → entire if-block skipped, no extraction.
     */
    @Test
    void accessLog_nonMatchingTag_noExtraction() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "OTHER");

        final LogData logData = buildTextLogData(
            "nginx-svc", "nginx-inst", "some other log", tags);

        runAndCompare(ACCESS_LOG_DSL, logData);
    }

    /**
     * TAG matches but text body doesn't match regexp → parsed.status is null,
     * no tag added.
     */
    @Test
    void accessLog_matchingTagNonMatchingText_noTag() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "NGINX_ACCESS_LOG");

        final LogData logData = buildTextLogData(
            "nginx-svc", "nginx-inst",
            "this text does not match the nginx access log format",
            tags);

        runAndCompare(ACCESS_LOG_DSL, logData);
    }

    // ── nginx-error-log tests ──

    /**
     * TAG matches NGINX_ERROR_LOG, text body matches regexp → level tag extracted,
     * timestamp parsed with format, metrics generated.
     *
     * <pre>
     * 2024/01/15 10:30:00 [error] 12345#0: upstream error
     * </pre>
     */
    @Test
    void errorLog_matchingTagAndText_extractsLevelTimestampMetrics() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "NGINX_ERROR_LOG");

        final LogData logData = buildTextLogData(
            "nginx-svc", "nginx-inst",
            "2024/01/15 10:30:00 [error] 12345#0: upstream error",
            tags);

        runAndCompare(ERROR_LOG_DSL, logData);
    }

    /**
     * TAG does not match NGINX_ERROR_LOG → entire if-block skipped.
     */
    @Test
    void errorLog_nonMatchingTag_noExtraction() {
        final Map<String, String> tags = new HashMap<>();
        tags.put("LOG_KIND", "OTHER");

        final LogData logData = buildTextLogData(
            "nginx-svc", "nginx-inst", "some log", tags);

        runAndCompare(ERROR_LOG_DSL, logData);
    }
}
