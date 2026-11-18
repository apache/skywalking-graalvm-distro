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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.io.StringReader;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry looks a MAL expression and filter up by their exact text, as the manifests carry it.
 */
class ManifestEscapeTest {
    @Test
    void aValueReadsBackAsWritten() throws Exception {
        for (final String value : new String[] {
            " { tags -> tags.job_name == 'a' }", "\tleading tab", "a\\b", "line\nbreak", "carriage\rreturn", "form\ffeed", "  two spaces",
            "x = y: z"}) {
            final Properties read = new Properties();
            read.load(new StringReader("filter=" + Precompiler.escapePropertiesValue(value)));
            assertEquals(value, read.getProperty("filter"), value);
        }
    }
}
