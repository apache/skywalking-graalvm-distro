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

package org.apache.skywalking.oap.server.graalvm.rules;

import java.util.List;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.ScopeType;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;

/**
 * Registers a TAL rule's metrics from its recorded declarations, at boot, in place of upstream
 * {@code TALClassGenerator#createMetrics}. The precompiler requires both to make the same {@link MeterSystem} calls.
 */
public final class TalMetrics {
    private TalMetrics() {
    }

    public static void create(final MeterSystem meterSystem, final List<TALMetricDeclaration> declarations) {
        for (final TALMetricDeclaration declaration : declarations) {
            meterSystem.create(declaration.fullName, declaration.function, ScopeType.valueOf(declaration.scopeType));
            if (declaration.labelLimit > 0) {
                meterSystem.limitLabels(declaration.fullName, declaration.labelLimit);
            }
        }
    }
}
