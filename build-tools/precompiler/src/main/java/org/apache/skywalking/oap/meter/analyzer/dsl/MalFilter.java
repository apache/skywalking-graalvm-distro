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
 * Interface for generated MAL filter expression classes.
 * Each filter expression from rule YAML files is transpiled to a pure Java class
 * implementing this interface at build time.
 *
 * Replaces the Groovy Closure&lt;Boolean&gt; approach — no Groovy runtime needed.
 *
 * Same-FQCN copy for precompiler build-time classpath.
 * Canonical version: meter-analyzer-for-graalvm.
 */
@FunctionalInterface
public interface MalFilter {

    /**
     * Test whether samples with the given tags should be included.
     *
     * @param tags the sample labels/tags to evaluate
     * @return true if the sample should be kept, false to filter it out
     */
    boolean test(Map<String, String> tags);
}
