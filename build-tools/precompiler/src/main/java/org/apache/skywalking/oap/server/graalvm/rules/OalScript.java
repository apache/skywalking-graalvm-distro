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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.server.core.oal.rt.OALDefine;

/**
 * A compiled OAL script: its define, the metrics and dispatcher classes upstream's engine hands its stream and
 * dispatcher listeners, in that order, and the sources it disables. {@code overlayAdded} marks a script the rules
 * overlay added, which no provider loads.
 */
@Getter
@RequiredArgsConstructor
public final class OalScript {
    private final OALDefine define;
    private final List<Class<?>> metrics;
    private final List<Class<?>> dispatchers;
    private final List<String> disabledSources;
    private final boolean overlayAdded;
}
