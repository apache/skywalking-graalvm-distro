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

package org.apache.skywalking.oap.server.graalvm.admin;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;

/**
 * Serves a friendly 501 for the admin features the GraalVM native distro cannot support.
 *
 * <p>The DSL live debugger ({@code /dsl-debugging/*}, {@code /runtime/oal/*}) and runtime-rule
 * hot-update ({@code /runtime/rule/*}, {@code /runtime/mal/*}, {@code /runtime/lal/*}) both
 * compile MAL / LAL / OAL rules into fresh bytecode with Javassist at runtime. A native image
 * is built under a closed-world assumption — no runtime class generation — so this distro
 * pre-compiles all DSL at build time and these on-demand-codegen features are unavailable.
 *
 * <p>Routes are registered with Armeria {@code prefix:} patterns so every verb and sub-path
 * under the two roots resolves here, returning a structured payload the Horizon UI can render
 * instead of hitting a connection error.
 *
 * <p>This distro-only class is not on the build-time precompiler's classpath (it depends on the
 * precompiler output), so the precompiler's Armeria-handler scan cannot register it. Its
 * reflection metadata is maintained by hand in {@code oap-graalvm-native}'s
 * {@code reachability-metadata.json}; without it Armeria silently builds zero routes and these
 * prefixes return 404 instead of 501.
 */
public class UnsupportedAdminFeatureHandler {

    private static final String MESSAGE =
        "This feature requires runtime DSL (MAL/LAL/OAL) compilation, which is not available "
            + "in the SkyWalking GraalVM native distribution. All DSL rules are pre-compiled at "
            + "build time; runtime rule hot-update and the DSL live debugger are disabled. Use "
            + "the standard JVM OAP distribution if you need these features.";

    @Get("prefix:/dsl-debugging")
    @Post("prefix:/dsl-debugging")
    @Put("prefix:/dsl-debugging")
    @Delete("prefix:/dsl-debugging")
    public HttpResponse dslDebugging(final ServiceRequestContext ctx) {
        return notImplemented("dsl-debugging", ctx.path());
    }

    @Get("prefix:/runtime")
    @Post("prefix:/runtime")
    @Put("prefix:/runtime")
    @Delete("prefix:/runtime")
    public HttpResponse runtimeRule(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    private static HttpResponse notImplemented(final String feature, final String path) {
        final String body = "{\"status\":501,"
            + "\"error\":\"feature_not_available_in_graalvm_native\","
            + "\"feature\":\"" + feature + "\","
            + "\"path\":\"" + escape(path) + "\","
            + "\"message\":\"" + MESSAGE + "\"}";
        return HttpResponse.of(HttpStatus.NOT_IMPLEMENTED, MediaType.JSON_UTF_8, body);
    }

    private static String escape(final String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
