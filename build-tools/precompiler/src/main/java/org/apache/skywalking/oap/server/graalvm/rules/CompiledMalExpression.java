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

import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.meter.analyzer.v2.dsl.MalExpression;

/**
 * A compiled MAL expression and what MetricConvert passed with it: the coordinates of its rule (as {@code DslSourceRef}
 * normalises them), the metric name and the expression text.
 */
@Getter
@RequiredArgsConstructor
public final class CompiledMalExpression {
    private final String source;
    private final int line;
    private final String metricName;
    private final String expression;
    private final Supplier<MalExpression> compiled;
}
