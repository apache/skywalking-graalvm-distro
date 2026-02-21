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

package org.apache.skywalking.oap.log.analyzer.dsl;

import org.apache.skywalking.oap.log.analyzer.dsl.spec.filter.FilterSpec;

/**
 * Pure Java interface for transpiled LAL (Log Analysis Language) scripts.
 * Replaces Groovy {@code DelegatingScript} — no Groovy runtime needed.
 *
 * <p>Each generated LAL class implements this interface. The transpiler converts
 * Groovy DSL scripts to Java source code at build time.
 */
@FunctionalInterface
public interface LalExpression {
    /**
     * Execute the LAL script logic.
     *
     * @param filterSpec the FilterSpec providing spec methods (json, text, extractor, sink, tag, abort)
     * @param binding    the Binding holding log data, parsed results, and state flags
     */
    void execute(FilterSpec filterSpec, Binding binding);
}
