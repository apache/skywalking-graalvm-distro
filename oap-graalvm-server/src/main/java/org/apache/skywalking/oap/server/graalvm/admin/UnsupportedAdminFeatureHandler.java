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
 * Serves a friendly 501 for the one admin feature the GraalVM native distro cannot support:
 * runtime-rule hot-update (the mutating {@code /runtime/rule/*}, {@code /runtime/mal/*},
 * {@code /runtime/lal/*} routes), which compiles MAL / LAL rules into fresh bytecode with
 * Javassist at runtime. A native image is built under a closed-world assumption — no runtime
 * class generation — so this distro pre-compiles all DSL at build time. The read-only rule
 * catalog ({@code /runtime/rule/list|bundled}, {@code GET /runtime/rule}) is served by
 * {@link BundledRuleCatalogHandler}; the DSL live debugger and the OAL listing come from
 * upstream's dsl-debugging module, whose probes the precompiler compiles into every rule class.
 *
 * <p>Routes return a structured payload the Horizon UI can render instead of hitting a
 * connection error.
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
            + "build time; runtime rule hot-update is disabled. Use the standard JVM OAP "
            + "distribution if you need it.";

    @Post("/runtime/rule/addOrUpdate")
    public HttpResponse runtimeRuleAddOrUpdate(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    @Post("/runtime/rule/inactivate")
    public HttpResponse runtimeRuleInactivate(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    @Post("/runtime/rule/delete")
    public HttpResponse runtimeRuleDelete(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    // The dump is an export of runtime (operator-pushed) rules, of which there are none here.
    @Get("/runtime/rule/dump")
    public HttpResponse runtimeRuleDump(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    @Get("/runtime/rule/dump/{catalog}")
    public HttpResponse runtimeRuleDumpCatalog(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    @Get("prefix:/runtime/mal")
    @Post("prefix:/runtime/mal")
    @Put("prefix:/runtime/mal")
    @Delete("prefix:/runtime/mal")
    public HttpResponse runtimeMal(final ServiceRequestContext ctx) {
        return notImplemented("runtime-rule", ctx.path());
    }

    @Get("prefix:/runtime/lal")
    @Post("prefix:/runtime/lal")
    @Put("prefix:/runtime/lal")
    @Delete("prefix:/runtime/lal")
    public HttpResponse runtimeLal(final ServiceRequestContext ctx) {
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
