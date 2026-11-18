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

package org.apache.skywalking.oap.server.graalvm.mal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Generated histogram inputs put the bucket bounds under the label the expression's histogram reads.
 */
class HistogramLabelTest {
    @Test
    void theBoundsGoUnderTheHistogramsLabel() {
        assertEquals("bucket", MALScriptComparisonBase.histogramLabel(
            "m.sum(['service', 'bucket']).histogram('bucket', TimeUnit.MILLISECONDS).histogram_percentile([50])"));
        assertEquals("le", MALScriptComparisonBase.histogramLabel("m.sum(['le']).histogram().histogram_percentile([50])"));
        assertEquals("le", MALScriptComparisonBase.histogramLabel("m.sum(['le']).histogram(\"le\")"));
        assertEquals("bucket", MALScriptComparisonBase.histogramLabel("m.sum(['bucket']).histogram ('bucket')"));
        assertNull(MALScriptComparisonBase.histogramLabel("m.sum(['service'])"));
    }
}
