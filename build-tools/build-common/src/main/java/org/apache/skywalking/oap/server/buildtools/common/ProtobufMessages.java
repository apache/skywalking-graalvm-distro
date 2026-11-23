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

package org.apache.skywalking.oap.server.buildtools.common;

import com.google.protobuf.Message;
import java.lang.reflect.Modifier;

public final class ProtobufMessages {
    private ProtobufMessages() {
    }

    /**
     * @return the generated message a type prints as: itself, or a builder's enclosing message, when it has the
     *     generated static {@code getDescriptor()}; null for anything else ({@code DynamicMessage}, its builder, a POJO)
     */
    public static Class<?> generatedMessage(final Class<?> type) {
        final Class<?> message = Message.Builder.class.isAssignableFrom(type) && type.getEnclosingClass() != null
            ? type.getEnclosingClass() : type;
        if (!Message.class.isAssignableFrom(message)) {
            return null;
        }
        try {
            return Modifier.isStatic(message.getMethod("getDescriptor").getModifiers()) ? message : null;
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }
}
