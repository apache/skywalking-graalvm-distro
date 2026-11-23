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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.oap.log.analyzer.v2.dsl.ExecutionContext;
import org.apache.skywalking.oap.log.analyzer.v2.compiler.LALCallables;
import org.apache.skywalking.oap.server.core.source.LogMetadata;
import org.apache.skywalking.oap.server.core.dsl.DslCallables;
import org.apache.skywalking.oap.server.core.dsl.debug.ToJson;

import static org.apache.skywalking.oap.server.buildtools.common.ProtobufMessages.generatedMessage;
import static org.apache.skywalking.oap.server.buildtools.precompiler.GenericTypes.typeBindings;
import static org.apache.skywalking.oap.server.buildtools.precompiler.GenericTypes.valueTypes;

/**
 * Generates the native-image metadata: reflect-config.json from the manifests the passes and the class path scans
 * wrote, and resource-config.json's fixed resource patterns.
 */
@Slf4j
final class NativeImageMetadata {
    /**
     * Generate GraalVM native-image metadata (reflect-config.json and resource-config.json)
     * from the manifests already produced by the precompiler.
     *
     * This gives native-image 100% coverage of all pre-compiled classes — unlike the tracing
     * agent which only captures code paths exercised during a traced run.
     */
    static void generateNativeImageConfig(String outputDir, List<String> scannedClassManifests)
        throws IOException {
        Path nativeImageDir = Path.of(outputDir,
            "META-INF", "native-image", "org.apache.skywalking", "oap-graalvm-distro");
        Files.createDirectories(nativeImageDir);

        generateReflectConfig(outputDir, nativeImageDir, scannedClassManifests);
        generateResourceConfig(nativeImageDir);
    }

    /**
     * Generate reflect-config.json from all manifest files.
     *
     * Annotation-scanned classes get full reflection access (fields, methods, constructors)
     * because AnnotationScan, StreamAnnotationListener, etc. inspect annotations and fields.
     *
     * Script/metric/dispatcher classes get constructor-only access since they are loaded
     * via Class.forName() + getDeclaredConstructor().newInstance().
     */
    private static void generateReflectConfig(String outputDir, Path nativeImageDir,
                                              List<String> scannedClassManifests) throws IOException {
        Path metaInf = Path.of(outputDir, "META-INF");
        Path annotationScanDir = metaInf.resolve("annotation-scan");

        List<Map<String, Object>> entries = new ArrayList<>();

        // Annotation-scanned classes — full reflection access
        for (String manifest : scannedClassManifests) {
            for (String className : readClassNames(annotationScanDir.resolve(manifest))) {
                entries.add(fullAccessEntry(className));
            }
        }

        // Full access: Armeria reflects on the handlers' route annotations, graphql-java-tools maps the schema onto the
        // resolvers and types, Jackson and Gson read the query entities
        String[] fullAccessManifests = {
            "ArmeriaHandlers.txt", "GraphQLResolvers.txt", "GraphQLTypes.txt", "QueryEntityClasses.txt"
        };
        for (String manifest : fullAccessManifests) {
            for (String className : readClassNames(annotationScanDir.resolve(manifest))) {
                entries.add(fullAccessEntry(className));
            }
        }

        // Classes reflected on at runtime that no scan finds, each with its reason — full access
        String[] configPojos = {
            // Zabbix protocol: Gson serializes ActiveChecks response to agent
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.protocol.bean.ZabbixResponse$ActiveChecks",
            // Zabbix protocol: Gson deserializes AgentData from agent push
            "org.apache.skywalking.oap.server.receiver.zabbix.provider.protocol.bean.ZabbixRequest$AgentData",
            // Meter base class: attr0-attr5 fields must be discoverable via getDeclaredFields()
            // for StorageModels.retrieval() to include them in BanyanDB schemas
            "org.apache.skywalking.oap.server.core.analysis.meter.Meter",
            // GraphQL query types deserialized at runtime
            "org.apache.skywalking.oap.server.core.query.type.event.Source",
            // Searchable tag POJO used in alarm/log query results
            "org.apache.skywalking.oap.server.core.analysis.manual.searchtag.Tag",
            // Alarm snapshot: Gson serializes/deserializes at runtime
            "org.apache.skywalking.oap.server.core.alarm.AlarmSnapshotRecord",
            // Alarm webhook: Gson serializes AlarmMessage list to JSON for webhook POST
            "org.apache.skywalking.oap.server.core.alarm.AlarmMessage",
            // Alarm recovery: extends AlarmMessage with recoveryTime field, serialized by Gson
            "org.apache.skywalking.oap.server.core.alarm.AlarmRecoveryMessage",
            // Cluster status REST endpoint: Jackson serializes the node Addresses /status/cluster/nodes returns
            "org.apache.skywalking.oap.server.core.remote.client.Address",
            // AWS Firehose receiver: Jackson writes the response the route builds
            "org.apache.skywalking.oap.server.receiver.aws.firehose.FirehoseRes",
            // UI template REST (/ui-management/templates): Jackson writes the answers the routes build
            "org.apache.skywalking.oap.server.core.query.type.DashboardConfiguration",
            "org.apache.skywalking.oap.server.core.query.type.TemplateChangeStatus"
        };
        for (String className : configPojos) {
            entries.add(fullAccessEntry(className));
        }

        // ModuleConfig subclasses from all accepted providers — full access.
        // Read from manifest generated by config-generator (see build-tools/config-generator).
        // ModuleDefine.prepare() calls type.getDeclaredConstructor().newInstance() to create config beans,
        // and YamlConfigLoaderUtils.copyProperties() populates them via setters/getters.
        try (InputStream configManifest =
                 NativeImageMetadata.class.getResourceAsStream("/META-INF/module-config-classes.txt")) {
            if (configManifest == null) {
                throw new IllegalStateException("META-INF/module-config-classes.txt not found on classpath. "
                    + "Run config-generator first to produce it.");
            }
            List<String> moduleConfigClasses = new BufferedReader(new InputStreamReader(configManifest, StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
            log.info("Loaded {} ModuleConfig classes from manifest", moduleConfigClasses.size());
            for (String className : moduleConfigClasses) {
                entries.add(fullAccessEntry(className));
            }
        }
        try (InputStream setByField =
                 NativeImageMetadata.class.getResourceAsStream("/META-INF/module-config-field-set.txt")) {
            if (setByField == null) {
                throw new IllegalStateException("META-INF/module-config-field-set.txt not found on classpath. "
                    + "Run config-generator first to produce it.");
            }
            entries.addAll(configFieldSetEntries(
                new BufferedReader(new InputStreamReader(setByField, StandardCharsets.UTF_8)).lines()
                    .map(String::trim).filter(line -> !line.isEmpty()).toList()));
        }

        // MeterFunction manifest — key=value format, full access (MeterSystem inspects annotations)
        for (String className : readValueFromKeyValue(annotationScanDir.resolve("MeterFunction.txt"))) {
            entries.add(fullAccessEntry(className));
        }

        // StorageBuilder classes — constructor-only (instantiated via getDeclaredConstructor().newInstance())
        addConstructorEntries(entries, annotationScanDir.resolve("StorageBuilders.txt"));
        // Stream data RemoteHandleWorker rebuilds — constructor-only
        addConstructorEntries(entries, annotationScanDir.resolve("StreamData.txt"));

        // OAL metrics, their storage builders and dispatchers — constructor-only. The builders are generated here
        // too, so a rules overlay's OAL script gets them.
        addConstructorEntries(entries, metaInf.resolve("oal-metrics-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-builder-classes.txt"));
        addConstructorEntries(entries, metaInf.resolve("oal-dispatcher-classes.txt"));

        // MAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("mal-v2-classes.txt"));

        // LAL v2 expression classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("lal-v2-classes.txt"));

        // TAL v2 expression classes: the constructor, plus the function-object fields the runtime TalDSL sets
        addTalExpressionEntries(entries, metaInf.resolve("tal-v2-classes.txt"), metaInf.resolve("tal-v2-rules.txt"));
        // The input types, providers and function objects the TAL rules were resolved through: ServiceLoader
        // instantiates the SPI ones, TALFunctionRegistry.bind lists the methods of every bound object (getMethods on
        // the providers), and toJson() in a rule serializes whatever object it is given through Gson
        for (String className : readClassNames(metaInf.resolve("tal-io-classes.txt"))) {
            Map<String, Object> entry = fullAccessEntry(className);
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        // The TAL roots `pairing` and `meta` of every rule, which toJson() hands to Gson's reflective serializer
        entries.add(fullAccessEntry("org.apache.skywalking.oap.trace.analyzer.v2.provider.span.PairingFact"));
        entries.add(fullAccessEntry("org.apache.skywalking.oap.server.core.trace.SpanMetadata"));
        // A protobuf input (a rule's `span`, an event) handed to toJson() is walked by Gson field by field: the message
        // classes themselves are in the descriptor closure below, the runtime classes holding their state are these.
        // Gson only reads fields, so their entries grant field access alone.
        for (String className : GSON_PROTOBUF_RUNTIME_CLASSES) {
            addFieldsWithNestedClasses(entries, className);
        }

        // Proto messages that go through protobuf's JsonFormat, whose accessor table finds the
        // generated getters and setters by reflection on the message, its Builder and every nested
        // message and enum type. Register the whole descriptor closure with full access. Roots: the
        // OTLP/HTTP JSON receivers' export requests, LogData parsed by ProtoBufJsonUtils, the TAL input
        // types, and the messages among the LAL values (addLalEntries; the LAL debug dump and
        // EnvoyAccessLogBuilder print the inputs).
        Set<String> protoJsonRoots = new TreeSet<>(List.of(
            "io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest",
            "io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest",
            "io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest",
            // Printed by the OTLP/HTTP JSON trace receiver and the TraceQL /otlp trace-by-id route.
            "io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse",
            "io.grafana.tempo.tempopb.TraceByIDResponse",
            "org.apache.skywalking.apm.network.logging.v3.LogData"));
        // The protobuf bodies of the HTTP routes, which Armeria converts through JsonFormat; the other bodies with full
        // access
        for (String className : readClassNames(annotationScanDir.resolve("HttpBodyTypes.txt"))) {
            Class<?> message = generatedMessage(ClasspathScans.loadClass(className));
            if (message != null) {
                protoJsonRoots.add(message.getName());
            } else {
                entries.add(fullAccessEntry(className));
            }
        }
        Properties talRules = loadProperties(metaInf.resolve("tal-v2-rules.txt"));
        for (String key : talRules.stringPropertyNames()) {
            if (key.matches("rule\\.\\d+\\.inputType") && !talRules.getProperty(key).isBlank()) {
                protoJsonRoots.add(talRules.getProperty(key).trim());
            }
        }
        addLalEntries(entries, metaInf, protoJsonRoots);
        addProtoMessageEntries(entries, protoJsonRoots);

        // Hierarchy v2 rule classes — one FQCN per line, constructor-only
        addConstructorEntries(entries, metaInf.resolve("hierarchy-v2-classes.txt"));

        // MAL and TAL meter classes — key=value format, constructor-only
        for (String meterManifest : new String[] {"mal-meter-classes.txt", "tal-meter-classes.txt"}) {
            for (String className : readValueFromKeyValue(metaInf.resolve(meterManifest))) {
                entries.add(constructorOnlyEntry(className));
            }
        }

        // Write reflect-config.json
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(nativeImageDir.resolve("reflect-config.json").toFile(), entries);
        log.info("Generated reflect-config.json with {} entries", entries.size());
    }

    /**
     * Generate resource-config.json for all META-INF resources in the generated JAR.
     */
    private static void generateResourceConfig(Path nativeImageDir) throws IOException {
        Map<String, Object> resourceConfig = new LinkedHashMap<>();
        Map<String, Object> resources = new LinkedHashMap<>();
        List<Map<String, String>> includes = new ArrayList<>();

        // Rule data is code (the generated registry); these are the scans and the rule text the catalogs display.
        includes.add(Map.of("pattern", "META-INF/annotation-scan/.*\\.txt"));
        includes.add(Map.of("pattern", "META-INF/rule-source/.*"));
        includes.add(Map.of("pattern", "oal/.*\\.oal"));

        resources.put("includes", includes);
        resourceConfig.put("resources", resources);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(nativeImageDir.resolve("resource-config.json").toFile(), resourceConfig);
        log.info("Generated resource-config.json with {} resource patterns", includes.size());
    }

    /**
     * Register every message and enum type reachable from {@code rootTypes} through the proto
     * descriptors, plus their {@code Builder} classes, with full access. A non-message root gets a
     * name-only entry.
     */
    private static void addProtoMessageEntries(List<Map<String, Object>> entries, Set<String> rootTypes) {
        Set<Class<?>> closure = new LinkedHashSet<>();
        for (String rootType : rootTypes) {
            Class<?> root;
            try {
                root = Class.forName(rootType);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Proto JSON root type is not on the precompiler classpath: " + rootType, e);
            }
            Class<?> generated = generatedMessage(root);
            if (generated != null) {
                collectProtoClosure(generated, closure);
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", rootType);
                entries.add(entry);
            }
        }
        for (Class<?> clazz : closure) {
            entries.add(fullAccessEntry(clazz.getName()));
        }
        log.info("Registered {} protobuf classes reachable from {} root types", closure.size(), rootTypes.size());
    }

    /**
     * What the LAL runtime reflects on. The SPI implementations ServiceLoader instantiates; the output type upstream's
     * boot resolves a rule's {@code outputType} to ({@code Class.forName} when the rule names a class); and the values
     * {@code LalRuntimeHelper} reads a property of when the rule's compiler could not type it ({@code parsed.<key>} no
     * parser set, read from the input, or {@code value.<name>}): it lists the value's public methods, checks the
     * getter against {@link LALCallables} and calls it. Those values are walked from every type a rule reads values
     * from ({@link #collectLalValues}); the generated messages among them join {@code protoRoots}.
     */
    private static void addLalEntries(List<Map<String, Object>> entries, Path metaInf, Set<String> protoRoots)
        throws IOException {
        for (String className : readClassNames(metaInf.resolve("lal-io-classes.txt"))) {
            entries.add(fullAccessEntry(className));
        }
        Properties rules = loadProperties(metaInf.resolve("lal-v2-rules.txt"));
        for (String key : rules.stringPropertyNames()) {
            if (key.matches("rule\\.\\d+\\.requestedOutputType") && !rules.getProperty(key).isBlank()) {
                entries.add(constructorOnlyEntry(rules.getProperty(key).trim()));
            }
        }
        Set<Class<?>> values = new LinkedHashSet<>();
        // The values every rule can read whatever its input: the log's own (log.*, a parser-less parsed.*) and the
        // parsed map; a value read from them through a call the compiler cannot type (a map's getOrDefault falling
        // back to log.traceContext) is reflected on at run time like an input's
        for (Class<?> builtin : LAL_BUILTIN_VALUES) {
            collectLalValues(builtin, protoRoots, values);
        }
        for (String className : readClassNames(metaInf.resolve("lal-input-types.txt"))) {
            try {
                collectLalValues(Class.forName(className), protoRoots, values);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("LAL input type is not on the precompiler classpath: " + className, e);
            }
        }
        for (Class<?> type : values) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        addLalCallablesEntries(entries);
        log.info("Registered {} LAL value classes besides the protobuf ones", values.size());
    }

    /**
     * The values LAL gives every rule, whatever its input: the log's metadata ({@code log.*}, and {@code parsed.*}
     * without a parser), its {@code LogData}, and the parsed map's holder.
     */
    static final List<Class<?>> LAL_BUILTIN_VALUES =
        List.of(LogMetadata.class, LogData.class, ExecutionContext.Parsed.class);

    /**
     * The types {@code LalRuntimeHelper} reflects on when a value is of {@code type}. A generated message joins
     * {@code protoRoots}: its descriptor closure holds every message its getters return. Another type is registered
     * with its public methods, and so is every public type of its hierarchy declaring a getter {@link LALCallables}
     * lets a rule call (the runtime calls a getter of a non-public class through one); a getter's return type, or an
     * element type of it, that is a protobuf message, a {@link ToJson}, or another type of this project whose getters
     * {@link LALCallables} lets a rule call ({@code LogMetadata.TraceContext}) is walked in turn.
     */
    static void collectLalValues(Class<?> type, Set<String> protoRoots, Set<Class<?>> registered) {
        Class<?> message = generatedMessage(type);
        if (message != null) {
            protoRoots.add(message.getName());
            return;
        }
        if (!registered.add(type)) {
            return;
        }
        DslCallables callables = LALCallables.get();
        for (Method getter : type.getMethods()) {
            if (getter.getParameterCount() != 0 || !isPropertyGetter(getter.getName())
                || !callables.allows(type, getter)) {
                continue;
            }
            registered.addAll(publicDeclarers(type, getter.getName()));
            for (Class<?> value : valueTypes(getter.getGenericReturnType(), typeBindings(type))) {
                if (MessageOrBuilder.class.isAssignableFrom(value) || ToJson.class.isAssignableFrom(value)
                    || hasCallableGetter(value, callables)) {
                    collectLalValues(value, protoRoots, registered);
                }
            }
        }
    }

    /**
     * @return whether the type is one of this project's whose getters a rule may call, read by reflection when a value
     *     of it reaches a rule untyped
     */
    private static boolean hasCallableGetter(Class<?> type, DslCallables callables) {
        return type.getName().startsWith("org.apache.skywalking.") && Arrays.stream(type.getMethods())
            .anyMatch(m -> m.getParameterCount() == 0 && isPropertyGetter(m.getName()) && callables.allows(type, m));
    }

    /** {@code getX()} or {@code isX()}: what {@code LalRuntimeHelper} looks a property {@code x} up as. */
    private static boolean isPropertyGetter(String name) {
        return name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))
            || name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static Set<Class<?>> publicDeclarers(Class<?> type, String getter) {
        Set<Class<?>> declarers = new LinkedHashSet<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (!seen.add(c)) {
                continue;
            }
            if (Modifier.isPublic(c.getModifiers()) && Arrays.stream(c.getDeclaredMethods()).anyMatch(
                m -> m.getName().equals(getter) && m.getParameterCount() == 0 && Modifier.isPublic(m.getModifiers())
                    && !Modifier.isStatic(m.getModifiers()))) {
                declarers.add(c);
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(Arrays.asList(c.getInterfaces()));
        }
        return declarers;
    }

    /**
     * {@link LALCallables} is first built in the image, when a getter read at run time is checked: building it reads
     * every listed class's public methods (and {@code Object}'s, for an interface) and static fields.
     */
    private static void addLalCallablesEntries(List<Map<String, Object>> entries) {
        DslCallables callables = LALCallables.get();
        Set<Class<?>> listed = new LinkedHashSet<>();
        listed.add(Object.class);
        listed.addAll(callables.declaredMethods().keySet());
        listed.addAll(callables.declaredStatics().keySet());
        for (Class<?> type : listed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("allPublicMethods", true);
            entries.add(entry);
        }
        callables.declaredStaticFields().forEach((type, names) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", type.getName());
            entry.put("fields", names.stream().map(n -> Map.of("name", n)).collect(Collectors.toList()));
            entries.add(entry);
        });
        log.info("Registered the public methods of {} classes LALCallables lists", listed.size());
    }

    private static void collectProtoClosure(Class<?> messageClass, Set<Class<?>> closure) {
        if (!closure.add(messageClass)) {
            return;
        }
        for (Class<?> nested : messageClass.getDeclaredClasses()) {
            if (Message.Builder.class.isAssignableFrom(nested)) {
                closure.add(nested);
            }
        }
        Descriptors.Descriptor descriptor;
        try {
            descriptor = (Descriptors.Descriptor) messageClass.getMethod("getDescriptor").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Not a generated protobuf message: " + messageClass.getName(), e);
        }
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            Descriptors.FieldDescriptor.JavaType javaType = field.getJavaType();
            if (javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE
                && javaType != Descriptors.FieldDescriptor.JavaType.ENUM) {
                continue;
            }
            Class<?> fieldType = field.isMapField()
                ? mapValueType(messageClass, field)
                : accessorReturnType(messageClass, field);
            if (fieldType == null) {
                continue; // map with scalar values: nothing generated to register
            }
            if (Message.class.isAssignableFrom(fieldType)) {
                collectProtoClosure(fieldType, closure);
            } else if (fieldType.isEnum()) {
                closure.add(fieldType);
            }
        }
    }

    /** {@code getFoo()} for singular fields, {@code getFoo(int)} for repeated ones. */
    private static Class<?> accessorReturnType(Class<?> messageClass, Descriptors.FieldDescriptor field) {
        return findGetter(messageClass, "get" + field.getName(), field.isRepeated() ? 1 : 0).getReturnType();
    }

    /** The value type argument of {@code Map<K, V> getFooMap()}; {@code null} for scalar values. */
    private static Class<?> mapValueType(Class<?> messageClass, Descriptors.FieldDescriptor field) {
        Type type = findGetter(messageClass, "get" + field.getName() + "Map", 0).getGenericReturnType();
        if (type instanceof ParameterizedType) {
            Type value = ((ParameterizedType) type).getActualTypeArguments()[1];
            if (value instanceof Class) {
                return (Class<?>) value;
            }
        }
        return null;
    }

    /**
     * protobuf-java derives getter names from the proto field name (underscores to camel case, a
     * trailing '_' when the name clashes with a reserved one); match case- and underscore-insensitively
     * instead of replicating its rules.
     */
    private static Method findGetter(Class<?> messageClass, String name, int parameterCount) {
        String wanted = name.replace("_", "").toLowerCase(Locale.ROOT);
        for (Method method : messageClass.getMethods()) {
            if (method.getDeclaringClass() != Object.class
                && method.getParameterCount() == parameterCount
                && method.getName().replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return method;
            }
        }
        throw new IllegalStateException("No getter " + name + " on " + messageClass.getName());
    }

    private static final String[] GSON_PROTOBUF_RUNTIME_CLASSES = {
        "com.google.protobuf.AbstractMessage",
        "com.google.protobuf.AbstractMessageLite",
        "com.google.protobuf.GeneratedMessage",
        "com.google.protobuf.GeneratedMessageV3",
        "com.google.protobuf.ByteString",
        "com.google.protobuf.RopeByteString",
        "com.google.protobuf.UnknownFieldSet",
        "com.google.protobuf.MapField",
        "com.google.protobuf.AbstractProtobufList",
        "com.google.protobuf.ProtobufArrayList",
        "com.google.protobuf.LazyStringArrayList",
        "com.google.protobuf.BooleanArrayList",
        "com.google.protobuf.IntArrayList",
        "com.google.protobuf.LongArrayList",
        "com.google.protobuf.FloatArrayList",
        "com.google.protobuf.DoubleArrayList"
    };

    /**
     * Field access for the class and every class nested in it. The protobuf runtime's internals differ between
     * versions: a class the protobuf version on the class path no longer has fails the build, for the list to be
     * reviewed.
     */
    private static void addFieldsWithNestedClasses(List<Map<String, Object>> entries, String className) {
        Class<?> clazz = ClasspathScans.loadClass(className);
        entries.add(fieldsOnlyEntry(clazz.getName()));
        for (Class<?> nested : clazz.getDeclaredClasses()) {
            entries.add(fieldsOnlyEntry(nested.getName()));
        }
    }

    /**
     * The fields the generated config binder writes through {@code Field.set} as upstream does (no setter writes them:
     * none declared, or a final or a static field), as the config-generator lists them ({@code declaringClass#field},
     * then {@code static} and {@code final} where they apply): each declaring class with those fields, so the image
     * finds them, and a final instance one writable; a static final one is only found, so {@code Field.set} refuses it
     * as upstream's does.
     */
    static List<Map<String, Object>> configFieldSetEntries(List<String> setByField) {
        Map<String, List<Map<String, Object>>> byClass = new TreeMap<>();
        for (String line : setByField) {
            String[] parts = line.split("\\s+");
            int hash = parts[0].indexOf('#');
            List<String> modifiers = Arrays.asList(parts).subList(1, parts.length);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", parts[0].substring(hash + 1));
            if (modifiers.contains("final") && !modifiers.contains("static")) {
                field.put("allowWrite", true);
            }
            byClass.computeIfAbsent(parts[0].substring(0, hash), k -> new ArrayList<>()).add(field);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        byClass.forEach((className, fields) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", className);
            entry.put("fields", fields);
            entries.add(entry);
        });
        return entries;
    }

    private static Map<String, Object> fieldsOnlyEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("allDeclaredFields", true);
        return entry;
    }

    private static Map<String, Object> fullAccessEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("allDeclaredFields", true);
        entry.put("allDeclaredMethods", true);
        entry.put("allDeclaredConstructors", true);
        return entry;
    }

    private static Map<String, Object> constructorOnlyEntry(String className) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", className);
        entry.put("methods", List.of(Map.of("name", "<init>", "parameterTypes", List.of())));
        return entry;
    }

    /**
     * Read class names from a manifest file (one class name per line).
     */
    private static List<String> readClassNames(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Read values from a key=value manifest file. Returns sorted unique values.
     */
    private static List<String> readValueFromKeyValue(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty() && s.contains("="))
            .map(s -> s.substring(s.indexOf('=') + 1))
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * The TAL expression classes of {@code tal-v2-classes.txt}: each one's constructor, plus the function-object
     * fields {@code tal-v2-rules.txt} records for it, which the runtime TalDSL writes.
     */
    private static void addTalExpressionEntries(List<Map<String, Object>> entries, Path classes, Path rules)
        throws IOException {
        Map<String, List<Map<String, Object>>> fieldsByClass = new HashMap<>();
        Properties props = loadProperties(rules);
        for (int i = 0; props.getProperty("rule." + i + ".class") != null; i++) {
            List<Map<String, Object>> fields = fieldsByClass.computeIfAbsent(
                props.getProperty("rule." + i + ".class"), k -> new ArrayList<>());
            for (int f = 0; props.getProperty("rule." + i + ".function." + f + ".field") != null; f++) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", props.getProperty("rule." + i + ".function." + f + ".field"));
                field.put("allowWrite", true);
                fields.add(field);
            }
        }
        for (String className : readClassNames(classes)) {
            Map<String, Object> entry = constructorOnlyEntry(className);
            List<Map<String, Object>> fields = fieldsByClass.get(className);
            if (fields != null && !fields.isEmpty()) {
                entry.put("fields", fields);
            }
            entries.add(entry);
        }
    }

    /**
     * Read class names from a one-per-line manifest and add constructor-only entries.
     */
    private static void addConstructorEntries(List<Map<String, Object>> entries, Path file) throws IOException {
        for (String className : readClassNames(file)) {
            entries.add(constructorOnlyEntry(className));
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
