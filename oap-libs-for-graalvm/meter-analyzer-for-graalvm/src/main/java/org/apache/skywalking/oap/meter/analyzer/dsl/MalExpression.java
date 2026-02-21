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

package org.apache.skywalking.oap.meter.analyzer.dsl;

import java.util.Map;

/**
 * Interface for generated MAL (Meter Analysis Language) expression classes.
 * Each MAL expression from rule YAML files is transpiled to a pure Java class
 * implementing this interface at build time.
 *
 * Replaces the Groovy DelegatingScript approach — no Groovy runtime needed.
 */
@FunctionalInterface
public interface MalExpression {

    /**
     * Execute the MAL expression against the given sample families.
     *
     * @param samples map of metric name → SampleFamily, replaces Groovy's
     *                propertyMissing() delegate lookup
     * @return the resulting SampleFamily, or SampleFamily.EMPTY if no data
     */
    SampleFamily run(Map<String, SampleFamily> samples);
}
