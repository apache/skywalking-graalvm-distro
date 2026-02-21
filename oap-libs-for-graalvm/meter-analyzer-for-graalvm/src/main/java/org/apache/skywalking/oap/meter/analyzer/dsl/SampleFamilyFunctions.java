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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;

/**
 * Java functional interfaces replacing Groovy Closure parameters in SampleFamily methods.
 * These are used by the generated MAL expression classes (transpiled from Groovy DSL).
 */
public final class SampleFamilyFunctions {

    private SampleFamilyFunctions() {
    }

    /**
     * Tag transformation function: receives mutable tag map, modifies it, returns modified map.
     * Replaces Closure&lt;?&gt; in SampleFamily.tag().
     */
    @FunctionalInterface
    public interface TagFunction extends Function<Map<String, String>, Map<String, String>> {
    }

    /**
     * Sample filter predicate: receives tag map, returns true to keep the sample.
     * Replaces Closure&lt;Boolean&gt; in SampleFamily.filter().
     */
    @FunctionalInterface
    public interface SampleFilter extends Predicate<Map<String, String>> {
    }

    /**
     * ForEach function: receives an array element and mutable label map.
     * Replaces Closure&lt;Void&gt; in SampleFamily.forEach().
     */
    @FunctionalInterface
    public interface ForEachFunction extends BiConsumer<String, Map<String, String>> {
    }

    /**
     * Decorate function: receives a MeterEntity to modify.
     * Replaces Closure&lt;Void&gt; in SampleFamily.decorate().
     */
    @FunctionalInterface
    public interface DecorateFunction extends Consumer<MeterEntity> {
    }

    /**
     * Properties extractor: receives label map, returns properties map.
     * Replaces Closure&lt;Map&gt; in SampleFamily.instance() overload.
     */
    @FunctionalInterface
    public interface PropertiesExtractor extends Function<Map<String, String>, Map<String, String>> {
    }
}
