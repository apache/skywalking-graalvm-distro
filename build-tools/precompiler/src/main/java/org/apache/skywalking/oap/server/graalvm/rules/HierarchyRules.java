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

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.server.core.query.type.Service;

/**
 * {@code hierarchy-definition.yml}: the layer pairs and the rule each uses, the matching rules' expressions, the layer
 * levels, and the compiled matcher of each rule.
 */
@Getter
@RequiredArgsConstructor
public final class HierarchyRules {
    private final Map<String, Map<String, String>> hierarchy;
    private final Map<String, String> autoMatchingRules;
    private final Map<String, Integer> layerLevels;
    private final Map<String, Supplier<BiFunction<Service, Service, Boolean>>> matchers;
}
