# Changes

## 0.5.0

### Upstream Sync

- Sync SkyWalking submodule to upstream master `476afadecd` (11.1.0-SNAPSHOT), 13 commits past the v11.0.0 tag.
- Wire the two new analyzer modules: `ai-evaluation` (SWIP-16 LLM-as-judge over sampled GenAI spans; disabled by default, configured through the new `config/ai-evaluation.yml`) and `ai-agent-conversation` (AI agent conversations landed by the AI Sessionizer; on by default because the GraphQL query module requires it, turned off with its `none` provider).
- Adopt every rule file upstream added or changed: `lal/ai-agent.yaml` (`ConversationFile` output builder), `meter-analyzer-config/gen-ai-model.yaml` (judge scores), `otel-rules/ai-agent/runtime-{instance,service}.yaml`, `otel-rules/banyandb/banyandb-trace-sampling.yaml`, and the AI-evaluation counters in `otel-rules/oap.yaml` — all enabled with the upstream defaults.
- Mirror the new `application.yml` sections and defaults, and pick up upstream's BanyanDB credential / trust-CA hot reload (`secretsManagementFile`), the `recordsAIAgent` BanyanDB group, the Zipkin cold-stage query, and the ports opening once boot completes (`ModuleProvider#notifyBootCompleted`, which the fixed module manager reaches through `BootstrapFlow`).
- Sync SkyWalking submodule to upstream master `456199a1f8` (11.1.0-SNAPSHOT), 47 commits past `476afadecd`.
- Sync SkyWalking submodule to upstream master `5484b9693e` (11.1.0-SNAPSHOT), 3 commits past `456199a1f8`: the shipped TAL rule counts a CONSUMER `receive` span that has a parent, the .NET Kafka instrumentation's only record of a message, as the consumer's request (a root CONSUMER `receive` and any CLIENT `receive` are still dropped), the OpenTelemetry Trace practices page is split into `otlp-trace-practices/`, and the CA-rotation e2e case is rewritten.
- Sync SkyWalking submodule to upstream master `f4d38fdc21` (11.1.0-SNAPSHOT), 1 commit past `5484b9693e`: server clusters (apache/skywalking#14183). `server-clusters.yml`, and its dynamic configuration item `core.default.serverClusters`, name a message broker, database or cache that clients reach at several addresses as one service. The native trace analysis renames such peers (recompiled, the distro replaces none of the listeners), and on the OTLP path the new TAL function `findServerClusterName(address[, kind])` does, which the shipped `otlp-general` and `otlp-slow-sql` rules call. The shipped `otlp-general` rule also reads `messaging.system.name` before `messaging.system`, reads `aws_sqs` / `aws_sns` as `aws.sqs` / `aws.sns`, and drops a CLIENT `settle` span as it drops a CLIENT `receive`.
- **TAL (Trace Analysis Language) on the native image.** Wire upstream's new `trace-analyzer` module (on by default, `SW_TRACE_ANALYZER=-` removes it) and pre-compile its rules like LAL: the precompiler compiles `tal/otlp-general.yaml` (`otlp-general`, `otlp-slow-sql`) with `TALClassGenerator`, generates the 28 `otlp_*` meter classes through the build-time `MeterSystem`, and writes `tal-v2-rules.txt` (class, input type and metric declarations per `tal/<file>.yaml:<line>`) plus `config-data/tal.json`. The new `trace-analyzer-for-graalvm` module replaces `TalDSL` (loads the pre-compiled class, then claims and creates its metrics, `limitLabels` included) and `TALConfigs` (JSON instead of `tal/*.yaml`); upstream's `SpanFilterListener`, pairing and receiver path run unchanged.
- Adopt every rule file upstream added or changed: `otel-rules/ai-agent/mcp_endpoint.yaml`, OpenTelemetry-native host and process monitoring (`otel-rules/process-hostmetrics-{linux,windows}.yaml`, reworked `vm.yaml` / `windows.yaml`), the Kafka, RabbitMQ, PostgreSQL, MySQL, Kong, APISIX, Zabbix and AI agent rule fixes, and `oal/mesh.oal` — all enabled with the upstream defaults.
- Mirror the new `application.yml` options: native OTLP trace storage (`otlpTraceStorage: otlp` is the new default; `zipkin` keeps the converted path), OTLP service grouping, searchable tags, head sampling and rate cap, the TraceQL `/otlp` datasource, the Kubernetes metadata read settings, the alarm hooks' `httpTimeout`, and the renamed `ai-agent-conversation` read windows plus `hide`.
- TAL rules are user-editable, so the pre-compilation is generic: every `tal/*.yaml` is compiled, whatever it declares (any metric function, labeled metrics with their `labelLimit`, percentile `buckets` / `ranks`, inline `layerDefinitions` used across files, alias or class-name input and output types), the SPI providers and builders are discovered rather than listed, any file name works as upstream takes it (two files sharing a stem fail the build), and the runtime loads only the class compiled from exactly the inputs it is given (coordinates, name, DSL text, input and output type, metric prefix: flow-style YAML can put two rules on one line). Files are installed in upstream's order, which decides which rule runs first on a span.
- The pre-compiled LAL and TAL rule manifests are written with `Properties` escapes in UTF-8 and read the same way, so a rule or file name with non-ASCII characters, backslashes or leading spaces loads as written.

### GraalVM Native Image Compatibility

- Embed every `query-protocol/*.graphqls` schema with one glob instead of a per-file list; upstream's new `gen-ai-evaluation-record` and `ai-agent-conversation` schemas were missing and the native OAP died at boot.
- The precompiler's Armeria scan collects `@Decorator` targets (the AI agent conversation view's `CompressResponse`): Armeria instantiates them reflectively, and without the metadata the native OAP died at boot with "cannot inject the dependency".
- Register the admin-server family's Jackson response POJOs (`org.apache.skywalking.oap.server.admin.*.response.*`) for reflection: `/inspect/*` answered HTTP 400 (`No serializer found for class ...MetricsResponse`) in the native image since the 11.0.0 sync.
- Register the protobuf descriptor closure of the OTLP/HTTP JSON receivers' `Export{Logs,Metrics,Trace}ServiceRequest` and of `LogData` (parsed by `ProtoBufJsonUtils`): OTLP/HTTP JSON requests failed natively with `Generated message class ... missing method getResourceLogsList`.
- Register the `ConversationFile` LAL output builder for reflection, regenerate the config loaders (`AIEvaluationConfig`, `AIAgentConversationConfig`, `BanyanDBStorageConfig$RecordsAIAgent`), and package `ai-evaluation.yml` in both distributions.
- Port upstream's `MeterSystem#limitLabels` into both `MeterSystem` replacements, the five Kubernetes metadata settings into `CoreModuleConfig`, and the OTLP storage / grouping / sampling settings and helpers into `OtelMetricReceiverConfig`; regenerate the config loaders (`TraceAnalyzerModuleConfig`, the new `AlarmModuleConfig`, `BanyanDBStorageConfig$OtlpTrace`, TraceQL and AI agent conversation fields).
- Retire the `HttpAlarmCallback` replacement: upstream now injects the `HttpClient` through the constructor, so the static field the replacement worked around is gone, and the old replacement would have broken the new hook constructors.
- Register for reflection the TAL expression and meter classes, `TALConfigs` / `TALConfig` / `LayerDefinition`, every input type, input provider and output builder the TAL rules resolve through, and `PairingFact` (TAL pairing halves cross OAP nodes as stream data and are rebuilt with `newInstance()`) and `SpanMetadata`, both of which a rule's `toJson()` hands to Gson, together with the protobuf runtime classes Gson walks when a rule passes it the `span` itself; a native probe rule calling `toJson(meta)` and `toJson(span)` returns the same JSON as the JVM OAP.
- Register the protobuf descriptor closure of `ExportTraceServiceResponse` (now JSON-printed by the OTLP/HTTP trace receiver) and Tempo's `TraceByIDResponse` (JSON-printed by the TraceQL `/otlp` trace-by-id route).
- Register the status module's `Debugging*Rsp` DTOs (trace, OTLP, Zipkin, log, MQE, topology): `/debugging/*` serializes them to YAML through Jackson, and none were covered — a pre-existing gap since the 11.0.0 relocation, plus the new OTLP debug-trace endpoints.
- Bundle `ios-analyzer`: the distro never shipped it, so the iOS HTTP and MetricKit span listeners (SWIP-11, since 11.0.0) did not run and an iOS app's `service_cpm`, endpoint and instance traffic stayed empty; the iOS MAL and LAL rules worked, which hid the gap. Every other upstream `server-starter` dependency the distro leaves out is excluded by policy (other cluster, configuration and storage plugins, `runtime-rule`).
- Reflection metadata for three JSON paths no e2e case reached: the admin `/status/cluster/nodes` answer (core `Address`, HTTP 500 without it), the `/status/alarm/*` answers (the `core.alarm.provider.status` classes Gson prints and parses, `{}` without them) and the AWS Firehose receiver's request and answer (`FirehoseReq`, `RequestData`, `FirehoseRes`; every post answered 400 without them).
- Package `server-clusters.yml` (empty, as upstream ships it) in both distributions and embed it as a native-image resource beside `gateways.yml`; `ServerClusterConfig` parses it with SnakeYAML's `SafeConstructor` into plain maps and lists, so it needs no reflection metadata.
- Register the UI template REST bodies (`DashboardSetting`, `DashboardConfiguration`, `TemplateChangeStatus`) for reflection: they lost their metadata when upstream removed `ui-configuration.graphqls` in 11.0.0, so on 0.4.0 `POST` / `PUT /ui-management/templates` failed natively (`MissingReflectionRegistrationError` on the `DashboardSetting` constructor).

### Documentation

- Document the new modules in `distro-policy.md` and `configuration.md` (new `ai-evaluation` / `ai-agent-conversation` sections; refreshed `lalFiles`, `malFiles` and `meterAnalyzerActiveFiles` defaults), refresh the build-time counts, list `server-clusters.yml` among the `config/` files in `distro-policy.md`, move the `version-mapping.md` dev row to `0.5.0-SNAPSHOT` and add `0.4.0` → `11.0.0`, and record the native-image pitfalls in the sync skill.
- Document TAL in `distro-policy.md`, `internals/dsl-immigration.md` and `CLAUDE.md` (five DSLs; TAL rules are not live-debuggable because upstream emits no TAL probes), the new `trace-analyzer` and `traceQL` sections and refreshed `receiver-otel`, `core`, `alarm` and `ai-agent-conversation` tables in `configuration.md`, and refresh the build-time counts.
- Correct the TLS docs: `supported-features.md` and the FAQ still called gRPC TLS unsupported, which 0.3.0 fixed with the JDK-provider `DynamicSslContext`. They now list what the native image serves: agent gRPC TLS, receiver gRPC TLS and mTLS, REST HTTPS, OAP-to-BanyanDB TLS.

### Testing

- Add MAL comparison tests for the AI agent runtime, BanyanDB trace-sampling and gen-ai-model rules and a LAL pre-compilation test for `ai-agent.yaml`; `OapTest` feeds the new `ai_evaluation_*` families; the provider / rule inventories and the precompiled-YAML staleness baseline track the new files.
- `TALRecordedTraceComparisonTest` runs the shipped TAL rules over upstream's recorded OpenTelemetry Demo trace, plus calls the recording lacks (a slow MySQL call, a Kafka message sent and consumed, an `aws_sqs` send, a RabbitMQ settle and a Redis call to a node list) with server clusters listing the MySQL, Kafka and Redis addresses, through a fresh `TALClassGenerator` compile and through the pre-compiled classes loaded by the replacement `TalDSL`, cold and warm alias cache, and requires identical metric values, traffic rows, alias rows and slow-statement records, and that every cluster is named on the way; `TALPrecompiledRuleTest` checks the config-data coordinates and that the replacement creates exactly the metrics a fresh compile declares.
- MAL comparison tests for `mcp_endpoint` and both `process-hostmetrics` files; `ImmigrationScopeTest` also scans `tal/`; the provider / rule inventories, the replacement-source and precompiled-YAML baselines track the new and changed files; `TraceQLProvider` is marked `ACCEPTED` in the provider inventory, matching the startup wiring and `AcceptedModules`.
- `TALPrecompileTest` (precompiler) compiles operator-style TAL fixtures through the distro's pass and checks the manifests and the build failures; `TALDocScriptsTest` extracts the scripts of upstream's `tal.md` and practice pages (`otlp-trace-practices/gateway-{overview,canary,nginx}.md`, `database-and-cache.md`, `message-queue.md`), splices or substitutes each practice into a copy of the shipped rule where the page says, compiles them and the shipped rule through the same pass and runs the classes the manifest names (address-named peers, gateway peer attribute, both canary setups, nginx as a one-span proxy by name and by tag, server clusters renamed only for the system their `kinds` list, on a `ServerClusterConfig` holding the pages' own `clusters:` YAML); `TalDSLManifestTest` pins the reader side. The server TAL tests iterate every file under `tal/` and dispatch spans by layer as upstream does. Precompiler test classes run in JVMs of their own, since the build-time `MeterSystem` and Javassist pool are JVM-wide.

### E2E Tests

- New cases: `ai-agent` (AI Sessionizer files over OTLP logs, the `asz.view` document equal to the Sessionizer's own, list / filter / raw-file / metrics checks), `banyandb-trace-sampling` (a plugin-capable BanyanDB running the sampler chain), and `banyandb-auth-rotation` / `banyandb-ca-rotation` (credential and trust-CA hot reload, then the shared storage suite).
- `virtual-genai` follows upstream's split: the mock LLM and the Spring AI service run from the published `e2e-mock-llm-server` / `e2e-spring-ai-service` images, and the case also asserts the LLM-as-judge evaluation records and metrics.
- Bump `SW_CTL_COMMIT` to `1b6837da` (ai-agent commands) and `SW_E2E_SERVICE_COMMIT` to `51e72735`; add `SW_AI_SESSIONIZER_COMMIT`.
- New cases: `otlp-traces-native` (native OTLP span storage, the TraceQL `/otlp` datasource and upstream's TAL assertions: services, topology, metrics, slow statements), `otlp-virtual-genai`, `otlp-virtual-genai-native`, `zipkin-virtual-genai` and `mixed-virtual-genai` (GenAI spans from every trace source), `ios`, `miniprogram-wechat`, `miniprogram-alipay`, `vm-otel-hostmetrics` (OpenTelemetry-native host and process monitoring) and `ui-management`.
- `otlp-traces` keeps the Zipkin path (`SW_OTEL_TRACE_STORAGE: zipkin`, upstream's new default stores spans natively); `ai-agent` adds upstream's provider-body, MCP and withheld-content checks with a second, `hide`-configured OAP; `virtual-genai` asserts the evaluation storage; `kafka-monitoring` and `event` follow upstream's setup changes.
- Bump `SW_CTL_COMMIT` to `30aaacd0`, `SW_AI_SESSIONIZER_COMMIT` to `aa67971d` and `SW_E2E_SERVICE_COMMIT` to `f115aa7c`; add `OTEL_COLLECTOR_CONTRIB_VERSION`.
- New kind case `otlp-traces-cluster`: two native OAP replicas on the Kubernetes coordinator, every call's CLIENT and SERVER halves sent to different replicas, so each caller-scoped alias the topology shows was learned from a `PairingFact` that crossed to the trace's owner, in both directions.
- `alarm` also asserts the admin `/status/alarm/*` answers, and `aws-s3` posts to the AWS Firehose receiver, which no case reached: the AWS cases send their metrics over gRPC.
- New cases `server-clusters` (two Java-agent Kafka producers reporting the broker by different `;`-joined lists, and the consumer's broker, drawn as one service) and `server-clusters-otlp` (an OpenTelemetry Python confluent-kafka producer and consumer reaching the broker by two names meet at one `OTLP_VIRTUAL_MQ` broker through the pre-compiled rule), reading upstream's `server-clusters/{native,otlp}` configuration and expected files.
- `banyandb-ca-rotation` rotates the way an operator does (the OAP trusts the old and the new CA, BanyanDB reloads a certificate the new CA signed, the OAP trusts the new CA only) once the topology shows the consumer's calls, before the storage suite, and rotates to an unrelated CA last; each step writes its file once, the OAP's trust file renamed into place. It used to start with the unrelated CA, and when the writes that failed included a network address alias's first write, the alias stayed out of storage until its next refresh five minutes later and the topology assertions timed out (2 of 3 CI runs of the 456199a1f8 sync). Same change as upstream's case (apache/skywalking#14181), whose certificates and expected files it reads.

### CI

- Pin every job to `ubuntu-24.04` (and `ubuntu-24.04-arm`) as upstream does since #14166: `ubuntu-latest` is rolling to Ubuntu 26.04 during 2026-10-19 – 2026-11-19, so jobs could land on either image.
- Repin the third-party actions to the newest SHAs on the ASF allow-list (`graalvm/setup-graalvm` v1.6.8, `docker/setup-buildx-action` v4.4.1, `docker/login-action` v4.7.0, `docker/build-push-action` v7.4.0, `softprops/action-gh-release` v3), move `apache/skywalking-infra-e2e` to upstream's pin `68bbdec` (Compose v2 log collection), and the GitHub actions to their current majors (`checkout` v7, `cache` v6, `upload-artifact` v7, `download-artifact` v8, `setup-java` v6).
- A manual run with a `commit` input now checks that commit out in every job; the license check, `Build & Test` and the e2e jobs checked out the branch head, so they could test other sources than the native image they ran.


## 0.4.0

### Upstream Sync

- Sync SkyWalking submodule to the upstream **v11.0.0** release tag (`6f1fd78e87`), via `ad733554b0` and `89624809f0`.
- Wire the admin-server family on the admin host (HTTP `:17128`, admin-internal gRPC `:17129`): `admin-server`, `status` (relocated from the 10.x `status-query` plugin — `/status/*`, `/debugging/*`), `inspect` (SWIP-14 metric catalog, `/inspect/*`), `ui-management` (dashboard-template REST for the Horizon UI, `/ui-management/*`), and `dsl-debugging` (SWIP-13 DSL live debugger, see below). The `status-query` plugin is removed.
- Adopt every rule file upstream added or reworked: Airflow layer (SWIP-7, `otel-rules/airflow/*`), BanyanDB self-observability (SWIP-15: reworked `banyandb-service/instance`, new `banyandb-endpoint` and `banyandb-instance-relation` on the new `SERVICE_INSTANCE_RELATION` meter scope), Node.js and PHP runtime meters (`meter-analyzer-config/nodejs-runtime`, `php-runtime`), the Envoy TCP access-log LAL rule (`envoy-als-tcp`), iOS (SWIP-11) and mini-program (SWIP-12) OTel rules, Envoy AI Gateway MCP rules, the mini-program log-MAL rules and the `ai_route_type` searchable log tag — all enabled with the upstream defaults.
- Follow the upstream DSL refactors: rule source attribution (`DslSourceRef`; generated classes are named `{yaml}_L{line}_{rule}` exactly as the JVM distro names them), the DSL class-loading move to `core/dsl`, LAL input-type routing (Envoy HTTP vs TCP entries on the same layer — the pre-compiled LAL manifest records each rule's effective input type), and `meter-analyzer-config` loading through the shared `Rules` loader (`MeterConfig`/`MeterConfigs` are gone upstream and in the distro).
- Mirror the new `application.yml` options: the `restSSL*` TLS settings of every HTTP server (present for parity, not verified in the native image), the extended `meterAnalyzerActiveFiles` and `enabledOtelMetricsRules` defaults, and the BanyanDB trace-retention pipeline config.
- Drop the bundled UI templates: upstream 11.0.0 removed `ui-initialized-templates/` and `UITemplateInitializer`; templates are now managed via `ui-management`.

### DSL Management and Debugging

- **DSL live debugger (SWIP-13) is supported.** The precompiler flips upstream's `DSLDebugCodegenSwitch` before every OAL / MAL / LAL generator pass (the same switch the JVM OAP flips at boot), so the debug probes and gate holders are compiled into the pre-compiled rule classes; upstream's `dsl-debugging` module then runs unchanged, only flipping gates and reading samples at runtime. `/dsl-debugging/*` sessions work on every bundled rule, and `/runtime/oal/*` lists the OAL catalog.
- **Read-only rule catalogs.** A distro handler serves `GET /runtime/rule/list`, `/runtime/rule/bundled` and `GET /runtime/rule` with upstream's payloads and `X-Sw-*` headers from `META-INF/rule-source/`, the raw MAL/LAL files the precompiler exports at build time, so the Horizon UI's DSL catalog, editor and live-debug pickers list and open every bundled rule (always `BUNDLED`).
- **Runtime rule hot-update stays unsupported**: `POST /runtime/rule/addOrUpdate|inactivate|delete`, `/runtime/rule/dump`, `/runtime/mal/*` and `/runtime/lal/*` answer a structured HTTP 501 — they generate bytecode at runtime, which a closed-world native image cannot do.

### GraalVM Native Image Compatibility

- Config loading: `YamlConfigLoaderUtils` and `ConfigInitializerGenerator` emit a no-op dispatch branch for empty `ModuleConfig` types (`InspectModuleConfig`, `UIManagementModuleConfig`); `DSLDebuggingModuleConfig` and the BanyanDB `TracePipeline` / `SamplerPluginConfig` nested configs are generated.
- Port the upstream runtime-rule DSL overloads and the `DslSourceRef` signatures into the same-FQCN replacements: meter `DSL.parse(..., ClassPool, ClassLoader)`, `FilterExpression(..., ClassPool, ClassLoader)`, log `DSL.of(..., ClassPool, ClassLoader)`; `LALConfigs` gains upstream's `LAL_CATALOG` / `stampSource`; `HierarchyDefinitionService` loads rules by name from a `ruleName=FQCN` manifest.
- The precompiler and the MAL comparison tests compose expressions via the real upstream `MetricConvert.formatExp` (ANTLR `injectExpPrefix`) instead of a hand-rolled replica, fixing pre-compiled class lookup for chained expressions (`.sum` / `.rate` / `.downsampling`).
- Register protobuf descriptor **editions** classes (`com.google.protobuf.DescriptorProtos$*`, incl. `FeatureSet`) and **protoc-gen-validate** classes (`io.envoyproxy.pgv.validate.Validate$*`) for native-image reflection. protobuf-java 4.33 (pulled by the sync) reflects on these when parsing the BanyanDB measure descriptors; without the metadata, BanyanDB metrics queries failed at runtime with `Generated message class ... missing method`.
- Register the distro-only Armeria handlers (`UnsupportedAdminFeatureHandler`, `BundledRuleCatalogHandler`) for native-image reflection in `reachability-metadata.json`. Armeria builds its annotated routes by reflection, and these classes are not on the build-time precompiler's classpath, so without the entries their routes were never registered.
- Register the whole protobuf descriptor closure of the LAL input types (Envoy `HTTPAccessLogEntry` / `TCPAccessLogEntry`: 52 message, Builder and enum classes down to `Struct` / `Any` / `Timestamp`) with method access. Upstream JSON-prints these entries through protobuf's `JsonFormat`, both for the `EnvoyAccessLog` content of the `envoy-als` rules and for the DSL debug captures; its accessor table looks the generated getters up by reflection, so in the native image the content and the captures degraded to `jsonformat-failed` (`Generated message class ... missing method getCommonProperties`). The precompiler walks the descriptors at build time, and `LalInputTypeReflectionTest` guards the closure.
- Keep `FilterExpression#getLiteral()` in the same-FQCN MAL replacements: the upstream `Analyzer` reads it from the filter probe that only runs with a debug session attached, so a session on a filtered rule file (e.g. `otel-rules/oap.yaml`) killed ingestion with `NoSuchMethodError`. `FilteredRuleDebugCaptureTest` runs the upstream pipeline over the pre-compiled classes with a recorder attached.

### Documentation

- Document the admin-server family, the status relocation, the read-only rule catalogs, the live debugger and the unsupported hot-update (HTTP 501) in `distro-policy.md`, `supported-features.md` and `configuration.md` (which also gains the `admin-server` / `status` / `inspect` / `ui-management` / `dsl-debugging` sections and the REST TLS options); add the `0.4.0` → `11.0.0` row to `version-mapping.md`; refresh the build-time counts and the manifest table in `docs/internals/dsl-immigration.md`.

### Testing

- MAL comparison harness: run the fresh and pre-compiled paths back to back with a `CounterWindow` reset (upstream now keys counter windows by sample name, so both paths shared one window), and let auto-discovery feed `tagEqual` values, histogram buckets and the labels closures reference — the reworked BanyanDB rules and the new rule files are covered in auto-discovery mode.
- Add MAL comparison tests for the Airflow, BanyanDB endpoint / instance-relation, Node.js runtime, PHP runtime and mini-program log-MAL rules, LAL pre-compilation tests for the iOS MetricKit and mini-program rules, and `BundledRuleCatalogHandlerTest`; LAL tests look pre-compiled classes up by source coordinates; track every new YAML in the precompiled-YAML staleness baseline.

### E2E Tests

- Bump pinned dependency images for the 11.0.0 sync: BanyanDB `3b83e18f` (0.11 API), `skywalking-cli` `85e5afdb`, e2e java-test-service `95a296e2`, Kubernetes `da0e267`.
- Bump `skywalking-infra-e2e` to upstream's pin (`0d917694`) — the synced e2e expected-output templates use the `containsOnce` verify function, which the prior pin predated.
- Remove the `menu` e2e case (CI matrix + wrapper): upstream dropped the bundled UI in 11.0.0 (#13877), deleting `test/e2e-v2/cases/menu/`, so the distro wrapper referenced a non-existent reuse file.
- Add the `dsl-management` e2e case, driven by both raw curl and the official `swctl admin` command tree (upstream #13889): the read-only rule and OAL catalogs return upstream's payloads, and every runtime-rule mutation degrades to the structured HTTP 501 (`feature_not_available_in_graalvm_native`) rather than a crash or a confusing 404. Add the `dsl-debugging` e2e case: `/dsl-debugging/status` reports injection on, and session start / get / stop cycles run on a bundled MAL rule and a bundled LAL rule.
- Add the `dsl-debugging-oal` e2e case: upstream's OAL live-debug flow (`test/e2e-v2/cases/dsl-debugging/oal`) runs unchanged against the native image and captures real `service_relation_server_cpm` samples.
- Keep debug sessions active on the hot path in two existing cases: `so11y` attaches a MAL session to `otel-rules/oap.yaml` (file-level filter) before its metric checks and asserts the filter captures; `istio-als` enables the `persistence` ALS analysis and attaches a LAL session to `lal/envoy-als`, asserting every captured `HTTPAccessLogEntry` is rendered (no `jsonformat-failed`).
- Add the `airflow` (upstream Airflow mock: a replay sender feeds recorded OTLP metrics into the `airflow/*` rules) and `banyandb-so11y` (upstream SWIP-15 case: liaison + hot data node scraped by an OTel collector) e2e cases, both referencing the upstream case files by relative path so their expectations track upstream.

## 0.3.0

### Upstream Sync

- Sync SkyWalking submodule to upstream v10.4.0 release tag.
- Add `gen-ai-analyzer` module: GenAI provider/model metrics from virtual-gen-ai.oal.
- Add Envoy AI Gateway MAL/LAL rules and config.
- Add TraceQL config properties: `lookback`, `zipkinTracesListResultTags`, `skywalkingTracesListResultTags`.

### GraalVM Native Image Compatibility

- Add `library-server-for-graalvm`: replace `DynamicSslContext` to use `SslProvider.JDK` instead of `SslProvider.OPENSSL`, enabling gRPC TLS in native images without `netty_tcnative`.

### Documentation

- Document TLS/SSL limitation: native image lacks `netty_tcnative`, recommend service mesh for mTLS.

### E2E Tests

- Add SSL e2e test case (gRPC TLS with JDK SSL provider in native image).
- Add mTLS e2e test case (mutual TLS with client certificates).
- Add RabbitMQ, RocketMQ, ActiveMQ, Pulsar, Kafka, Redis, MongoDB, Flink monitoring e2e test cases (OTEL metrics collection).
- Add AWS DynamoDB, S3, EKS, API Gateway e2e test cases (mock sender metrics).
- Add Auth e2e test case (token-based agent-to-OAP authentication).
- Add OTLP Traces e2e test case (OpenTelemetry trace ingestion via Zipkin API).
- Add Virtual MQ e2e test case (Kafka-instrumented virtual MQ layer metrics).
- Add Kafka Exporter e2e test case (trace and log export to Kafka).
- Add Virtual GenAI e2e test case (GenAI provider/model metrics via Spring AI + Java agent).
- Add Envoy AI Gateway e2e test case (ENVOY_AI_GATEWAY layer metrics/logs via OTLP).
- Add TraceQL SkyWalking e2e test case (Tempo API with SkyWalking native trace datasource).
- Add Envoy AI Gateway MAL comparison tests (34 tests for gateway-service and gateway-instance rules).
- Add Self-Observability e2e test case (OAP Prometheus telemetry via OTEL collector).
- Add MQE e2e test case (Metrics Query Engine expression evaluation with baseline).

## 0.2.1

### Build

- Fix `version.properties` generation for source tarball builds: move antrun `copy-version-properties` to a Maven profile that only activates when `.git` exists, so pre-generated `version.properties` from `release.sh` is used in source tarball builds.

### Release Tooling

- Rewrite `release/pre-release.sh` to create a release branch (`release/v<version>`) instead of committing directly to main.
- Add `changes/changes.md` verification to `release/pre-release.sh` (requires release notes section before proceeding).

## 0.2.0

### Highlights

Upgrade to the latest Apache SkyWalking OAP server, with documentation restructure and CI/CD improvements.

### Upstream Sync

- Sync SkyWalking submodule to upstream commit `64a1795d8a`.

### Documentation

- Add user-facing docs: Quick Start, Supported Features, FAQ.
- Move internal build-time docs to `docs/internals/`.
- Update `docs/README.md` with "For Users" / "For Contributors" sections and official doc site link.
- Add Docker Hub README (`docker/DOCKERHUB_README.md`).
- Add release guide (`docs/release-guide.md`).
- Update root `README.md` with project intro, quick start, and image registry table.
- Add "Building from Apache Source Tarball" section to `docs/compiling.md`.

### CI/CD

- Push Docker images to Docker Hub (release only) in addition to GHCR.
- Docker Hub only receives `latest` and version tags — no commit SHA tags.
- Add `.asf.yaml` branch protection.
- PR-only `cancel-in-progress` to avoid cancelling release builds.

### Release Tooling

- `release/release.sh`: auto-create SVN `graalvm-distro` directory if it doesn't exist.
- `release/release.sh`: fix SHA-512 checksum files to contain only hash + filename (no local paths).
- `release/release.sh`: re-upload darwin SHA-512 to GitHub Release after GPG signing.
- `release/release.sh`: link vote email to `compiling.md` instead of `quick-start.md`.
- Generate vote email template with GPG signer info and submodule commit IDs.

### New Module

- Add TraceQL module (Tempo-compatible trace query API) with Zipkin and SkyWalking datasource support.

### Build

- Fix Armeria handler scan to detect inherited `@Get`/`@Path` annotations (precompiler).

### Testing

- Replacement class staleness detector: add auto-discovery coverage check for untracked same-FQCN replacements in `oap-libs-for-graalvm/`.

### E2E Tests

- Add PromQL e2e test case (Prometheus-compatible query API).
- Add LogQL e2e test case (Loki-compatible log query API).
- Add TraceQL e2e test case (Tempo-compatible trace query API with Zipkin datasource).
- Update BanyanDB to `e1ba421` (fixes Zipkin `minDuration` trace query).
- Bump Istio to 1.28.0.
- Add Baseline e2e test case.

## 0.1.1

### Release Tooling

- `release/full-release.sh`: end-to-end release script.

## 0.1.0

### Highlights

Apache SkyWalking GraalVM Distro is a GraalVM native image distribution of the Apache SkyWalking OAP server.
It compiles the full-featured OAP server into a single native binary (~200MB), delivering instant startup
and reduced memory footprint compared to the standard JVM distribution.

This is the initial release, built on top of Apache SkyWalking OAP server.

### Build-Time Compilation

- Build-time OAL engine: pre-compile ~1285 metrics/builder/dispatcher classes via Javassist at Maven compile time.
- Build-time MAL compiler: pre-compile ~1250 MAL expressions from 71 YAML rule files into `MalExpression` classes.
- Build-time LAL compiler: pre-compile ~10 LAL scripts from 8 YAML files into `LalExpression` classes.
- Build-time Hierarchy compiler: pre-compile ~4 hierarchy matching rules into `BiFunction` classes.
- Build-time MeterSystem: pre-generate ~1188 meter function subclasses via Javassist.
- Auto-generate `reflect-config.json` by scanning HTTP handlers, GraphQL resolvers/types, config POJOs, and DSL manifests.

### GraalVM Native Image Compatibility

- Replace Groovy runtime with pure Java: MAL DSL, LAL DSL, and Hierarchy rules all use ANTLR4 + Javassist v2 engines.
- Replace Guava `ClassPath.from()` classpath scanning with build-time manifests for annotations, dispatchers, and source receivers.
- Replace `Field.setAccessible()` reflection in config loading with Lombok `@Setter`-based property copying.
- Replace `ServiceLoader` SPI discovery with direct provider wiring in `ModuleDefine`.
- Lazy `HttpClient` initialization in `HttpAlarmCallback` (static final field breaks native image).
- Direct JDK 25 virtual thread API calls instead of reflection in `VirtualThreads`.

### Same-FQCN Replacement Classes

- `OALEngineLoaderService` — load OAL classes from manifests.
- `AnnotationScan` — read annotation manifests instead of classpath scan.
- `SourceReceiverImpl` — read dispatcher manifests instead of classpath scan.
- `MeterSystem` — load pre-generated MeterFunction classes from manifest.
- `CoreModuleConfig` — added `@Setter` at class level.
- `HierarchyDefinitionService` / `HierarchyService` — Java-backed closures instead of GroovyShell.
- `HttpAlarmCallback` — lazy HttpClient initialization.
- MAL `DSL` / `FilterExpression` — load pre-compiled expressions from per-file manifests.
- LAL `DSL` — load pre-compiled expressions from manifest.
- `ModuleDefine` — direct provider wiring without ServiceLoader.
- `VirtualThreads` — direct JDK 25 API calls.
- `YamlConfigLoaderUtils` — Lombok setters instead of reflection.
- Config-only `@Setter` additions: `AnalyzerModuleConfig`, `LogAnalyzerModuleConfig`, `EnvoyMetricReceiverConfig`, `OtelMetricReceiverConfig`, `EBPFReceiverModuleConfig`, `AWSFirehoseReceiverModuleConfig`, `CiliumFetcherConfig`, `StatusQueryConfig`, `HealthCheckerConfig`.
- Config loaders: `Rules`, `LALConfigs`, `MeterConfigs` — load from JSON manifests instead of filesystem YAML.

### Distribution and Packaging

- JVM distribution: repackaged OAP server with all replacement classes via `maven-shade-plugin`.
- Native distribution: single binary (~200MB) with config files, LICENSE, NOTICE, and third-party licenses.
- Docker image: `ghcr.io/apache/skywalking-graalvm-distro` based on `debian:bookworm-slim`.
- Multi-arch Docker images: `linux/amd64` and `linux/arm64`.
- macOS native binary: build locally via `make native-image` on macOS.

### Testing

- 73 MAL comparison tests: dual-path verification (fresh v2 compilation vs pre-compiled classes).
- LAL pre-compilation tests: verify all 8 LAL YAML files load from manifest.
- Hierarchy comparison tests: verify pre-compiled rules match fresh compilation.
- Replacement class staleness detector: SHA-256 tracking of upstream source files.
- YAML staleness detector: SHA-256 tracking of ~49 YAML rule files.

### E2E Tests

- Baseline test: BanyanDB storage with alarm webhook verification.
- Simple Java agent test: trace collection with native OAP.
- Istio ALS test: Envoy access log service integration.
- Event, menu, alarm, log, meter, trace-profiling, telegraf, zabbix, and zipkin test cases.

### Release Tooling

- `release/pre-release.sh`: bump Maven version from SNAPSHOT to release, tag, and bump to next SNAPSHOT.
- `release/release.sh`: create source tarball, build macOS native binary locally, download Linux binaries from GitHub Release, GPG sign all artifacts.

### Benchmark

- Local boot test: cold/warm startup time and idle memory comparison (JVM vs GraalVM).
- Kubernetes resource usage test: CPU and memory under sustained ~12 RPS traffic on Kind + Istio + Bookinfo.
- CPM validation: verify entry service call rate matches expected traffic.

### CI/CD

- Unified CI/release workflow: push to main, tag push, PR, and manual `workflow_dispatch` with optional commit SHA and version.
- Dual Docker registry: push to both GHCR and Docker Hub (Docker Hub on release only).
- Multi-arch Docker manifest: `linux/amd64` and `linux/arm64` via push-by-digest and `imagetools create`.
- GitHub Release page: auto-upload tarballs with SHA-512 checksums and changelog from `changes/`.
- 12 E2E test cases on CI (non-release builds).
