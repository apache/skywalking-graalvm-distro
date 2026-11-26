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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.skywalking.oap.log.analyzer.v2.provider.LALConfig;
import org.apache.skywalking.oap.meter.analyzer.v2.prometheus.rule.MetricsRule;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.dsl.Catalog;
import org.apache.skywalking.oap.server.core.rule.ext.StaticRuleRegistry;
import org.apache.skywalking.oap.server.graalvm.rules.CompiledRules;
import org.apache.skywalking.oap.server.graalvm.rules.LalFile;
import org.apache.skywalking.oap.server.graalvm.rules.MalFile;

/**
 * Read-only view of the bundled MAL / LAL rules: upstream's runtime-rule read routes, answered as its
 * {@code RuntimeRuleService} answers them on a node whose storage holds no runtime rule, so the Horizon UI and
 * {@code swctl admin} can list and review the rules. Every rule is compiled in at build time, so every rule is
 * {@code BUNDLED}; the mutating routes stay on {@link UnsupportedAdminFeatureHandler}. The OAL listing
 * ({@code /runtime/oal/*}) comes from upstream's dsl-debugging module, which is wired in.
 *
 * <p>The rules are those the boot loaded, as upstream's read routes read them from {@link StaticRuleRegistry}, where
 * the rule loaders record every file they load (the replacement loaders too, through {@code RuleSources}): the files of a
 * module turned off are not there. Whether {@code /list} lists a loaded file is decided per request, from the rules
 * compiled from it and the layers registered, as upstream's {@code StaticRuleLoader} claims a file ({@link #listed}).
 *
 * <p>Distro-only Armeria handler: needs its reflection entry in {@code oap-graalvm-native}'s
 * {@code reachability-metadata.json}, like {@link UnsupportedAdminFeatureHandler}.
 */
public class BundledRuleCatalogHandler {
    /**
     * The catalogs upstream's MAL and LAL rule engines claim: those {@code /list} and {@code GET /runtime/rule}
     * accept.
     */
    private static final Set<String> ENGINE_CATALOGS = new LinkedHashSet<>(List.of(
        "otel-rules", "log-mal-rules", "telegraf-rules", "meter-analyzer-config", "lal"));
    private static final String LAL = "lal";
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final Gson GSON = new Gson();
    private static final MediaType YAML =
        MediaType.create("application", "x-yaml").withCharset(StandardCharsets.UTF_8);

    private final Claims claims;

    public BundledRuleCatalogHandler() {
        this(Claims.compiled());
    }

    BundledRuleCatalogHandler(final Claims claims) {
        this.claims = claims;
    }

    /**
     * No runtime rule exists here, so the rows are the bundled rules upstream's runtime-rule module loads at boot, as
     * its {@code StaticRuleLoader} records them. Upstream's order is a hash map's; here it is by catalog and name.
     */
    @Get("/runtime/rule/list")
    public HttpResponse list(@Param("catalog") @Default("") final String catalog) {
        final String filter = catalog == null ? "" : catalog.trim();
        if (!filter.isEmpty() && !ENGINE_CATALOGS.contains(filter)) {
            return badRequest("invalid_catalog", filter, null, "catalog must be one of " + ENGINE_CATALOGS);
        }
        final JsonArray rows = new JsonArray();
        // By catalog:name, so a catalog's rules are in name order, as upstream lists them.
        for (final Map.Entry<String, String> rule : new TreeMap<>(StaticRuleRegistry.active().entries()).entrySet()) {
            final String[] key = StaticRuleRegistry.splitKey(rule.getKey());
            if (ENGINE_CATALOGS.contains(key[0]) && (filter.isEmpty() || filter.equals(key[0]))
                && listed(key[0], key[1])) {
                rows.add(bundledEntry(key[0], key[1], sha256Hex(rule.getValue())));
            }
        }
        // Only runtime-rule mints rule class loaders (DSLClassLoaderManager), so there are none here.
        final JsonObject loaderStats = new JsonObject();
        loaderStats.addProperty("active", 0);
        loaderStats.addProperty("pending", 0);
        final JsonObject envelope = new JsonObject();
        envelope.addProperty("generatedAt", System.currentTimeMillis());
        envelope.add("loaderStats", loaderStats);
        envelope.add("rules", rows);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, GSON.toJson(envelope));
    }

    /** Nothing is ever applied at runtime, so there is no apply to find, whatever is asked. */
    @Get("/runtime/rule/status")
    public HttpResponse applyStatus() {
        final JsonObject status = new JsonObject();
        status.addProperty("found", false);
        status.addProperty("phase", "UNKNOWN");
        status.addProperty("servedBy", "self");
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, GSON.toJson(status));
    }

    @Get("/runtime/rule")
    public HttpResponse get(@Param("catalog") final String catalog,
                            @Param("name") final String name,
                            @Param("source") @Default("") final String source,
                            @Header("Accept") @Default("") final String accept,
                            @Header("If-None-Match") @Default("") final String ifNoneMatch) {
        final Catalog parsed = parseCatalogOrNull(catalog);
        if (parsed == null) {
            return invalidCatalog(catalog, name);
        }
        final String wireName = parsed.getWireName();
        if (!ENGINE_CATALOGS.contains(wireName)) {
            return badRequest("invalid_catalog", wireName, name, "catalog must be one of " + ENGINE_CATALOGS);
        }
        if (name == null || name.isEmpty()) {
            return badRequest("missing_name", wireName, name, "name query parameter is required");
        }
        if (name.startsWith("/") || name.contains("..") || name.contains("\\") || name.contains("\u0000")
            || !VALID_NAME.matcher(name).matches()) {
            return badRequest("invalid_name", wireName, name,
                "name must match segments [A-Za-z0-9._-]+ joined by '/' with no leading slash, "
                    + "no '..', no empty segments, no backslash");
        }
        final boolean forceBundled = "bundled".equalsIgnoreCase(source);
        if (!forceBundled && source != null && !source.isEmpty() && !"runtime".equalsIgnoreCase(source)) {
            return badRequest("invalid_source", wireName, name, "source must be 'runtime' (default) or 'bundled'");
        }
        final String content = StaticRuleRegistry.active().find(wireName, name).orElse(null);
        if (content == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON_UTF_8,
                jsonBody("not_found", wireName, name,
                    forceBundled
                        ? "no bundled rule for this (catalog, name); source=bundled was requested"
                        : "no runtime rule and no bundled rule for this (catalog, name)"));
        }
        final String contentHash = sha256Hex(content);
        final String eTag = "\"" + contentHash + "\"";
        if (eTag.equals(ifNoneMatch == null ? "" : ifNoneMatch.trim())) {
            return HttpResponse.of(ruleHeaders(HttpStatus.NOT_MODIFIED, contentHash, eTag).build());
        }
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json")) {
            final JsonObject env = new JsonObject();
            env.addProperty("catalog", wireName);
            env.addProperty("name", name);
            env.addProperty("status", "BUNDLED");
            env.addProperty("source", "bundled");
            env.addProperty("contentHash", contentHash);
            env.addProperty("updateTime", 0L);
            env.addProperty("content", content);
            return HttpResponse.of(
                ruleHeaders(HttpStatus.OK, contentHash, eTag).contentType(MediaType.JSON_UTF_8).build(),
                HttpData.ofUtf8(GSON.toJson(env)));
        }
        return HttpResponse.of(ruleHeaders(HttpStatus.OK, contentHash, eTag).contentType(YAML).build(),
                               HttpData.ofUtf8(content));
    }

    @Get("/runtime/rule/bundled")
    public HttpResponse listBundled(@Param("catalog") final String catalog,
                                    @Param("withContent") @Default("true") final String withContent) {
        final Catalog parsed = parseCatalogOrNull(catalog);
        if (parsed == null) {
            return invalidCatalog(catalog, null);
        }
        // As upstream: a catalog no rule engine claims (oal, tal) has no runtime rules to list beside.
        if (!ENGINE_CATALOGS.contains(parsed.getWireName())) {
            return invalidCatalog(parsed.getWireName(), null);
        }
        final boolean includeContent = parseFlag(withContent)
            || "true".equalsIgnoreCase(withContent == null ? "true" : withContent.trim());
        final JsonArray out = new JsonArray();
        for (final StaticRuleRegistry.NamedRule rule : StaticRuleRegistry.active().findByCatalog(parsed.getWireName())) {
            final JsonObject row = new JsonObject();
            row.addProperty("name", rule.getName());
            row.addProperty("kind", "bundled");
            row.addProperty("contentHash", sha256Hex(rule.getContent()));
            row.addProperty("overridden", false);
            if (includeContent) {
                row.addProperty("content", rule.getContent());
            }
            out.add(row);
        }
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, GSON.toJson(out));
    }

    private static JsonObject bundledEntry(final String catalog, final String name, final String contentHash) {
        final JsonObject row = new JsonObject();
        row.addProperty("catalog", catalog);
        row.addProperty("name", name);
        row.addProperty("status", "BUNDLED");
        row.addProperty("localState", "RUNNING");
        row.addProperty("loaderGc", "LIVE");
        row.addProperty("loaderKind", "NONE");
        row.addProperty("loaderName", "");
        row.addProperty("contentHash", contentHash);
        row.addProperty("bundled", true);
        row.addProperty("bundledContentHash", contentHash);
        row.addProperty("pendingUnregister", false);
        return row;
    }

    private static Catalog parseCatalogOrNull(final String wireValue) {
        if (wireValue == null || wireValue.isEmpty()) {
            return null;
        }
        try {
            return Catalog.of(wireValue);
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }

    private static boolean parseFlag(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        final String v = raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v);
    }

    /** As upstream's {@code ContentHash}: SHA-256 of the UTF-8 text, in lowercase hex. */
    /**
     * Whether upstream's runtime-rule module lists a loaded file: its {@code StaticRuleLoader} keeps the files its
     * engine claims, a MAL file in which {@code MalFileApplier#parseMetricNames} finds a metric rule with a name, a LAL
     * file in which {@code LalFileApplier#parseRuleKeys} finds a rule with a name and no layer, {@code auto} or one
     * registered now. Decided from the rules the image compiled, bound as upstream's loaders bound them; a file that
     * yields no rule is not listed.
     */
    boolean listed(final String catalog, final String name) {
        if (LAL.equals(catalog)) {
            final List<String> layers = claims.lalLayers.get(name);
            return layers != null && layers.stream().anyMatch(BundledRuleCatalogHandler::registeredOrAuto);
        }
        return claims.malClaimed.contains(catalog + ":" + name);
    }

    private static boolean registeredOrAuto(final String layer) {
        if (layer == null || LALConfig.LAYER_AUTO.equalsIgnoreCase(layer)) {
            return true;
        }
        try {
            Layer.valueOf(layer);
            return true;
        } catch (final IllegalArgumentException unknown) {
            return false;
        }
    }

    /**
     * What the claim of each compiled file depends on: the MAL files holding a metric rule with a name, by
     * {@code catalog:name}, and the layers of the named rules of each LAL file, by name.
     */
    static final class Claims {
        private final Set<String> malClaimed = new HashSet<>();
        private final Map<String, List<String>> lalLayers = new HashMap<>();

        /**
         * @return the claims of the rules the image compiled
         */
        static Claims compiled() {
            final Claims claims = new Claims();
            for (final MalFile file : CompiledRules.malFiles()) {
                final List<MetricsRule> rules = file.getRule().getMetricsRules();
                if (rules != null && rules.stream().anyMatch(rule -> rule.getName() != null)) {
                    claims.malClaimed.add(file.getCatalog() + ":" + file.getRule().getName());
                }
            }
            for (final LalFile file : CompiledRules.lalFiles()) {
                final List<String> layers = new ArrayList<>();
                for (final LALConfig rule : file.getConfigs().getRules() == null ? List.<LALConfig>of()
                    : file.getConfigs().getRules()) {
                    if (rule.getName() != null && !rule.getName().isEmpty()) {
                        layers.add(rule.getLayer());
                    }
                }
                claims.lalLayers.put(file.getName().substring(0, file.getName().lastIndexOf('.')), layers);
            }
            return claims;
        }

        /**
         * @return these claims and a compiled LAL file whose named rules are of these layers
         */
        Claims withLalFile(final String name, final List<String> layers) {
            lalLayers.put(name, layers);
            return this;
        }
    }

    private static String sha256Hex(final String content) {
        try {
            final StringBuilder hex = new StringBuilder(64);
            for (final byte b : MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ResponseHeadersBuilder ruleHeaders(final HttpStatus status, final String contentHash,
                                                      final String eTag) {
        return ResponseHeaders.builder(status)
            .add("X-Sw-Content-Hash", contentHash)
            .add("X-Sw-Status", "BUNDLED")
            .add("X-Sw-Source", "bundled")
            .add("X-Sw-Update-Time", "0")
            .add(HttpHeaderNames.ETAG, eTag);
    }

    private static HttpResponse invalidCatalog(final String catalog, final String name) {
        return badRequest("invalid_catalog", catalog, name, "catalog must be one of " + ENGINE_CATALOGS);
    }

    private static HttpResponse badRequest(final String applyStatus, final String catalog, final String name,
                                           final String message) {
        return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8,
            jsonBody(applyStatus, catalog, name, message));
    }

    /** Upstream's {@code applyStatus/catalog/name/message} envelope for rule-route errors. */
    private static String jsonBody(final String applyStatus, final String catalog, final String name,
                                   final String message) {
        final JsonObject body = new JsonObject();
        body.addProperty("applyStatus", applyStatus);
        body.addProperty("catalog", catalog == null ? "" : catalog);
        body.addProperty("name", name == null ? "" : name);
        body.addProperty("message", message == null ? "" : message);
        return GSON.toJson(body);
    }
}
