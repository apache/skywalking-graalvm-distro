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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only view of the bundled MAL / LAL rules, on the same routes and with the same payloads
 * as upstream's runtime-rule module, so the Horizon UI and {@code swctl admin} can list and
 * review rules. Every rule is pre-compiled at build time, so every rule is {@code BUNDLED}; the
 * mutating routes stay on {@link UnsupportedAdminFeatureHandler}. The OAL listing
 * ({@code /runtime/oal/*}) comes from upstream's dsl-debugging module, which is wired in.
 *
 * <p>Backed by {@code META-INF/rule-source/} from the precompiler (see its
 * {@code exportRuleSources}), because upstream's {@code StaticRuleRegistry} is only filled by
 * the YAML loaders this distro replaces.
 *
 * <p>Distro-only Armeria handler: needs its reflection entry in {@code oap-graalvm-native}'s
 * {@code reachability-metadata.json}, like {@link UnsupportedAdminFeatureHandler}.
 */
@Slf4j
public class BundledRuleCatalogHandler {
    static final String RULE_SOURCE_ROOT = "META-INF/rule-source/";
    private static final List<String> CATALOGS = List.of(
        "otel-rules", "log-mal-rules", "telegraf-rules", "meter-analyzer-config", "lal");
    private static final Gson GSON = new Gson();
    private static final MediaType YAML =
        MediaType.create("application", "x-yaml").withCharset(StandardCharsets.UTF_8);

    private final ClassLoader classLoader;
    private final Map<String, List<BundledRule>> rulesByCatalog = new LinkedHashMap<>();
    private final Map<String, BundledRule> rulesByKey = new LinkedHashMap<>();

    static final class BundledRule {
        final String catalog;
        final String name;
        final String path;
        final String contentHash;

        BundledRule(final String catalog, final String name, final String path, final String contentHash) {
            this.catalog = catalog;
            this.name = name;
            this.path = path;
            this.contentHash = contentHash;
        }
    }

    public BundledRuleCatalogHandler() {
        this(BundledRuleCatalogHandler.class.getClassLoader());
    }

    BundledRuleCatalogHandler(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        for (final String catalog : CATALOGS) {
            rulesByCatalog.put(catalog, new ArrayList<>());
        }
        for (final String line : readLines(RULE_SOURCE_ROOT + "index.txt")) {
            final String[] parts = line.split("\\|", 4);
            if (parts.length < 4) {
                continue;
            }
            final BundledRule rule = new BundledRule(parts[0], parts[1], parts[2], parts[3]);
            rulesByCatalog.computeIfAbsent(rule.catalog, k -> new ArrayList<>()).add(rule);
            rulesByKey.put(key(rule.catalog, rule.name), rule);
        }
        log.info("Bundled rule catalog: {} MAL/LAL rules", rulesByKey.size());
    }

    // ---- /runtime/rule (upstream RuntimeRuleRestHandler read routes) ----

    /** No runtime rules can exist here, so the envelope is always empty; Horizon merges it with /bundled. */
    @Get("/runtime/rule/list")
    public HttpResponse list(@Param("catalog") @Default("") final String catalog) {
        final String filter = catalog.trim();
        if (!filter.isEmpty() && !CATALOGS.contains(filter)) {
            return invalidCatalog(filter, null);
        }
        final JsonObject loaderStats = new JsonObject();
        loaderStats.addProperty("active", 0);
        loaderStats.addProperty("pending", 0);
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("generatedAt", System.currentTimeMillis());
        envelope.add("loaderStats", loaderStats);
        envelope.add("rules", new JsonArray());
        return json(HttpStatus.OK, envelope);
    }

    @Get("/runtime/rule/bundled")
    public HttpResponse listBundled(@Param("catalog") @Default("") final String catalog,
                                    @Param("withContent") @Default("true") final String withContent) {
        final String c = catalog.trim();
        if (!CATALOGS.contains(c)) {
            return invalidCatalog(c, null);
        }
        final boolean includeContent = !"false".equalsIgnoreCase(withContent.trim());
        final JsonArray out = new JsonArray();
        for (final BundledRule rule : rulesByCatalog.get(c)) {
            final JsonObject row = new JsonObject();
            row.addProperty("name", rule.name);
            row.addProperty("kind", "bundled");
            row.addProperty("contentHash", rule.contentHash);
            row.addProperty("overridden", false);
            if (includeContent) {
                row.addProperty("content", readContent(rule));
            }
            out.add(row);
        }
        return json(HttpStatus.OK, out);
    }

    @Get("/runtime/rule")
    public HttpResponse get(@Param("catalog") @Default("") final String catalog,
                            @Param("name") @Default("") final String name,
                            @Param("source") @Default("") final String source,
                            @Header("Accept") @Default("") final String accept,
                            @Header("If-None-Match") @Default("") final String ifNoneMatch) {
        final String c = catalog.trim();
        if (!CATALOGS.contains(c)) {
            return invalidCatalog(c, name);
        }
        if (name.trim().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_name", c, name, "name is required");
        }
        if (!source.isEmpty() && !"runtime".equalsIgnoreCase(source) && !"bundled".equalsIgnoreCase(source)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_source", c, name,
                "source must be 'runtime' (default) or 'bundled'");
        }
        final BundledRule rule = rulesByKey.get(key(c, name.trim()));
        if (rule == null) {
            return error(HttpStatus.NOT_FOUND, "not_found", c, name,
                "no bundled rule for this (catalog, name); this distribution has no runtime rules");
        }
        final String eTag = "\"" + rule.contentHash + "\"";
        if (eTag.equals(ifNoneMatch.trim())) {
            return HttpResponse.of(ruleHeaders(HttpStatus.NOT_MODIFIED, rule, eTag).build());
        }
        final String content = readContent(rule);
        if (accept.toLowerCase(Locale.ROOT).contains("application/json")) {
            final JsonObject env = new JsonObject();
            env.addProperty("catalog", c);
            env.addProperty("name", rule.name);
            env.addProperty("status", "BUNDLED");
            env.addProperty("source", "bundled");
            env.addProperty("contentHash", rule.contentHash);
            env.addProperty("updateTime", 0L);
            env.addProperty("content", content);
            return HttpResponse.of(ruleHeaders(HttpStatus.OK, rule, eTag).contentType(MediaType.JSON_UTF_8).build(),
                                   HttpData.ofUtf8(GSON.toJson(env)));
        }
        return HttpResponse.of(ruleHeaders(HttpStatus.OK, rule, eTag).contentType(YAML).build(),
                               HttpData.ofUtf8(content));
    }

    /** Nothing is ever applied at runtime, so there is never an apply to report. */
    @Get("/runtime/rule/status")
    public HttpResponse applyStatus(@Param("catalog") @Default("") final String catalog,
                                    @Param("name") @Default("") final String name) {
        return error(HttpStatus.NOT_FOUND, "not_found", catalog, name,
            "no runtime apply exists: rules are bundled and read-only in the GraalVM native distribution");
    }

    // ---- helpers ----

    private static String key(final String catalog, final String name) {
        return catalog + "|" + name;
    }

    private String readContent(final BundledRule rule) {
        final String content = readResource(RULE_SOURCE_ROOT + rule.catalog + "/" + rule.path);
        return content == null ? "" : content;
    }

    private String readResource(final String resource) {
        try (InputStream is = classLoader.getResourceAsStream(resource)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> readLines(final String resource) {
        final List<String> lines = new ArrayList<>();
        try (InputStream is = classLoader.getResourceAsStream(resource)) {
            if (is == null) {
                log.warn("Bundled rule catalog manifest not found: {}", resource);
                return lines;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        lines.add(line);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    private static ResponseHeadersBuilder ruleHeaders(final HttpStatus status, final BundledRule rule,
                                                       final String eTag) {
        return ResponseHeaders.builder(status)
            .add("X-Sw-Content-Hash", rule.contentHash)
            .add("X-Sw-Status", "BUNDLED")
            .add("X-Sw-Source", "bundled")
            .add("X-Sw-Update-Time", "0")
            .add(HttpHeaderNames.ETAG, eTag);
    }

    private static HttpResponse invalidCatalog(final String catalog, final String name) {
        return error(HttpStatus.BAD_REQUEST, "invalid_catalog", catalog, name,
            "catalog must be one of " + String.join(", ", CATALOGS));
    }

    /** Same {@code applyStatus/catalog/name/message} envelope upstream returns for rule-route errors. */
    private static HttpResponse error(final HttpStatus status, final String applyStatus, final String catalog,
                                      final String name, final String message) {
        final JsonObject body = new JsonObject();
        body.addProperty("applyStatus", applyStatus);
        body.addProperty("catalog", catalog == null ? "" : catalog);
        body.addProperty("name", name == null ? "" : name);
        body.addProperty("message", message);
        return json(status, body);
    }

    private static HttpResponse json(final HttpStatus status, final Object body) {
        return HttpResponse.of(status, MediaType.JSON_UTF_8, GSON.toJson(body));
    }
}
