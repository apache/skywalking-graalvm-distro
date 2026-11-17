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

import java.util.List;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.trace.analyzer.v2.compiler.TALMetricDeclaration;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;

/**
 * A compiled TAL rule and every input it was compiled from: coordinates alone are no identity, two rules can share a
 * line. {@code functions} are the function-object fields the generated class holds, which upstream sets from the
 * function registry after instantiating it; {@code metrics} the declarations the compiler collected.
 */
@Getter
@RequiredArgsConstructor
public final class TalRule {
    private final String source;
    private final int line;
    private final String name;
    private final Supplier<TalExpression> expression;
    private final String inputType;
    private final String inputAlias;
    private final String metricPrefix;
    private final String dslSha256;
    private final List<TALMetricDeclaration> metrics;
    private final List<Function> functions;

    /**
     * A function-object field of the generated class: its name, the registry key of the object it holds, and that
     * object's class.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class Function {
        private final String field;
        private final String key;
        private final String type;
    }
}
