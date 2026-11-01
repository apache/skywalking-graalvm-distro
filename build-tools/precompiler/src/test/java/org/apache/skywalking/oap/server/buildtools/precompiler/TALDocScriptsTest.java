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

import com.google.common.reflect.ClassPath;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.IDManager;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.apdex.ApdexFunction;
import org.apache.skywalking.oap.server.core.cache.NetworkAddressAliasCache;
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.DatabaseSlowStatementBuilder;
import org.apache.skywalking.oap.server.configuration.api.ConfigChangeWatcher.ConfigChangeEvent;
import org.apache.skywalking.oap.server.configuration.api.ConfigChangeWatcher.EventType;
import org.apache.skywalking.oap.server.core.config.IComponentLibraryCatalogService;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.ServerClusterConfig;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.apache.skywalking.oap.server.core.source.NetworkAddressAliasSetup;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.core.trace.SpanMetadata;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.apache.skywalking.oap.server.library.module.ModuleProviderHolder;
import org.apache.skywalking.oap.server.library.module.ModuleServiceHolder;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.ExecutionContext;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.TalExpression;
import org.apache.skywalking.oap.trace.analyzer.v2.dsl.spec.FilterSpec;
import org.apache.skywalking.oap.trace.analyzer.v2.provider.TraceAnalyzerModuleConfig;
import org.apache.skywalking.oap.trace.analyzer.v2.spi.OtlpTALSourceTypeProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The TAL scripts upstream's documentation gives operators, read from the docs as they are: the rule in
 * {@code tal.md} and the edits the gateway, database and message queue pages under {@code otlp-trace-practices/}
 * tell you to make to your copy of {@code otlp-general.yaml}, spliced or substituted where the page says. Each variant is compiled by the distro's own TAL pass, and
 * the class the manifest names is run against spans the practice is about, so a documented script that the
 * native distro cannot build, or that no longer does what its page says, fails here.
 */
class TALDocScriptsTest {
    private static final Path SUBMODULE = Path.of(System.getProperty("user.dir")).getParent().getParent()
        .resolve("skywalking");
    private static final Path TAL_MD = SUBMODULE.resolve("docs/en/concepts-and-designs/tal.md");
    private static final Path PRACTICES = SUBMODULE.resolve("docs/en/setup/backend/otlp-trace-practices");
    private static final Path GATEWAY_MD = PRACTICES.resolve("gateway-overview.md");
    private static final Path CANARY_MD = PRACTICES.resolve("gateway-canary.md");
    private static final Path NGINX_MD = PRACTICES.resolve("gateway-nginx.md");
    private static final Path DATABASE_MD = PRACTICES.resolve("database-and-cache.md");
    private static final Path MQ_MD = PRACTICES.resolve("message-queue.md");
    private static final Path SHIPPED = SUBMODULE.resolve("oap-server/server-starter/src/main/resources/tal/otlp-general.yaml");
    private static final String DOCS = "tal-docs";

    private static final String CLIENT_ANCHOR = "if (peer && meta.kind == \"CLIENT\" && !isGateway(peer)";
    private static final String SERVER_ANCHOR = "if (meta.kind == \"SERVER\" || meta.kind == \"CONSUMER\") {";
    private static final String KINDS_MARKER = "findServerClusterName(peer, system)";

    private static Path OUT;
    private static Properties MANIFEST;
    /**
     * The shipped rules, by name in file order, and the one each practice edits: the rule holding the anchor the
     * page names.
     */
    private static List<String> SHIPPED_RULES;
    private static String CLIENT_RULE;
    private static String SERVER_RULE;
    private static String SLOW_SQL_RULE;

    @BeforeAll
    static void compileTheDocumentedScripts() throws Exception {
        final Path docsDir = Path.of(TALDocScriptsTest.class.getClassLoader().getResource("tal-fixture").toURI())
                                 .getParent().resolve(DOCS);
        Files.createDirectories(docsDir);
        try (Stream<Path> old = Files.list(docsDir)) {
            for (final Path f : (Iterable<Path>) old::iterator) {
                Files.delete(f);
            }
        }
        final List<String> shipped = Files.readAllLines(SHIPPED, StandardCharsets.UTF_8);
        SHIPPED_RULES = new ArrayList<>();
        try (InputStream in = Files.newInputStream(SHIPPED)) {
            final Map<String, Object> doc = new Yaml().load(in);
            for (final Object entry : (List<?>) doc.get("rules")) {
                final Map<?, ?> rule = (Map<?, ?>) entry;
                final String name = (String) rule.get("name");
                SHIPPED_RULES.add(name);
                final String dsl = (String) rule.get("dsl");
                if (CLIENT_RULE == null && dsl.contains(CLIENT_ANCHOR)) {
                    CLIENT_RULE = name;
                }
                if (SERVER_RULE == null && dsl.contains(SERVER_ANCHOR)) {
                    SERVER_RULE = name;
                }
                if (SLOW_SQL_RULE == null && "SlowSQL".equals(rule.get("outputType"))) {
                    SLOW_SQL_RULE = name;
                }
            }
        }

        write(docsDir, "address-named-peers.yaml", List.of(
            "metricPrefix: doc",
            "rules:",
            "  - name: address-named-peers",
            "    layer: OTLP_GENERAL",
            "    dsl: |",
            indent(block(TAL_MD, "prepareNetworkAlias peer"), "      ")));

        final List<String> gatewayPeer = block(GATEWAY_MD, "hasAttr(\"<attribute>\")");
        write(docsDir, "practice-gateway-peer.yaml", spliceBefore(shipped,
            "if (peer && !renamed && isGateway(peer + \":80\")) {", fill(gatewayPeer, "<attribute>", "gateway.upstream")));
        write(docsDir, "practice-canary-gateway.yaml", spliceBefore(shipped,
            CLIENT_ANCHOR, fill(block(CANARY_MD, "meta.service == \"<gateway>\""), "<gateway>", "gateway")));
        write(docsDir, "practice-canary-router.yaml", spliceBefore(shipped,
            CLIENT_ANCHOR, block(CANARY_MD, "!isGateway(peer) && attr(\"http.request.header.x-canary\")")));
        final List<String> nginx = block(NGINX_MD, "nginx's SERVER span");
        write(docsDir, "practice-nginx.yaml", spliceAfter(shipped, SERVER_ANCHOR, nginx));
        // The page's alternative picks nginx by a resource tag instead of its service name.
        final String byTag = block(NGINX_MD, "resource(\"proxy.type\") == \"nginx\"").get(0).trim();
        final List<String> nginxByTag = new ArrayList<>(nginx);
        boolean replaced = false;
        for (int i = 0; i < nginxByTag.size(); i++) {
            if (nginxByTag.get(i).trim().startsWith("if (meta.kind == \"SERVER\" && meta.service == \"nginx\")")) {
                nginxByTag.set(i, byTag);
                replaced = true;
            }
        }
        assertTrue(replaced, "the nginx practice no longer picks nginx by its service name");
        write(docsDir, "practice-nginx-by-tag.yaml", spliceAfter(shipped, SERVER_ANCHOR, nginxByTag));
        // Both pages give the same three calls, to be changed together.
        final List<String> kinds = block(DATABASE_MD, KINDS_MARKER);
        assertEquals(kinds, block(MQ_MD, KINDS_MARKER), "the database and message queue pages edit the calls alike");
        write(docsDir, "practice-cluster-kinds.yaml", substituteCalls(shipped, kinds));
        // The file as shipped, the rule the pages' "without a kind" means.
        write(docsDir, "otlp-general.yaml", shipped);

        OUT = Files.createTempDirectory("tal-docs-out");
        Files.createDirectories(OUT.resolve("META-INF"));
        MeterSystem.setFunctionRegister(Precompiler.meterFunctionRegister(
            ClassPath.from(TALDocScriptsTest.class.getClassLoader())
                     .getTopLevelClassesRecursive("org.apache.skywalking.oap.server.core.analysis.meter.function")));
        MeterSystem.setOutputDirectory(OUT.toString());
        Precompiler.compileTAL(OUT.toString(), new MeterSystem(null), DOCS);

        MANIFEST = new Properties();
        try (Reader reader = Files.newBufferedReader(OUT.resolve("META-INF/tal-v2-rules.txt"), StandardCharsets.UTF_8)) {
            MANIFEST.load(reader);
        }
    }

    @AfterAll
    static void tearDown() {
        MeterSystem.reset();
    }

    @Test
    void everyDocumentedScriptCompiles() {
        final List<String> compiled = new ArrayList<>();
        for (int i = 0; MANIFEST.getProperty("rule." + i + ".class") != null; i++) {
            compiled.add(MANIFEST.getProperty("rule." + i + ".source") + "#" + MANIFEST.getProperty("rule." + i + ".name"));
        }
        // Every practice is an edit of your copy of the shipped file, so each variant carries all its rules.
        final List<String> expected = new ArrayList<>(List.of("tal/address-named-peers.yaml#address-named-peers"));
        for (final String variant : List.of("otlp-general.yaml", "practice-canary-gateway.yaml",
            "practice-canary-router.yaml", "practice-cluster-kinds.yaml", "practice-gateway-peer.yaml",
            "practice-nginx-by-tag.yaml", "practice-nginx.yaml")) {
            SHIPPED_RULES.forEach(rule -> expected.add("tal/" + variant + "#" + rule));
        }
        expected.sort(null);
        compiled.sort(null);
        assertEquals(expected, compiled);
    }

    @Test
    void theAddressNamedPeersRuleLearnsTheAliasFromBothHalves() throws Exception {
        final Harness h = new Harness();
        final AnalyzedSpan client = span("frontend", "CLIENT", Map.of(), Map.of("server.address", "cart", "server.port",
            "7070"), "1111111111111111", "0000000000000001");
        assertEquals("cart:7070", h.run("address-named-peers.yaml", "address-named-peers", client).getNetworkAliasPeer());
        final AnalyzedSpan server = span("cart", "SERVER", Map.of(), Map.of(), "2222222222222222", "1111111111111111");
        assertTrue(h.run("address-named-peers.yaml", "address-named-peers", server).isNetworkAliasRequested(),
            "the SERVER span asks for the alias its caller prepared");
        assertEquals(List.of("frontend->cart:7070 = cart"), h.aliases,
            "the documented buildNetworkAlias composes the caller-scoped address the callee is learned under");
    }

    @Test
    void aGatewayUpstreamAttributeNamesThePeer() throws Exception {
        final Map<String, String> upstream = Map.of("gateway.upstream", "10.0.1.5:8080");
        assertEquals("10.0.1.5:8080", new Harness().run("practice-gateway-peer.yaml", CLIENT_RULE,
            span("gateway", "CLIENT", Map.of(), upstream)).getNetworkAliasPeer());
        assertNull(new Harness().run("practice-canary-router.yaml", CLIENT_RULE,
                span("gateway", "CLIENT", Map.of(), upstream)).getNetworkAliasPeer(),
            "without the edit the attribute names nothing");
    }

    @Test
    void theCanaryHeaderGoesIntoThePeer() throws Exception {
        final Map<String, String> v2 = Map.of("server.address", "b", "server.port", "8080",
            "http.request.header.x-canary", "v2");
        final Map<String, String> v1 = Map.of("server.address", "b", "server.port", "8080",
            "http.request.header.x-canary", "v1");
        assertEquals("b:8080(v2)", new Harness().run("practice-canary-gateway.yaml", CLIENT_RULE,
            span("gateway", "CLIENT", Map.of(), v2)).getNetworkAliasPeer());
        assertEquals("b:8080", new Harness().run("practice-canary-gateway.yaml", CLIENT_RULE,
            span("a", "CLIENT", Map.of(), v2)).getNetworkAliasPeer(), "only the gateway's own spans");
        assertEquals("b:8080", new Harness().run("practice-canary-router.yaml", CLIENT_RULE,
            span("a", "CLIENT", Map.of(), v1)).getNetworkAliasPeer(), "only the value routed on");

        // "The canary's calls are then a->b:8080(v2) -> b-canary, the others a->b:8080 -> b".
        final Harness h = new Harness();
        h.run("practice-canary-router.yaml", CLIENT_RULE, span("a", "CLIENT", Map.of(), v2, "3333333333333333", ""));
        h.run("practice-canary-router.yaml", SERVER_RULE, span("b-canary", "SERVER", Map.of(), Map.of(),
            "4444444444444444", "3333333333333333"));
        h.run("practice-canary-router.yaml", CLIENT_RULE, span("a", "CLIENT", Map.of(), v1, "5555555555555555", ""));
        h.run("practice-canary-router.yaml", SERVER_RULE, span("b", "SERVER", Map.of(), Map.of(),
            "6666666666666666", "5555555555555555"));
        assertEquals(List.of("a->b:8080(v2) = b-canary", "a->b:8080 = b"), h.aliases);

        // "!isGateway(peer) keeps a call to a gateway listed in gateways.yml as it is".
        final Harness gateway = new Harness();
        gateway.gateways.add("ingress:80");
        final ExecutionContext toIngress = gateway.run("practice-canary-router.yaml", CLIENT_RULE,
            span("a", "CLIENT", Map.of(), Map.of("server.address", "ingress", "server.port", "80",
                "http.request.header.x-canary", "v2")));
        assertNull(toIngress.getNetworkAliasPeer(), "a listed gateway prepares no alias");
        assertTrue(gateway.accepted.stream().anyMatch(a -> a.contains("dest=ingress:80 ")), gateway.accepted.toString());
        assertTrue(gateway.accepted.stream().noneMatch(a -> a.contains("(v2)")), gateway.accepted.toString());
    }

    @Test
    void nginxDrawsItsUpstreamCallAndStillLearnsItsCallers() throws Exception {
        final Map<String, String> upstream = Map.of("upstream.address", "10.0.3.4:8080");
        final Harness h = new Harness();
        final ExecutionContext nginx = h.run("practice-nginx.yaml", SERVER_RULE,
            span("nginx", "SERVER", Map.of(), upstream));
        assertEquals("10.0.3.4:8080", nginx.getNetworkAliasPeer(), "the caller half of the upstream hop");
        assertTrue(nginx.isNetworkAliasRequested(), "the rest of the SERVER block runs, so its callers are learned too");
        for (final String metric : List.of("otlp_service_relation_client_cpm", "otlp_service_relation_client_resp_time",
            "otlp_service_relation_client_call_sla")) {
            assertTrue(h.accepted.stream().anyMatch(a -> a.startsWith(metric + " SERVICE_RELATION src=nginx "
                    + "dest=10.0.3.4:8080 ")), metric + " on nginx -> its upstream: " + h.accepted);
        }
        assertTrue(h.accepted.contains("otlp_service_relation_client_cpm SERVICE_RELATION src=nginx "
            + "dest=10.0.3.4:8080 value=1"), "one call: " + h.accepted);
        assertTrue(h.accepted.contains("otlp_service_relation_client_resp_time SERVICE_RELATION src=nginx "
            + "dest=10.0.3.4:8080 value=50"), "the relation's latency is the whole request at nginx: " + h.accepted);
        assertTrue(h.accepted.contains("otlp_service_relation_client_call_sla SERVICE_RELATION src=nginx "
            + "dest=10.0.3.4:8080 value=true"), "an UNSET status with no HTTP error is a success: " + h.accepted);

        final Harness noUpstream = new Harness();
        assertNull(noUpstream.run("practice-nginx.yaml", SERVER_RULE, span("nginx", "SERVER", Map.of(), Map.of()))
                             .getNetworkAliasPeer(), "a request nginx answers itself draws no call");
        assertTrue(noUpstream.accepted.stream().noneMatch(a -> a.startsWith("otlp_service_relation_client_")),
            noUpstream.accepted.toString());
        final Harness other = new Harness();
        assertNull(other.run("practice-nginx.yaml", SERVER_RULE, span("checkout", "SERVER", Map.of(), upstream))
                        .getNetworkAliasPeer(), "another service's SERVER span draws no call");
        assertTrue(other.accepted.stream().noneMatch(a -> a.startsWith("otlp_service_relation_client_")),
            other.accepted.toString());

        assertEquals("10.0.3.4:8080", new Harness().run("practice-nginx-by-tag.yaml", SERVER_RULE,
            span("edge-proxy", "SERVER", Map.of("proxy.type", "nginx"), upstream)).getNetworkAliasPeer());
        assertNull(new Harness().run("practice-nginx-by-tag.yaml", SERVER_RULE,
            span("nginx", "SERVER", Map.of(), upstream)).getNetworkAliasPeer(), "picked by the tag, not the name");
    }

    @Test
    void kindsRenameADatabaseOrCacheClusterOnlyForItsOwnSystem() throws Exception {
        final String clusters = String.join("\n", block(DATABASE_MD, "kinds: [postgresql]"));
        final Map<String, String> postgres = Map.of("db.system.name", "postgresql",
            "server.address", "pg-0.pg.db.svc.cluster.local", "server.port", "5432");
        final Map<String, String> mysql = Map.of("db.system.name", "mysql",
            "server.address", "pg-0.pg.db.svc.cluster.local", "server.port", "5432");
        final Map<String, String> redis = Map.of("db.system.name", "redis",
            "server.address", "redis-1.redis.cache.svc.cluster.local", "server.port", "6379");
        final Map<String, String> postgresAtRedis = Map.of("db.system.name", "postgresql",
            "server.address", "redis-1.redis.cache.svc.cluster.local", "server.port", "6379");

        assertEquals("orders-db", clientDest("practice-cluster-kinds.yaml", clusters, postgres));
        assertEquals("pg-0.pg.db.svc.cluster.local:5432", clientDest("practice-cluster-kinds.yaml", clusters, mysql),
            "a postgresql cluster is not a mysql client's");
        assertEquals("session-cache", clientDest("practice-cluster-kinds.yaml", clusters, redis));
        assertEquals("redis-1.redis.cache.svc.cluster.local:6379",
            clientDest("practice-cluster-kinds.yaml", clusters, postgresAtRedis));
        assertEquals("orders-db", clientDest("otlp-general.yaml", clusters, mysql),
            "the shipped rule asks without a kind, so any system's client is renamed");

        final Map<String, String> slow = new HashMap<>(postgres);
        slow.put("db.query.text", "SELECT * FROM orders");
        assertEquals("orders-db", slowStatementService("practice-cluster-kinds.yaml", clusters, slow),
            "the slow statement is filed under the node the topology draws");
        final Map<String, String> slowMysql = new HashMap<>(mysql);
        slowMysql.put("db.query.text", "SELECT * FROM orders");
        assertEquals("pg-0.pg.db.svc.cluster.local:5432",
            slowStatementService("practice-cluster-kinds.yaml", clusters, slowMysql));
    }

    @Test
    void kindsRenameABrokerClusterOnlyForItsOwnSystem() throws Exception {
        final String clusters = String.join("\n", block(MQ_MD, "kinds: [kafka]"));
        final Map<String, String> kafka = Map.of("messaging.system.name", "kafka",
            "server.address", "kafka.prod.svc.cluster.local", "server.port", "9092");
        final Map<String, String> rabbit = Map.of("messaging.system", "rabbitmq",
            "server.address", "kafka.prod.svc.cluster.local", "server.port", "9092");

        assertEquals("kafka-prod", consumerBroker("practice-cluster-kinds.yaml", clusters, kafka));
        assertEquals("kafka.prod.svc.cluster.local:9092", consumerBroker("practice-cluster-kinds.yaml", clusters, rabbit),
            "a kafka cluster is not a rabbitmq consumer's");
        assertEquals("kafka-prod", consumerBroker("otlp-general.yaml", clusters, rabbit));

        final Harness producer = new Harness(clusters);
        producer.run("practice-cluster-kinds.yaml", CLIENT_RULE, span("orders", "PRODUCER", Map.of(), kafka));
        assertTrue(producer.accepted.contains("otlp_service_relation_client_cpm SERVICE_RELATION src=orders "
            + "dest=kafka-prod value=1"), "the producer's side meets the consumer's at the cluster: " + producer.accepted);
    }

    /**
     * @return the peer the rule draws a CLIENT span's call to
     */
    private static String clientDest(final String file, final String clusters,
                                     final Map<String, String> attributes) throws Exception {
        final Harness h = new Harness(clusters);
        h.run(file, CLIENT_RULE, span("checkout", "CLIENT", Map.of(), attributes));
        return relationEnd(h, "otlp_service_relation_client_cpm SERVICE_RELATION src=checkout dest=", true);
    }

    /**
     * @return the broker the rule draws a CONSUMER span's message from
     */
    private static String consumerBroker(final String file, final String clusters,
                                         final Map<String, String> attributes) throws Exception {
        final Harness h = new Harness(clusters);
        h.run(file, SERVER_RULE, span("shipping", "CONSUMER", Map.of(), attributes));
        return relationEnd(h, "otlp_service_relation_server_cpm SERVICE_RELATION src=", false);
    }

    private static String relationEnd(final Harness h, final String prefix, final boolean dest) {
        for (final String a : h.accepted) {
            if (a.startsWith(prefix)) {
                final String rest = a.substring(prefix.length());
                return dest ? rest.substring(0, rest.indexOf(' ')) : rest.substring(0, rest.indexOf(" dest="));
            }
        }
        throw new AssertionError("no " + prefix + " in " + h.accepted);
    }

    private static String slowStatementService(final String file, final String clusters,
                                               final Map<String, String> attributes) throws Exception {
        final Harness h = new Harness(clusters);
        final AnalyzedSpan statement = span("checkout", "CLIENT", Map.of(), attributes, "b7ad6b7169203331",
            "00f067aa0ba902b7", 350);
        final ExecutionContext ctx = h.run(file, SLOW_SQL_RULE, statement);
        assertFalse(ctx.isAborted(), "a 350 ms statement is slow");
        assertTrue(ctx.isSinkKept(), "the rule's sink keeps the record");
        // TraceAnalyzerServiceImpl#writeRecord fills the record from the metadata the rule's `service` set.
        final DatabaseSlowStatementBuilder record = (DatabaseSlowStatementBuilder) ctx.outputAsBuilder();
        record.init(ctx.getMeta(), statement.span, h.filterSpec.getModuleManager());
        return record.getServiceName();
    }

    private static final class AnalyzedSpan {
        private final SpanMetadata meta;
        private final Span span;
        private final Map<String, String> resource;

        private AnalyzedSpan(final SpanMetadata meta, final Span span, final Map<String, String> resource) {
            this.meta = meta;
            this.span = span;
            this.resource = resource;
        }
    }

    /**
     * One OAP's view: the classes the manifest names, run as {@code TraceAnalyzerServiceImpl} runs them, with the
     * pairing, the gateways list, the server clusters, every value a metric accepts and every alias row written.
     */
    private static final class Harness {
        private final List<String> accepted = new ArrayList<>();
        private final List<String> aliases = new ArrayList<>();
        private final Set<String> gateways = new HashSet<>();
        private final Map<String, TalExpression> loaded = new HashMap<>();
        private final FilterSpec filterSpec;

        private Harness() {
            this("");
        }

        /**
         * @param clusters the content of {@code server-clusters.yml}, as the dynamic configuration delivers it
         */
        private Harness(final String clusters) {
            final MeterSystem meterSystem = mock(MeterSystem.class);
            when(meterSystem.buildMetrics(anyString(), any())).thenAnswer(invocation -> {
                final String metric = invocation.getArgument(0);
                final AcceptableValue<?> value = mock(AcceptableValue.class);
                doAnswer(accept -> {
                    final MeterEntity e = accept.getArgument(0);
                    accepted.add(metric + " " + e.getScopeType() + " src=" + e.getSourceServiceName() + " dest="
                        + e.getDestServiceName() + " value=" + accept.getArgument(1));
                    return null;
                }).when(value).accept(any(), any());
                return value;
            });
            final NamingControl namingControl = new NamingControl(70, 70, 150, new EndpointNameGrouping());
            MeterEntity.setNamingControl(namingControl);
            ApdexFunction.setDICT(name -> 500);
            final SourceReceiver sourceReceiver = mock(SourceReceiver.class);
            doAnswer(invocation -> {
                if (invocation.getArgument(0) instanceof NetworkAddressAliasSetup) {
                    final NetworkAddressAliasSetup setup = invocation.getArgument(0);
                    setup.prepare();
                    aliases.add(setup.getAddress() + " = "
                        + IDManager.ServiceID.analysisId(setup.getRepresentServiceId()).getName());
                }
                return null;
            }).when(sourceReceiver).receive(any());
            final ServerClusterConfig serverClusters = new ServerClusterConfig(mock(ModuleProvider.class), 70);
            serverClusters.notify(new ConfigChangeEvent(clusters, EventType.MODIFY));
            filterSpec = new FilterSpec(moduleManager(serverClusters, namingControl), new TraceAnalyzerModuleConfig(),
                meterSystem, namingControl, sourceReceiver);
            filterSpec.getSpanPairing().getAliasRegistrar().setGateways(gateways::contains);
        }

        /**
         * Run one rule on one span, then send the span's pairing halves as {@code TraceAnalyzerServiceImpl} does;
         * a matched pair is composed by the callee rule's own {@code buildNetworkAlias}.
         */
        private ExecutionContext run(final String file, final String rule, final AnalyzedSpan span) throws Exception {
            final TalExpression expression = loaded.computeIfAbsent(file + "#" + rule, key -> load(file, rule));
            filterSpec.getSpanPairing().setAddressComposer(
                (caller, callee) -> expression.networkAliasAddress(filterSpec, new ExecutionContext(), caller));
            final ExecutionContext ctx = new ExecutionContext();
            ctx.setSourceTypeProvider(new OtlpTALSourceTypeProvider());
            ctx.init(span.meta, span.span, span.resource, Map.of());
            expression.execute(filterSpec, ctx);
            if (!ctx.isAborted() && ctx.getNetworkAliasPeer() != null) {
                filterSpec.getSpanPairing().clientSeen(span.meta, ctx.getNetworkAliasPeer());
            }
            if (!ctx.isAborted() && ctx.isNetworkAliasRequested()) {
                filterSpec.getSpanPairing().serverSeen(span.meta, true, span.meta.getLayer(), rule);
            }
            return ctx;
        }
    }

    /**
     * The class the manifest names for the rule, instantiated as the runtime {@code TalDSL} would load it.
     */
    private static TalExpression load(final String file, final String rule) {
        for (int i = 0; MANIFEST.getProperty("rule." + i + ".class") != null; i++) {
            if (("tal/" + file).equals(MANIFEST.getProperty("rule." + i + ".source"))
                && rule.equals(MANIFEST.getProperty("rule." + i + ".name"))) {
                try {
                    return (TalExpression) Class.forName(MANIFEST.getProperty("rule." + i + ".class"))
                                                .getDeclaredConstructor().newInstance();
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new AssertionError("no " + rule + " compiled from " + file);
    }

    private static ModuleManager moduleManager(final ServerClusterConfig serverClusters,
                                               final NamingControl namingControl) {
        final IComponentLibraryCatalogService catalog = mock(IComponentLibraryCatalogService.class);
        when(catalog.getComponentIdIgnoreCase(anyString())).thenReturn(49);
        final ModuleServiceHolder core = mock(ModuleServiceHolder.class);
        when(core.getService(IComponentLibraryCatalogService.class)).thenReturn(catalog);
        when(core.getService(NetworkAddressAliasCache.class)).thenReturn(mock(NetworkAddressAliasCache.class));
        when(core.getService(ServerClusterConfig.class)).thenReturn(serverClusters);
        when(core.getService(NamingControl.class)).thenReturn(namingControl);
        final ModuleProviderHolder coreModule = mock(ModuleProviderHolder.class);
        when(coreModule.provider()).thenReturn(core);
        final ModuleManager moduleManager = mock(ModuleManager.class);
        when(moduleManager.find(CoreModule.NAME)).thenReturn(coreModule);
        return moduleManager;
    }

    private static AnalyzedSpan span(final String service, final String kind, final Map<String, String> resource,
                                     final Map<String, String> attributes) {
        return span(service, kind, resource, attributes, "b7ad6b7169203331", "00f067aa0ba902b7");
    }

    private static AnalyzedSpan span(final String service, final String kind, final Map<String, String> resource,
                                     final Map<String, String> attributes, final String spanId,
                                     final String parentSpanId) {
        return span(service, kind, resource, attributes, spanId, parentSpanId, 50);
    }

    private static AnalyzedSpan span(final String service, final String kind, final Map<String, String> resource,
                                     final Map<String, String> attributes, final String spanId,
                                     final String parentSpanId, final int latencyMillis) {
        final Span.Builder span = Span.newBuilder().setName("GET /").setStartTimeUnixNano(1_700_000_000_000_000_000L)
                                      .setEndTimeUnixNano(1_700_000_000_000_000_000L + latencyMillis * 1_000_000L);
        attributes.forEach((k, v) -> span.addAttributes(
            KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setStringValue(v))));
        final Map<String, String> resourceTags = new HashMap<>(resource);
        resourceTags.put("service.name", service);
        resourceTags.put("service.layer", "OTLP_GENERAL");
        final SpanMetadata meta = SpanMetadata.builder()
            .service(service).serviceInstance("").endpoint("GET /").layer("OTLP_GENERAL")
            .kind(kind).statusCode("UNSET").statusMessage("")
            .startTime(1_700_000_000_000L).timestamp(1_700_000_000_000L + latencyMillis).latency(latencyMillis)
            .traceId("0af7651916cd43dd8448eb211c80319c").spanId(spanId)
            .parentSpanId(parentSpanId).root(parentSpanId.isEmpty())
            .parentLocation(parentSpanId.isEmpty() ? SpanMetadata.PARENT_UNKNOWN : SpanMetadata.PARENT_REMOTE)
            .scopeName("doc-test").scopeVersion("1")
            .build();
        return new AnalyzedSpan(meta, span.build(), resourceTags);
    }

    /**
     * @return the lines of the fenced code block that holds {@code marker}; fails when the page no longer has it
     */
    private static List<String> block(final Path page, final String marker) throws Exception {
        final List<String> lines = Files.readAllLines(page, StandardCharsets.UTF_8);
        List<String> current = null;
        for (final String line : lines) {
            if (line.trim().startsWith("```")) {
                if (current == null) {
                    current = new ArrayList<>();
                } else {
                    if (String.join("\n", current).contains(marker)) {
                        return dedent(current);
                    }
                    current = null;
                }
            } else if (current != null) {
                current.add(line);
            }
        }
        throw new AssertionError(page.getFileName() + " has no code block with " + marker);
    }

    private static List<String> dedent(final List<String> lines) {
        int common = Integer.MAX_VALUE;
        for (final String line : lines) {
            if (!line.isBlank()) {
                common = Math.min(common, line.length() - line.stripLeading().length());
            }
        }
        final List<String> result = new ArrayList<>();
        for (final String line : lines) {
            result.add(line.isBlank() ? "" : line.substring(common));
        }
        return result;
    }

    private static List<String> fill(final List<String> lines, final String placeholder, final String value) {
        final List<String> result = new ArrayList<>();
        lines.forEach(line -> result.add(line.replace(placeholder, value)));
        return result;
    }

    private static String indent(final List<String> lines, final String prefix) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i).isEmpty() ? "" : prefix + lines.get(i));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> spliceBefore(final List<String> file, final String anchor, final List<String> snippet) {
        final int at = indexOf(file, anchor);
        final String prefix = file.get(at).substring(0, file.get(at).length() - file.get(at).stripLeading().length());
        final List<String> result = new ArrayList<>(file.subList(0, at));
        result.add(indent(snippet, prefix));
        result.addAll(file.subList(at, file.size()));
        return result;
    }

    private static List<String> spliceAfter(final List<String> file, final String anchor, final List<String> snippet) {
        final int at = indexOf(file, anchor);
        final String prefix = file.get(at).substring(0, file.get(at).length() - file.get(at).stripLeading().length())
            + "  ";
        final List<String> result = new ArrayList<>(file.subList(0, at + 1));
        result.add(indent(snippet, prefix));
        result.addAll(file.subList(at + 1, file.size()));
        return result;
    }

    /**
     * The edit the database and message queue pages give: each {@code def cluster = findServerClusterName(x, ...)}
     * line replaces the shipped file's next {@code def cluster = findServerClusterName(x)}, in the order the page
     * lists them, and none of the shipped calls is left without a kind.
     */
    private static List<String> substituteCalls(final List<String> file, final List<String> calls) {
        final Pattern call = Pattern.compile("def cluster = findServerClusterName\\((\\w+), .+\\)");
        final List<String> result = new ArrayList<>(file);
        int from = 0;
        int substituted = 0;
        for (final String line : calls) {
            final Matcher m = call.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            final String shippedCall = "def cluster = findServerClusterName(" + m.group(1) + ")";
            final int at = indexOf(result.subList(from, result.size()), shippedCall) + from;
            final String prefix = result.get(at).substring(0, result.get(at).length() - result.get(at).stripLeading().length());
            result.set(at, prefix + line.trim());
            from = at + 1;
            substituted++;
        }
        assertEquals(3, substituted, "the pages give the three calls of the shipped file");
        assertTrue(result.stream().noneMatch(l -> l.trim().matches("def cluster = findServerClusterName\\(\\w+\\)")),
            "every shipped call takes the kind: " + result);
        return result;
    }

    /**
     * @return the first line of the shipped file starting with {@code anchor}, the place the practice page names
     */
    private static int indexOf(final List<String> file, final String anchor) {
        for (int i = 0; i < file.size(); i++) {
            if (file.get(i).trim().startsWith(anchor)) {
                return i;
            }
        }
        throw new AssertionError("otlp-general.yaml has no line " + anchor + "; the practice no longer fits it");
    }

    private static void write(final Path dir, final String name, final List<String> lines) throws Exception {
        write(dir, name, String.join("\n", lines));
    }

    private static void write(final Path dir, final String name, final String content) throws Exception {
        final Matcher placeholder = Pattern.compile("<[a-z][a-z-]*>").matcher(content);
        assertFalse(placeholder.find(), name + " still holds a placeholder: " + (placeholder.find(0) ? placeholder.group() : ""));
        Files.writeString(dir.resolve(name), content + "\n", StandardCharsets.UTF_8);
    }
}
