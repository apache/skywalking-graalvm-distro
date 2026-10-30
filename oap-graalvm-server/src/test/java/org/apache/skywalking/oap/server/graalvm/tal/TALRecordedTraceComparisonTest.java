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

package org.apache.skywalking.oap.server.graalvm.tal;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.SpanFlags;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.Layer;
import org.apache.skywalking.oap.server.core.analysis.manual.networkalias.NetworkAddressAlias;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.function.apdex.ApdexFunction;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.cache.NetworkAddressAliasCache;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.source.ISource;
import org.apache.skywalking.oap.server.core.source.NetworkAddressAliasSetup;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.core.trace.SpanMetadata;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.receiver.otel.otlp.OTLPValues;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.ExecutionContext;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalDSL;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.spec.FilterSpec;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TALConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.TALSourceTypeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Every TAL rule under {@code tal/} over the trace upstream recorded from the OpenTelemetry Demo (one
 * {@code GET /api/cart} through frontend-web, frontend-proxy, frontend and cart), plus one slow MySQL call, run twice:
 * through a fresh {@code TALClassGenerator} compile of the submodule's YAML, as the JVM OAP boots, and through the
 * classes the precompiler emitted, loaded by the same-FQCN {@code TalDSL}. Every metric value, traffic row, alias
 * row, output record and span vote must be identical.
 *
 * <p>Each run makes a cold pass, where the pairing learns each caller and peer, and a warm pass with the first
 * pass's alias rows in the cache, as upstream's {@code ShippedRuleOverRecordedTraceTest} does.
 */
class TALRecordedTraceComparisonTest {
    private static final Path FIXTURE = Path.of(System.getProperty("user.dir")).getParent().resolve(
        "skywalking/oap-server/server-receiver-plugin/otel-receiver-plugin/src/test/resources/tal/"
            + "otlp-trace-get-api-cart.json");

    private MockedStatic<MetricsStreamProcessor> mockedProcessor;
    private final List<Metrics> traffic = new ArrayList<>();

    @BeforeEach
    void setUp() {
        final MetricsStreamProcessor processor = mock(MetricsStreamProcessor.class);
        mockedProcessor = Mockito.mockStatic(MetricsStreamProcessor.class);
        mockedProcessor.when(MetricsStreamProcessor::getInstance).thenReturn(processor);
        doAnswer(invocation -> {
            traffic.add(invocation.getArgument(0));
            return null;
        }).when(processor).in(any());
        ApdexFunction.setDICT(name -> 500);
    }

    @AfterEach
    void tearDown() {
        mockedProcessor.close();
    }

    @Test
    void precompiledRulesEmitWhatAFreshCompileEmits() throws Exception {
        assertEquals(9, loadSpans("GENERAL").size(), "the eight recorded spans and the slow MySQL call");

        final List<String> fresh = run(Run::fresh);
        final List<String> precompiled = run(Run::precompiled);

        assertTrue(fresh.stream().anyMatch(line -> line.startsWith("rule ")),
            "no rule ran over the recorded trace, so the comparison would prove nothing:\n" + String.join("\n", fresh));
        assertEquals(fresh, precompiled);
    }

    private interface Loader {
        TalExpression load(Run run, TALConfig rule) throws Exception;
    }

    /**
     * One run's services: the meter system the rules create their metrics in and every output collected.
     */
    private final class Run {
        private final MeterSystem[] meterSystem = new MeterSystem[1];
        private final List<String> out = new ArrayList<>();
        private final List<NetworkAddressAliasSetup> aliases = new ArrayList<>();
        private final NamingControl namingControl = TALTestSupport.namingControl();
        private final SourceReceiver sourceReceiver = mock(SourceReceiver.class);
        private final ModuleManager moduleManager;

        private Run() {
            doAnswer(invocation -> {
                final ISource source = invocation.getArgument(0);
                source.prepare();
                if (source instanceof NetworkAddressAliasSetup) {
                    aliases.add((NetworkAddressAliasSetup) source);
                }
                return null;
            }).when(sourceReceiver).receive(any());
            moduleManager = TALTestSupport.moduleManager(meterSystem, sourceReceiver, namingControl);
            meterSystem[0] = spy(new MeterSystem(moduleManager));
            doAnswer(invocation -> {
                final Object value = invocation.getArgument(0);
                out.add("metric " + value.getClass().getName() + " " + ((Metrics) value).serialize().build());
                return null;
            }).when(meterSystem[0]).doStreamingCalculation(any());
            MeterEntity.setNamingControl(namingControl);
        }

        private TalExpression fresh(final TALConfig rule) throws Exception {
            return TALTestSupport.freshCompile(rule, meterSystem[0]);
        }

        private TalExpression precompiled(final TALConfig rule) throws Exception {
            final TalDSL dsl = TALTestSupport.loadPrecompiled(rule, moduleManager);
            assertTrue(dsl.getExpression().getClass().getName().contains("_L" + rule.getLineNo() + "_"),
                "the pre-compiled class is named after its rule's line: " + dsl.getExpression().getClass());
            return dsl.getExpression();
        }
    }

    private List<String> run(final Loader loader) throws Exception {
        traffic.clear();
        final Run path = new Run();
        // By layer, in file order, as SpanFilterListener.Factory keys them.
        final Map<String, Map<String, Bound>> byLayer = new LinkedHashMap<>();
        for (final TALConfig rule : TALTestSupport.precompiledRules()) {
            byLayer.computeIfAbsent(rule.getLayer(), layer -> new LinkedHashMap<>())
                   .put(rule.getName(), new Bound(rule.getName(), loader.load(path, rule),
                       TALTestSupport.inputProvider(TALTestSupport.inputTypeOf(rule.getInputType()))));
        }
        final FilterSpec filterSpec = new FilterSpec(path.moduleManager, new TraceAnalyzerModuleConfig(),
            path.meterSystem[0], path.namingControl, path.sourceReceiver);
        // A matched pair is built under the address its callee's rule composes, as TraceAnalyzerModuleProvider wires.
        filterSpec.getSpanPairing().setAddressComposer((caller, callee) -> {
            final Bound rule = byLayer.getOrDefault(callee.getRuleLayer(), Map.of()).get(callee.getRuleName());
            return rule == null ? null : rule.expression.networkAliasAddress(filterSpec, new ExecutionContext(), caller);
        });

        // The recording carries no service.layer; it is replayed as the spans of every layer some rule serves.
        for (final String layer : byLayer.keySet()) {
            final List<AnalyzedSpan> spans = loadSpans(layer);
            path.out.add("=== " + layer + " cold ===");
            runAll(path, filterSpec, byLayer, spans);
            loadAliasCache(path);
            path.aliases.clear();
            path.out.add("=== " + layer + " warm ===");
            runAll(path, filterSpec, byLayer, spans);
        }
        return path.out;
    }

    private static final class Bound {
        private final String name;
        private final TalExpression expression;
        private final TALSourceTypeProvider provider;

        private Bound(final String name, final TalExpression expression, final TALSourceTypeProvider provider) {
            this.name = name;
            this.expression = expression;
            this.provider = provider;
        }
    }

    /**
     * What {@code TraceAnalyzerServiceImpl.doAnalysis} does per span: the rules of the span's layer whose input type
     * matches, each on its own copy of the metadata, then the span's pairing halves, then the rows the run produced.
     */
    private void runAll(final Run path, final FilterSpec filterSpec, final Map<String, Map<String, Bound>> byLayer,
                        final List<AnalyzedSpan> spans) {
        for (final AnalyzedSpan s : spans) {
            path.out.add("span " + s.meta.getService() + " " + s.meta.getKind() + " " + s.meta.getEndpoint());
            String aliasRule = null;
            SpanMetadata aliasMeta = null;
            String clientPeer = null;
            SpanMetadata clientMeta = null;
            for (final Bound rule : byLayer.getOrDefault(s.meta.getLayer(), Map.of()).values()) {
                if (!rule.provider.inputType().isInstance(s.span)) {
                    continue;
                }
                final SpanMetadata ruleMeta = copyOf(s.meta);
                final ExecutionContext ctx = new ExecutionContext();
                ctx.setSourceTypeProvider(rule.provider);
                ctx.init(ruleMeta, s.span, s.resourceTags, s.scopeTags);
                rule.expression.execute(filterSpec, ctx);
                path.out.add("rule " + rule.name + " aborted=" + ctx.isAborted() + " persist=" + ctx.isPersist()
                    + " enforced=" + ctx.isEnforced() + " tags=" + ctx.getAdditionalTags() + " meta=" + ruleMeta);
                if (ctx.isAborted()) {
                    continue;
                }
                if (aliasRule == null && ctx.isNetworkAliasRequested()) {
                    aliasRule = rule.name;
                    aliasMeta = ruleMeta;
                }
                if (clientPeer == null && ctx.getNetworkAliasPeer() != null) {
                    clientPeer = ctx.getNetworkAliasPeer();
                    clientMeta = ruleMeta;
                }
                if (ctx.outputAsBuilder() != null && ctx.isSinkKept()) {
                    path.out.add("record " + ctx.outputAsBuilder().outputToJson());
                }
            }
            if (clientPeer != null) {
                filterSpec.getSpanPairing().clientSeen(clientMeta, clientPeer);
            }
            if (aliasRule != null) {
                final Layer spanLayer = Layer.valueOf(s.meta.getLayer());
                final Layer aliasLayer = layerOf(aliasMeta);
                filterSpec.getSpanPairing().serverSeen(aliasMeta,
                    MeterEntity.isNormal(aliasLayer != null ? aliasLayer : spanLayer), spanLayer.name(), aliasRule);
            }
        }
        traffic.forEach(row -> path.out.add("traffic " + row.getClass().getName() + " " + row.serialize().build()));
        traffic.clear();
        path.aliases.forEach(a -> path.out.add("alias " + a.getAddress() + " = " + a.getRepresentServiceId()
            + " / " + a.getRepresentServiceInstanceId()));
    }

    private static Layer layerOf(final SpanMetadata meta) {
        try {
            return meta.getLayer() == null || meta.getLayer().isEmpty() ? null : Layer.valueOf(meta.getLayer());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Same as upstream's private {@code TraceAnalyzerServiceImpl.copyOf}: a rule that overwrites a meta field does
     * not leak into the next rule.
     */
    private static SpanMetadata copyOf(final SpanMetadata meta) {
        return SpanMetadata.builder()
                           .service(meta.getService())
                           .serviceInstance(meta.getServiceInstance())
                           .endpoint(meta.getEndpoint())
                           .layer(meta.getLayer())
                           .kind(meta.getKind())
                           .statusCode(meta.getStatusCode())
                           .statusMessage(meta.getStatusMessage())
                           .startTime(meta.getStartTime())
                           .timestamp(meta.getTimestamp())
                           .latency(meta.getLatency())
                           .traceId(meta.getTraceId())
                           .spanId(meta.getSpanId())
                           .parentSpanId(meta.getParentSpanId())
                           .root(meta.isRoot())
                           .parentLocation(meta.getParentLocation())
                           .scopeName(meta.getScopeName())
                           .scopeVersion(meta.getScopeVersion())
                           .build();
    }

    /**
     * The core's cache timer would load the cold pass's rows from storage; this is that reload.
     */
    private static void loadAliasCache(final Run path) {
        final NetworkAddressAliasCache cache = mock(NetworkAddressAliasCache.class);
        for (final NetworkAddressAliasSetup setup : path.aliases) {
            final NetworkAddressAlias row = new NetworkAddressAlias();
            row.setAddress(setup.getAddress());
            row.setRepresentServiceId(setup.getRepresentServiceId());
            row.setRepresentServiceInstanceId(setup.getRepresentServiceInstanceId());
            Mockito.when(cache.get(setup.getAddress())).thenReturn(row);
        }
        Mockito.when(path.moduleManager.find(CoreModule.NAME).provider()
                                       .getService(NetworkAddressAliasCache.class)).thenReturn(cache);
    }

    private static final class AnalyzedSpan {
        private SpanMetadata meta;
        private Span span;
        private Map<String, String> resourceTags;
        private Map<String, String> scopeTags;
    }

    /**
     * The fixture's spans in start order, each with the metadata and attribute maps the receiver hands the
     * analyzer, then frontend's CLIENT span turned into a 350 ms MySQL call; every resource sends
     * {@code service.layer=<layer>}.
     */
    private static List<AnalyzedSpan> loadSpans(final String layer) throws Exception {
        final TracesData.Builder builder = TracesData.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(
            OTLPValues.hexIdsToBase64(new String(Files.readAllBytes(FIXTURE), StandardCharsets.UTF_8)), builder);
        final List<AnalyzedSpan> out = new ArrayList<>();
        AnalyzedSpan frontendClient = null;
        for (final ResourceSpans rs : builder.getResourceSpansList()) {
            final Map<String, String> resourceTags =
                new LinkedHashMap<>(OTLPValues.toStringMap(rs.getResource().getAttributesList()));
            resourceTags.put("service.layer", layer);
            for (final ScopeSpans ss : rs.getScopeSpansList()) {
                for (final Span span : ss.getSpansList()) {
                    final AnalyzedSpan a = analyzed(span, resourceTags, ss);
                    out.add(a);
                    if (frontendClient == null && "frontend".equals(a.meta.getService())
                        && "CLIENT".equals(a.meta.getKind())) {
                        frontendClient = a;
                    }
                }
            }
        }
        out.sort((x, y) -> Long.compare(x.span.getStartTimeUnixNano(), y.span.getStartTimeUnixNano()));
        final Span sql = frontendClient.span.toBuilder()
            .setSpanId(ByteString.copyFrom(new byte[] {0x5d, 0x1c, 0x0f, 0x3a, 0x77, 0x02, 0x41, 0x6b}))
            .setName("SELECT shop.cart")
            .setEndTimeUnixNano(frontendClient.span.getStartTimeUnixNano() + TimeUnit.MILLISECONDS.toNanos(350))
            .clearAttributes()
            .addAttributes(attr("db.system.name", "mysql"))
            .addAttributes(attr("db.namespace", "shop"))
            .addAttributes(attr("db.query.text", "SELECT * FROM cart WHERE user_id = ?"))
            .addAttributes(attr("server.address", "mysql"))
            .addAttributes(attr("server.port", "3306"))
            .build();
        final ScopeSpans scope = ScopeSpans.newBuilder().build();
        out.add(analyzed(sql, frontendClient.resourceTags, scope));
        return out;
    }

    private static KeyValue attr(final String key, final String value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    private static AnalyzedSpan analyzed(final Span span, final Map<String, String> resourceTags,
                                         final ScopeSpans ss) {
        final String parent = OTLPValues.hexId(span.getParentSpanId());
        final AnalyzedSpan a = new AnalyzedSpan();
        a.span = span;
        a.resourceTags = resourceTags;
        a.scopeTags = OTLPValues.toStringMap(ss.getScope().getAttributesList());
        a.meta = SpanMetadata.builder()
            .service(resourceTags.getOrDefault("service.name", ""))
            .serviceInstance(resourceTags.getOrDefault("service.instance.id", ""))
            .endpoint(span.getName()).layer(resourceTags.getOrDefault("service.layer", ""))
            .kind(SpanMetadata.normalizeKind(span.getKind().name()))
            .statusCode(SpanMetadata.normalizeStatusCode(span.getStatus().getCode().name()))
            .statusMessage(span.getStatus().getMessage())
            .startTime(TimeUnit.NANOSECONDS.toMillis(span.getStartTimeUnixNano()))
            .timestamp(TimeUnit.NANOSECONDS.toMillis(span.getEndTimeUnixNano()))
            .latency(SpanMetadata.latencyMillis(span.getStartTimeUnixNano(), span.getEndTimeUnixNano()))
            .traceId(OTLPValues.hexId(span.getTraceId())).spanId(OTLPValues.hexId(span.getSpanId()))
            .parentSpanId(parent).root(parent.isEmpty())
            .parentLocation(parentLocation(parent, span.getFlags()))
            .scopeName(ss.getScope().getName()).scopeVersion(ss.getScope().getVersion())
            .build();
        return a;
    }

    /**
     * Same as upstream's package-private {@code OpenTelemetryTraceHandler.parentLocation}.
     */
    private static String parentLocation(final String parentSpanId, final int flags) {
        if (parentSpanId.isEmpty() || (flags & SpanFlags.SPAN_FLAGS_CONTEXT_HAS_IS_REMOTE_MASK_VALUE) == 0) {
            return SpanMetadata.PARENT_UNKNOWN;
        }
        return (flags & SpanFlags.SPAN_FLAGS_CONTEXT_IS_REMOTE_MASK_VALUE) != 0
            ? SpanMetadata.PARENT_REMOTE : SpanMetadata.PARENT_LOCAL;
    }
}
