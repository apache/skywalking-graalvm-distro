# Configuration

The SkyWalking GraalVM Distro uses a simplified `application.yml` with a fixed set of modules and providers.
All settings are configurable via **system environment variables** at runtime, following the same
`${SW_ENV_VAR:default}` pattern as upstream SkyWalking.

For the full upstream configuration reference, see the
[SkyWalking Configuration Vocabulary](https://skywalking.apache.org/docs/main/next/en/setup/backend/configuration-vocabulary/).

## Differences from Upstream

This distro selects a fixed subset of modules/providers at build time. Modules and providers **not listed
below are not available** — they are not compiled into the distro binary.

### Removed Providers (vs upstream)

| Module | Removed Providers | Reason |
|--------|-------------------|--------|
| **cluster** | zookeeper, consul, etcd, nacos | Only `standalone` and `kubernetes` are included |
| **storage** | elasticsearch, h2, mysql, postgresql, opensearch | Only `banyandb` is included |
| **configuration** | apollo, consul, etcd, nacos, zookeeper, grpc | Only `k8s-configmap` (and `none`) are included |
| **telemetry** | none (upstream default) | Only `prometheus` is included |

All other modules (receivers, analyzers, query, alarm, exporter, etc.) retain their default providers
with the same configuration options as upstream.

### Optional Modules

Several modules are **disabled by default** (empty selector `:-`). Enable them by setting the
corresponding environment variable:

| Module | Enable Via | Default |
|--------|-----------|---------|
| `receiver-zabbix` | `SW_RECEIVER_ZABBIX=default` | disabled |
| `receiver-zipkin` | `SW_RECEIVER_ZIPKIN=default` | disabled |
| `query-zipkin` | `SW_QUERY_ZIPKIN=default` | disabled |
| `kafka-fetcher` | `SW_KAFKA_FETCHER=default` | disabled |
| `cilium-fetcher` | `SW_CILIUM_FETCHER=default` | disabled |
| `exporter` | `SW_EXPORTER=default` | disabled |

## Module Configuration Reference

Below is the complete list of modules, their fixed providers, and available settings.
Each setting shows its environment variable and default value.

---

### cluster

**Selector**: `SW_CLUSTER` (default: `standalone`)

Available providers: `standalone`, `kubernetes`

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| *(selector)* | `SW_CLUSTER` | `standalone` |

**kubernetes** provider:

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| namespace | `SW_CLUSTER_K8S_NAMESPACE` | `default` |
| labelSelector | `SW_CLUSTER_K8S_LABEL` | `app=collector,release=skywalking` |
| uidEnvName | `SW_CLUSTER_K8S_UID` | `SKYWALKING_COLLECTOR_UID` |

---

### core

**Selector**: `SW_CORE` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| role | `SW_CORE_ROLE` | `Mixed` |
| restHost | `SW_CORE_REST_HOST` | `0.0.0.0` |
| restPort | `SW_CORE_REST_PORT` | `12800` |
| restContextPath | `SW_CORE_REST_CONTEXT_PATH` | `/` |
| restIdleTimeOut | `SW_CORE_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_CORE_REST_QUEUE_SIZE` | `0` |
| httpMaxRequestHeaderSize | `SW_CORE_HTTP_MAX_REQUEST_HEADER_SIZE` | `8192` |
| gRPCHost | `SW_CORE_GRPC_HOST` | `0.0.0.0` |
| gRPCPort | `SW_CORE_GRPC_PORT` | `11800` |
| maxConcurrentCallsPerConnection | `SW_CORE_GRPC_MAX_CONCURRENT_CALL` | `0` |
| maxMessageSize | `SW_CORE_GRPC_MAX_MESSAGE_SIZE` | `52428800` |
| gRPCThreadPoolSize | `SW_CORE_GRPC_THREAD_POOL_SIZE` | `-1` |
| gRPCSslEnabled | `SW_CORE_GRPC_SSL_ENABLED` | `false` |
| gRPCSslKeyPath | `SW_CORE_GRPC_SSL_KEY_PATH` | `""` |
| gRPCSslCertChainPath | `SW_CORE_GRPC_SSL_CERT_CHAIN_PATH` | `""` |
| gRPCSslTrustedCAPath | `SW_CORE_GRPC_SSL_TRUSTED_CA_PATH` | `""` |
| downsampling | *(not overridable)* | `Hour, Day` |
| enableDataKeeperExecutor | `SW_CORE_ENABLE_DATA_KEEPER_EXECUTOR` | `true` |
| dataKeeperExecutePeriod | `SW_CORE_DATA_KEEPER_EXECUTE_PERIOD` | `5` |
| recordDataTTL | `SW_CORE_RECORD_DATA_TTL` | `3` |
| metricsDataTTL | `SW_CORE_METRICS_DATA_TTL` | `7` |
| l1FlushPeriod | `SW_CORE_L1_AGGREGATION_FLUSH_PERIOD` | `500` |
| storageSessionTimeout | `SW_CORE_STORAGE_SESSION_TIMEOUT` | `70000` |
| persistentPeriod | `SW_CORE_PERSISTENT_PERIOD` | `25` |
| topNReportPeriod | `SW_CORE_TOPN_REPORT_PERIOD` | `10` |
| activeExtraModelColumns | `SW_CORE_ACTIVE_EXTRA_MODEL_COLUMNS` | `false` |
| serviceNameMaxLength | `SW_SERVICE_NAME_MAX_LENGTH` | `70` |
| serviceCacheRefreshInterval | `SW_SERVICE_CACHE_REFRESH_INTERVAL` | `10` |
| instanceNameMaxLength | `SW_INSTANCE_NAME_MAX_LENGTH` | `70` |
| endpointNameMaxLength | `SW_ENDPOINT_NAME_MAX_LENGTH` | `150` |
| searchableTracesTags | `SW_SEARCHABLE_TAG_KEYS` | `http.method,http.status_code,rpc.status_code,db.type,db.instance,mq.queue,mq.topic,mq.broker` |
| searchableLogsTags | `SW_SEARCHABLE_LOGS_TAG_KEYS` | `level,http.status_code` |
| searchableAlarmTags | `SW_SEARCHABLE_ALARM_TAG_KEYS` | `level` |
| autocompleteTagKeysQueryMaxSize | `SW_AUTOCOMPLETE_TAG_KEYS_QUERY_MAX_SIZE` | `100` |
| autocompleteTagValuesQueryMaxSize | `SW_AUTOCOMPLETE_TAG_VALUES_QUERY_MAX_SIZE` | `100` |
| prepareThreads | `SW_CORE_PREPARE_THREADS` | `2` |
| enableEndpointNameGroupingByOpenapi | `SW_CORE_ENABLE_ENDPOINT_NAME_GROUPING_BY_OPENAPI` | `true` |
| syncPeriodHttpUriRecognitionPattern | `SW_CORE_SYNC_PERIOD_HTTP_URI_RECOGNITION_PATTERN` | `10` |
| trainingPeriodHttpUriRecognitionPattern | `SW_CORE_TRAINING_PERIOD_HTTP_URI_RECOGNITION_PATTERN` | `60` |
| maxHttpUrisNumberPerService | `SW_CORE_MAX_HTTP_URIS_NUMBER_PER_SVR` | `3000` |
| enableHierarchy | `SW_CORE_ENABLE_HIERARCHY` | `true` |
| maxHeapMemoryUsagePercent | `SW_CORE_MAX_HEAP_MEMORY_USAGE_PERCENT` | `96` |
| maxDirectMemoryUsage | `SW_CORE_MAX_DIRECT_MEMORY_USAGE` | `-1` |

---

### storage

**Selector**: `SW_STORAGE` (default: `banyandb`)

Only the `banyandb` provider is available. BanyanDB storage configuration is loaded from
`bydb.yml` and `bydb-topn.yml` at runtime. See the
[upstream BanyanDB storage documentation](https://skywalking.apache.org/docs/main/next/en/setup/backend/storages/banyandb/)
for details on those files.

---

### agent-analyzer

**Selector**: `SW_AGENT_ANALYZER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| traceSamplingPolicySettingsFile | `SW_TRACE_SAMPLING_POLICY_SETTINGS_FILE` | `trace-sampling-policy-settings.yml` |
| slowDBAccessThreshold | `SW_SLOW_DB_THRESHOLD` | `default:200,mongodb:100` |
| forceSampleErrorSegment | `SW_FORCE_SAMPLE_ERROR_SEGMENT` | `true` |
| segmentStatusAnalysisStrategy | `SW_SEGMENT_STATUS_ANALYSIS_STRATEGY` | `FROM_SPAN_STATUS` |
| noUpstreamRealAddressAgents | `SW_NO_UPSTREAM_REAL_ADDRESS` | `6000,9000` |
| meterAnalyzerActiveFiles | `SW_METER_ANALYZER_ACTIVE_FILES` | `datasource,threadpool,satellite,go-runtime,python-runtime,continuous-profiling,java-agent,go-agent,ruby-runtime` |
| slowCacheReadThreshold | `SW_SLOW_CACHE_SLOW_READ_THRESHOLD` | `default:20,redis:10` |
| slowCacheWriteThreshold | `SW_SLOW_CACHE_SLOW_WRITE_THRESHOLD` | `default:20,redis:10` |

---

### log-analyzer

**Selector**: `SW_LOG_ANALYZER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| lalFiles | `SW_LOG_LAL_FILES` | `envoy-als,mesh-dp,mysql-slowsql,pgsql-slowsql,redis-slowsql,k8s-service,nginx,default` |
| malFiles | `SW_LOG_MAL_FILES` | `nginx` |

---

### event-analyzer

**Selector**: `SW_EVENT_ANALYZER` (default: `default`)

No additional settings.

---

### receiver-sharing-server

**Selector**: `SW_RECEIVER_SHARING_SERVER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| restHost | `SW_RECEIVER_SHARING_REST_HOST` | `0.0.0.0` |
| restPort | `SW_RECEIVER_SHARING_REST_PORT` | `0` |
| restContextPath | `SW_RECEIVER_SHARING_REST_CONTEXT_PATH` | `/` |
| restIdleTimeOut | `SW_RECEIVER_SHARING_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_RECEIVER_SHARING_REST_QUEUE_SIZE` | `0` |
| httpMaxRequestHeaderSize | `SW_RECEIVER_SHARING_HTTP_MAX_REQUEST_HEADER_SIZE` | `8192` |
| gRPCHost | `SW_RECEIVER_GRPC_HOST` | `0.0.0.0` |
| gRPCPort | `SW_RECEIVER_GRPC_PORT` | `0` |
| maxConcurrentCallsPerConnection | `SW_RECEIVER_GRPC_MAX_CONCURRENT_CALL` | `0` |
| maxMessageSize | `SW_RECEIVER_GRPC_MAX_MESSAGE_SIZE` | `52428800` |
| gRPCThreadPoolSize | `SW_RECEIVER_GRPC_THREAD_POOL_SIZE` | `0` |
| gRPCSslEnabled | `SW_RECEIVER_GRPC_SSL_ENABLED` | `false` |
| gRPCSslKeyPath | `SW_RECEIVER_GRPC_SSL_KEY_PATH` | `""` |
| gRPCSslCertChainPath | `SW_RECEIVER_GRPC_SSL_CERT_CHAIN_PATH` | `""` |
| gRPCSslTrustedCAsPath | `SW_RECEIVER_GRPC_SSL_TRUSTED_CAS_PATH` | `""` |
| authentication | `SW_AUTHENTICATION` | `""` |

---

### receiver-register

**Selector**: `SW_RECEIVER_REGISTER` (default: `default`)

No additional settings.

---

### receiver-trace

**Selector**: `SW_RECEIVER_TRACE` (default: `default`)

No additional settings.

---

### receiver-jvm

**Selector**: `SW_RECEIVER_JVM` (default: `default`)

No additional settings.

---

### receiver-clr

**Selector**: `SW_RECEIVER_CLR` (default: `default`)

No additional settings.

---

### receiver-profile

**Selector**: `SW_RECEIVER_PROFILE` (default: `default`)

No additional settings.

---

### receiver-async-profiler

**Selector**: `SW_RECEIVER_ASYNC_PROFILER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| jfrMaxSize | `SW_RECEIVER_ASYNC_PROFILER_JFR_MAX_SIZE` | `31457280` |
| memoryParserEnabled | `SW_RECEIVER_ASYNC_PROFILER_MEMORY_PARSER_ENABLED` | `true` |

---

### receiver-pprof

**Selector**: `SW_RECEIVER_PPROF` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| pprofMaxSize | `SW_RECEIVER_PPROF_MAX_SIZE` | `31457280` |
| memoryParserEnabled | `SW_RECEIVER_PPROF_MEMORY_PARSER_ENABLED` | `true` |

---

### receiver-zabbix (disabled by default)

**Selector**: `SW_RECEIVER_ZABBIX` (default: disabled)

Set `SW_RECEIVER_ZABBIX=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| port | `SW_RECEIVER_ZABBIX_PORT` | `10051` |
| host | `SW_RECEIVER_ZABBIX_HOST` | `0.0.0.0` |
| activeFiles | `SW_RECEIVER_ZABBIX_ACTIVE_FILES` | `agent` |

---

### service-mesh

**Selector**: `SW_SERVICE_MESH` (default: `default`)

No additional settings.

---

### envoy-metric

**Selector**: `SW_ENVOY_METRIC` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| acceptMetricsService | `SW_ENVOY_METRIC_SERVICE` | `true` |
| enabledEnvoyMetricsRules | `SW_ENVOY_METRIC_ENABLED_RULES` | `envoy,envoy-svc-relation` |
| alsHTTPAnalysis | `SW_ENVOY_METRIC_ALS_HTTP_ANALYSIS` | `""` |
| alsTCPAnalysis | `SW_ENVOY_METRIC_ALS_TCP_ANALYSIS` | `""` |
| k8sServiceNameRule | `K8S_SERVICE_NAME_RULE` | `${pod.metadata.labels.(service.istio.io/canonical-name)}.${pod.metadata.namespace}` |
| istioServiceNameRule | `ISTIO_SERVICE_NAME_RULE` | `${serviceEntry.metadata.name}.${serviceEntry.metadata.namespace}` |
| istioServiceEntryIgnoredNamespaces | `SW_ISTIO_SERVICE_ENTRY_IGNORED_NAMESPACES` | `""` |
| gRPCHost | `SW_ALS_GRPC_HOST` | `0.0.0.0` |
| gRPCPort | `SW_ALS_GRPC_PORT` | `0` |
| maxConcurrentCallsPerConnection | `SW_ALS_GRPC_MAX_CONCURRENT_CALL` | `0` |
| maxMessageSize | `SW_ALS_GRPC_MAX_MESSAGE_SIZE` | `0` |
| gRPCThreadPoolSize | `SW_ALS_GRPC_THREAD_POOL_SIZE` | `0` |
| gRPCSslEnabled | `SW_ALS_GRPC_SSL_ENABLED` | `false` |
| gRPCSslKeyPath | `SW_ALS_GRPC_SSL_KEY_PATH` | `""` |
| gRPCSslCertChainPath | `SW_ALS_GRPC_SSL_CERT_CHAIN_PATH` | `""` |
| gRPCSslTrustedCAsPath | `SW_ALS_GRPC_SSL_TRUSTED_CAS_PATH` | `""` |

---

### kafka-fetcher (disabled by default)

**Selector**: `SW_KAFKA_FETCHER` (default: disabled)

Set `SW_KAFKA_FETCHER=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| bootstrapServers | `SW_KAFKA_FETCHER_SERVERS` | `localhost:9092` |
| namespace | `SW_NAMESPACE` | `""` |
| partitions | `SW_KAFKA_FETCHER_PARTITIONS` | `3` |
| replicationFactor | `SW_KAFKA_FETCHER_PARTITIONS_FACTOR` | `2` |
| enableNativeProtoLog | `SW_KAFKA_FETCHER_ENABLE_NATIVE_PROTO_LOG` | `true` |
| enableNativeJsonLog | `SW_KAFKA_FETCHER_ENABLE_NATIVE_JSON_LOG` | `true` |
| consumers | `SW_KAFKA_FETCHER_CONSUMERS` | `1` |
| kafkaHandlerThreadPoolSize | `SW_KAFKA_HANDLER_THREAD_POOL_SIZE` | `-1` |
| kafkaHandlerThreadPoolQueueSize | `SW_KAFKA_HANDLER_THREAD_POOL_QUEUE_SIZE` | `-1` |

---

### cilium-fetcher (disabled by default)

**Selector**: `SW_CILIUM_FETCHER` (default: disabled)

Set `SW_CILIUM_FETCHER=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| peerHost | `SW_CILIUM_FETCHER_PEER_HOST` | `hubble-peer.kube-system.svc.cluster.local` |
| peerPort | `SW_CILIUM_FETCHER_PEER_PORT` | `80` |
| fetchFailureRetrySecond | `SW_CILIUM_FETCHER_FETCH_FAILURE_RETRY_SECOND` | `10` |
| sslConnection | `SW_CILIUM_FETCHER_SSL_CONNECTION` | `false` |
| sslPrivateKeyFile | `SW_CILIUM_FETCHER_PRIVATE_KEY_FILE_PATH` | *(empty)* |
| sslCertChainFile | `SW_CILIUM_FETCHER_CERT_CHAIN_FILE_PATH` | *(empty)* |
| sslCaFile | `SW_CILIUM_FETCHER_CA_FILE_PATH` | *(empty)* |
| convertClientAsServerTraffic | `SW_CILIUM_FETCHER_CONVERT_CLIENT_AS_SERVER_TRAFFIC` | `true` |

---

### receiver-meter

**Selector**: `SW_RECEIVER_METER` (default: `default`)

No additional settings.

---

### receiver-otel

**Selector**: `SW_OTEL_RECEIVER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| enabledHandlers | `SW_OTEL_RECEIVER_ENABLED_HANDLERS` | `otlp-metrics,otlp-logs` |
| enabledOtelMetricsRules | `SW_OTEL_RECEIVER_ENABLED_OTEL_METRICS_RULES` | `apisix,nginx/*,k8s/*,istio-controlplane,vm,mysql/*,postgresql/*,oap,aws-eks/*,windows,aws-s3/*,aws-dynamodb/*,aws-gateway/*,redis/*,elasticsearch/*,rabbitmq/*,mongodb/*,kafka/*,pulsar/*,bookkeeper/*,rocketmq/*,clickhouse/*,activemq/*,kong/*,flink/*,banyandb/*` |

---

### receiver-zipkin (disabled by default)

**Selector**: `SW_RECEIVER_ZIPKIN` (default: disabled)

Set `SW_RECEIVER_ZIPKIN=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| searchableTracesTags | `SW_ZIPKIN_SEARCHABLE_TAG_KEYS` | `http.method` |
| sampleRate | `SW_ZIPKIN_SAMPLE_RATE` | `10000` |
| maxSpansPerSecond | `SW_ZIPKIN_MAX_SPANS_PER_SECOND` | `0` |
| enableHttpCollector | `SW_ZIPKIN_HTTP_COLLECTOR_ENABLED` | `true` |
| restHost | `SW_RECEIVER_ZIPKIN_REST_HOST` | `0.0.0.0` |
| restPort | `SW_RECEIVER_ZIPKIN_REST_PORT` | `9411` |
| restContextPath | `SW_RECEIVER_ZIPKIN_REST_CONTEXT_PATH` | `/` |
| restIdleTimeOut | `SW_RECEIVER_ZIPKIN_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_RECEIVER_ZIPKIN_REST_QUEUE_SIZE` | `0` |
| enableKafkaCollector | `SW_ZIPKIN_KAFKA_COLLECTOR_ENABLED` | `false` |
| kafkaBootstrapServers | `SW_ZIPKIN_KAFKA_SERVERS` | `localhost:9092` |
| kafkaGroupId | `SW_ZIPKIN_KAFKA_GROUP_ID` | `zipkin` |
| kafkaTopic | `SW_ZIPKIN_KAFKA_TOPIC` | `zipkin` |
| kafkaConsumerConfig | `SW_ZIPKIN_KAFKA_CONSUMER_CONFIG` | `{"auto.offset.reset":"earliest","enable.auto.commit":true}` |
| kafkaConsumers | `SW_ZIPKIN_KAFKA_CONSUMERS` | `1` |
| kafkaHandlerThreadPoolSize | `SW_ZIPKIN_KAFKA_HANDLER_THREAD_POOL_SIZE` | `-1` |
| kafkaHandlerThreadPoolQueueSize | `SW_ZIPKIN_KAFKA_HANDLER_THREAD_POOL_QUEUE_SIZE` | `-1` |

---

### receiver-browser

**Selector**: `SW_RECEIVER_BROWSER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| sampleRate | `SW_RECEIVER_BROWSER_SAMPLE_RATE` | `10000` |

---

### receiver-log

**Selector**: `SW_RECEIVER_LOG` (default: `default`)

No additional settings.

---

### query

**Selector**: `SW_QUERY` (default: `graphql`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| enableLogTestTool | `SW_QUERY_GRAPHQL_ENABLE_LOG_TEST_TOOL` | `false` |
| maxQueryComplexity | `SW_QUERY_MAX_QUERY_COMPLEXITY` | `3000` |
| enableUpdateUITemplate | `SW_ENABLE_UPDATE_UI_TEMPLATE` | `false` |
| enableOnDemandPodLog | `SW_ENABLE_ON_DEMAND_POD_LOG` | `false` |

---

### query-zipkin (disabled by default)

**Selector**: `SW_QUERY_ZIPKIN` (default: disabled)

Set `SW_QUERY_ZIPKIN=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| restHost | `SW_QUERY_ZIPKIN_REST_HOST` | `0.0.0.0` |
| restPort | `SW_QUERY_ZIPKIN_REST_PORT` | `9412` |
| restContextPath | `SW_QUERY_ZIPKIN_REST_CONTEXT_PATH` | `/zipkin` |
| restIdleTimeOut | `SW_QUERY_ZIPKIN_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_QUERY_ZIPKIN_REST_QUEUE_SIZE` | `0` |
| lookback | `SW_QUERY_ZIPKIN_LOOKBACK` | `86400000` |
| namesMaxAge | `SW_QUERY_ZIPKIN_NAMES_MAX_AGE` | `300` |
| uiQueryLimit | `SW_QUERY_ZIPKIN_UI_QUERY_LIMIT` | `10` |
| uiDefaultLookback | `SW_QUERY_ZIPKIN_UI_DEFAULT_LOOKBACK` | `900000` |

---

### promql

**Selector**: `SW_PROMQL` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| restHost | `SW_PROMQL_REST_HOST` | `0.0.0.0` |
| restPort | `SW_PROMQL_REST_PORT` | `9090` |
| restContextPath | `SW_PROMQL_REST_CONTEXT_PATH` | `/` |
| restIdleTimeOut | `SW_PROMQL_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_PROMQL_REST_QUEUE_SIZE` | `0` |
| buildInfoVersion | `SW_PROMQL_BUILD_INFO_VERSION` | `2.45.0` |
| buildInfoRevision | `SW_PROMQL_BUILD_INFO_REVISION` | `""` |
| buildInfoBranch | `SW_PROMQL_BUILD_INFO_BRANCH` | `""` |
| buildInfoBuildUser | `SW_PROMQL_BUILD_INFO_BUILD_USER` | `""` |
| buildInfoBuildDate | `SW_PROMQL_BUILD_INFO_BUILD_DATE` | `""` |
| buildInfoGoVersion | `SW_PROMQL_BUILD_INFO_GO_VERSION` | `""` |

---

### logql

**Selector**: `SW_LOGQL` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| restHost | `SW_LOGQL_REST_HOST` | `0.0.0.0` |
| restPort | `SW_LOGQL_REST_PORT` | `3100` |
| restContextPath | `SW_LOGQL_REST_CONTEXT_PATH` | `/` |
| restIdleTimeOut | `SW_LOGQL_REST_IDLE_TIMEOUT` | `30000` |
| restAcceptQueueSize | `SW_LOGQL_REST_QUEUE_SIZE` | `0` |

---

### alarm

**Selector**: `SW_ALARM` (default: `default`)

No additional settings in `application.yml`. Alarm rules are configured via `alarm-settings.yml`.

---

### telemetry

**Selector**: `SW_TELEMETRY` (default: `prometheus`)

Only the `prometheus` provider is available.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| host | `SW_TELEMETRY_PROMETHEUS_HOST` | `0.0.0.0` |
| port | `SW_TELEMETRY_PROMETHEUS_PORT` | `1234` |
| sslEnabled | `SW_TELEMETRY_PROMETHEUS_SSL_ENABLED` | `false` |
| sslKeyPath | `SW_TELEMETRY_PROMETHEUS_SSL_KEY_PATH` | `""` |
| sslCertChainPath | `SW_TELEMETRY_PROMETHEUS_SSL_CERT_CHAIN_PATH` | `""` |

---

### configuration

**Selector**: `SW_CONFIGURATION` (default: `k8s-configmap`)

Available providers: `none`, `k8s-configmap`

**k8s-configmap** provider:

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| period | `SW_CONFIG_CONFIGMAP_PERIOD` | `60` |
| namespace | `SW_CLUSTER_K8S_NAMESPACE` | `default` |
| labelSelector | `SW_CLUSTER_K8S_LABEL` | `app=collector,release=skywalking` |

---

### exporter (disabled by default)

**Selector**: `SW_EXPORTER` (default: disabled)

Set `SW_EXPORTER=default` to enable.

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| enableGRPCMetrics | `SW_EXPORTER_ENABLE_GRPC_METRICS` | `false` |
| gRPCTargetHost | `SW_EXPORTER_GRPC_HOST` | `127.0.0.1` |
| gRPCTargetPort | `SW_EXPORTER_GRPC_PORT` | `9870` |
| enableKafkaTrace | `SW_EXPORTER_ENABLE_KAFKA_TRACE` | `false` |
| enableKafkaLog | `SW_EXPORTER_ENABLE_KAFKA_LOG` | `false` |
| kafkaBootstrapServers | `SW_EXPORTER_KAFKA_SERVERS` | `localhost:9092` |
| kafkaProducerConfig | `SW_EXPORTER_KAFKA_PRODUCER_CONFIG` | `""` |
| kafkaTopicTrace | `SW_EXPORTER_KAFKA_TOPIC_TRACE` | `skywalking-export-trace` |
| kafkaTopicLog | `SW_EXPORTER_KAFKA_TOPIC_LOG` | `skywalking-export-log` |
| exportErrorStatusTraceOnly | `SW_EXPORTER_KAFKA_TRACE_FILTER_ERROR` | `false` |

---

### health-checker

**Selector**: `SW_HEALTH_CHECKER` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| checkIntervalSeconds | `SW_HEALTH_CHECKER_INTERVAL_SECONDS` | `30` |

---

### status-query

**Selector**: `SW_STATUS_QUERY` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| keywords4MaskingSecretsOfConfig | `SW_DEBUGGING_QUERY_KEYWORDS_FOR_MASKING_SECRETS` | `user,password,token,accessKey,secretKey,authentication` |

---

### configuration-discovery

**Selector**: `SW_CONFIGURATION_DISCOVERY` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| disableMessageDigest | `SW_DISABLE_MESSAGE_DIGEST` | `false` |

---

### receiver-event

**Selector**: `SW_RECEIVER_EVENT` (default: `default`)

No additional settings.

---

### receiver-ebpf

**Selector**: `SW_RECEIVER_EBPF` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| continuousPolicyCacheTimeout | `SW_CONTINUOUS_POLICY_CACHE_TIMEOUT` | `60` |
| gRPCHost | `SW_EBPF_GRPC_HOST` | `0.0.0.0` |
| gRPCPort | `SW_EBPF_GRPC_PORT` | `0` |
| maxConcurrentCallsPerConnection | `SW_EBPF_GRPC_MAX_CONCURRENT_CALL` | `0` |
| maxMessageSize | `SW_EBPF_ALS_GRPC_MAX_MESSAGE_SIZE` | `0` |
| gRPCThreadPoolSize | `SW_EBPF_GRPC_THREAD_POOL_SIZE` | `0` |
| gRPCSslEnabled | `SW_EBPF_GRPC_SSL_ENABLED` | `false` |
| gRPCSslKeyPath | `SW_EBPF_GRPC_SSL_KEY_PATH` | `""` |
| gRPCSslCertChainPath | `SW_EBPF_GRPC_SSL_CERT_CHAIN_PATH` | `""` |
| gRPCSslTrustedCAsPath | `SW_EBPF_GRPC_SSL_TRUSTED_CAS_PATH` | `""` |

---

### receiver-telegraf

**Selector**: `SW_RECEIVER_TELEGRAF` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| activeFiles | `SW_RECEIVER_TELEGRAF_ACTIVE_FILES` | `vm` |

---

### aws-firehose

**Selector**: `SW_RECEIVER_AWS_FIREHOSE` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| host | `SW_RECEIVER_AWS_FIREHOSE_HTTP_HOST` | `0.0.0.0` |
| port | `SW_RECEIVER_AWS_FIREHOSE_HTTP_PORT` | `12801` |
| contextPath | `SW_RECEIVER_AWS_FIREHOSE_HTTP_CONTEXT_PATH` | `/` |
| idleTimeOut | `SW_RECEIVER_AWS_FIREHOSE_HTTP_IDLE_TIME_OUT` | `30000` |
| acceptQueueSize | `SW_RECEIVER_AWS_FIREHOSE_HTTP_ACCEPT_QUEUE_SIZE` | `0` |
| maxRequestHeaderSize | `SW_RECEIVER_AWS_FIREHOSE_HTTP_MAX_REQUEST_HEADER_SIZE` | `8192` |
| firehoseAccessKey | `SW_RECEIVER_AWS_FIREHOSE_ACCESS_KEY` | *(empty)* |
| enableTLS | `SW_RECEIVER_AWS_FIREHOSE_HTTP_ENABLE_TLS` | `false` |
| tlsKeyPath | `SW_RECEIVER_AWS_FIREHOSE_HTTP_TLS_KEY_PATH` | *(empty)* |
| tlsCertChainPath | `SW_RECEIVER_AWS_FIREHOSE_HTTP_TLS_CERT_CHAIN_PATH` | *(empty)* |

---

### ai-pipeline

**Selector**: `SW_AI_PIPELINE` (default: `default`)

| Setting | Environment Variable | Default |
|---------|---------------------|---------|
| uriRecognitionServerAddr | `SW_AI_PIPELINE_URI_RECOGNITION_SERVER_ADDR` | *(empty)* |
| uriRecognitionServerPort | `SW_AI_PIPELINE_URI_RECOGNITION_SERVER_PORT` | `17128` |
| baselineServerAddr | `SW_API_PIPELINE_BASELINE_SERVICE_HOST` | *(empty)* |
| baselineServerPort | `SW_API_PIPELINE_BASELINE_SERVICE_PORT` | `18080` |
