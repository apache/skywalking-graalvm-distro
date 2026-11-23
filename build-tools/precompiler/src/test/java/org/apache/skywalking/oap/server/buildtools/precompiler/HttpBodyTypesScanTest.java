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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.util.List;
import java.util.Map;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classes Armeria binds a route's bodies to are found from the route's signature: its parameters but the
 * {@code @Param} values, its return type, the classes their fields hold (through arrays, wildcards, generic arrays and
 * type variables, by their binding in the hierarchy or else their bounds) and their superclasses, this project's classes only, enums included; a protobuf message of any package is kept whole,
 * for its descriptor closure, without its generated fields.
 */
class HttpBodyTypesScanTest {

    public static class Handler {
        @Post("/body")
        public Answer post(final Request request, @Param("name") final Ignored name) {
            return null;
        }

        @Get("/log")
        public HttpResponse log(final LogData log) {
            return null;
        }

        @Post("/otlp")
        public HttpResponse otlp(final ExportTraceServiceRequest.Builder request) {
            return null;
        }

        public Ignored notARoute(final Ignored ignored) {
            return null;
        }
    }

    public static class Request {
        private List<Item> items;
        private Map<String, Item> byName;
        private List<? extends Bounded> bounded;
        private List<InArray>[] inArray;
        private Kind kind;
        private FilledBox box;
        private Shelf<?> shelf;
        private static Ignored CONSTANT;
        private transient Ignored notSerialized;
    }

    public static class Bounded {
    }

    public static class InArray {
    }

    public enum Kind {
        ONE
    }

    public static class Box<T> {
        private T value;
    }

    public static class FilledBox extends Box<Boxed> {
    }

    public static class Boxed {
    }

    public static class Shelf<T extends Shelved> {
        private T[] items;
    }

    public static class Shelved {
    }

    public static class Item {
        private String text;
    }

    public static class Answer extends Base {
    }

    public static class Base {
        private Item[] items;
    }

    public static class Ignored {
    }

    @Test
    void theBodiesOfARoutesSignatureAndTheClassesTheirFieldsHoldAreScanned() {
        assertEquals(List.of(
            ExportTraceServiceRequest.class.getName(), LogData.class.getName(), Answer.class.getName(),
            Base.class.getName(), Bounded.class.getName(), Box.class.getName(), Boxed.class.getName(),
            FilledBox.class.getName(), InArray.class.getName(), Item.class.getName(), Kind.class.getName(),
            Request.class.getName(), Shelf.class.getName(), Shelved.class.getName()),
            Precompiler.scanHttpBodyTypes(List.of(Handler.class.getName())));
    }

    public static class GenericHandler<T> {
        @Post("/submit")
        public T submit(final T body) {
            return null;
        }
    }

    public static class Endpoint extends GenericHandler<Payload> {
    }

    public static class Payload {
        private Item item;
    }

    /**
     * A route a generic superclass declares binds the bodies the handler gives its type variables.
     */
    @Test
    void anInheritedGenericRouteBindsTheHandlersTypeArguments() {
        assertEquals(List.of(Item.class.getName(), Payload.class.getName()),
            Precompiler.scanHttpBodyTypes(List.of(Endpoint.class.getName())));
    }
}
