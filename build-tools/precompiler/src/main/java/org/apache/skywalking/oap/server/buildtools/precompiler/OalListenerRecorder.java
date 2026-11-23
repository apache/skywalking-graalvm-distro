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

package org.apache.skywalking.oap.server.buildtools.precompiler;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.DispatcherDetectorListener;
import org.apache.skywalking.oap.server.core.analysis.Stream;
import org.apache.skywalking.oap.server.core.analysis.StreamAnnotationListener;

/**
 * The stream and dispatcher listener of one OAL script at build time: records, in order, the classes upstream's
 * {@code OALEngineV2#notifyAllListeners} hands over, which the runtime registers for the script, and each metrics
 * class's storage builder, which the runtime instantiates. It registers nothing, so no stream is defined.
 */
@Getter
final class OalListenerRecorder extends StreamAnnotationListener implements DispatcherDetectorListener {
    private final List<String> metrics = new ArrayList<>();
    private final List<String> builders = new ArrayList<>();
    private final List<String> dispatchers = new ArrayList<>();

    OalListenerRecorder() {
        super(null);
    }

    @Override
    public void notify(final Class aClass) {
        final Stream stream = (Stream) aClass.getAnnotation(Stream.class);
        if (stream == null) {
            throw new UnexpectedException(
                "Stream annotation listener could only parse the class present stream annotation.");
        }
        metrics.add(aClass.getName());
        builders.add(stream.builder().getName());
    }

    @Override
    public void addIfAsSourceDispatcher(final Class aClass) {
        dispatchers.add(aClass.getName());
    }
}
