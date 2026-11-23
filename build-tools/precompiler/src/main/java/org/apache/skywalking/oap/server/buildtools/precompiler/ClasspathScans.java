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

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.protobuf.Message;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.apache.skywalking.oap.server.core.analysis.ISourceDecorator;
import org.apache.skywalking.oap.server.core.analysis.SourceDispatcher;
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue;
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction;
import org.apache.skywalking.oap.server.core.annotation.AnnotationListener;
import org.apache.skywalking.oap.server.core.remote.data.StreamData;
import org.apache.skywalking.oap.server.core.storage.type.StorageBuilder;

import static org.apache.skywalking.oap.server.buildtools.common.ProtobufMessages.generatedMessage;
import static org.apache.skywalking.oap.server.buildtools.precompiler.BuildIntermediates.writeManifest;
import static org.apache.skywalking.oap.server.buildtools.precompiler.GenericTypes.typeBindings;
import static org.apache.skywalking.oap.server.buildtools.precompiler.GenericTypes.valueTypes;

/**
 * The class path scans: the class lists the boot reads (annotation scan, dispatchers), the meter functions the rule
 * registry carries, and the lists the native-image metadata is generated from.
 */
@Slf4j
final class ClasspathScans {
    /**
     * @return the annotation-scan manifests the native-image metadata registers, and the meter functions
     */
    static Result writeManifests(final Path metaInf) throws Exception {
        Path annotationScanDir = metaInf.resolve("annotation-scan");
        Files.createDirectories(annotationScanDir);

        ImmutableSet<ClassPath.ClassInfo> allClasses = ClassPath
            .from(ClasspathScans.class.getClassLoader())
            .getTopLevelClassesRecursive("org.apache.skywalking");

        // The class lists AnnotationScan and SourceReceiverImpl read at boot: one per annotation an AnnotationListener
        // listens for, and the decorators and dispatchers
        List<String> scannedClassManifests = new ArrayList<>();
        for (Class<? extends Annotation> annotation : listenedAnnotations(allClasses)) {
            String manifest = annotation.getSimpleName() + ".txt";
            writeManifest(annotationScanDir.resolve(manifest), scanAnnotation(allClasses, annotation));
            scannedClassManifests.add(manifest);
        }
        writeManifest(annotationScanDir.resolve("SourceDispatcher.txt"),
            scanImplementations(allClasses, SourceDispatcher.class));
        writeManifest(annotationScanDir.resolve("ISourceDecorator.txt"),
            scanImplementations(allClasses, ISourceDecorator.class));
        scannedClassManifests.add("SourceDispatcher.txt");
        scannedClassManifests.add("ISourceDecorator.txt");

        // The meter functions, for the build-time MeterSystem and, through the rule registry, the runtime one
        final Map<String, Class<? extends AcceptableValue>> meterFunctions = meterFunctionRegister(allClasses);
        writeManifest(annotationScanDir.resolve("MeterFunction.txt"), meterFunctions.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue().getName()).collect(Collectors.toList()));

        // The rest are build intermediates of the reflection metadata, which the generated jar leaves out.
        writeManifest(annotationScanDir.resolve("StorageBuilders.txt"),
            scanStorageBuilders(allClasses));
        // RemoteHandleWorker rebuilds the stream data another OAP node sends with newInstance(), of any class a module
        // registers through IWorkerInstanceSetter, an @Stream class or not
        writeManifest(annotationScanDir.resolve("StreamData.txt"), scanStreamData(allClasses));

        final List<String> armeriaHandlers = scanArmeriaHandlers(allClasses);
        writeManifest(annotationScanDir.resolve("ArmeriaHandlers.txt"), armeriaHandlers);
        writeManifest(annotationScanDir.resolve("HttpBodyTypes.txt"), scanHttpBodyTypes(armeriaHandlers));

        writeManifest(annotationScanDir.resolve("GraphQLResolvers.txt"),
            scanGraphQLResolvers(allClasses));
        writeManifest(annotationScanDir.resolve("GraphQLTypes.txt"),
            scanGraphQLTypes(allClasses));

        writeManifest(annotationScanDir.resolve("QueryEntityClasses.txt"),
            scanQueryEntityClasses(allClasses));
        return new Result(scannedClassManifests, meterFunctions);
    }

    record Result(List<String> scannedClassManifests, Map<String, Class<? extends AcceptableValue>> meterFunctions) {
    }

    /**
     * The {@code @MeterFunction} classes by function name, as upstream {@code MeterSystem}'s constructor registers
     * them, and failing as it does on one that is no {@code AcceptableValue}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Class<? extends AcceptableValue>> meterFunctionRegister(
        Iterable<ClassPath.ClassInfo> classes) {
        Map<String, Class<? extends AcceptableValue>> functionRegister = new TreeMap<>();
        for (ClassPath.ClassInfo classInfo : classes) {
            Class<?> aClass = classInfo.load();
            if (aClass.isAnnotationPresent(MeterFunction.class)) {
                MeterFunction mf = aClass.getAnnotation(MeterFunction.class);
                if (!AcceptableValue.class.isAssignableFrom(aClass)) {
                    throw new IllegalArgumentException(
                        "Function " + aClass.getCanonicalName() + " doesn't implement AcceptableValue.");
                }
                functionRegister.put(mf.functionName(), (Class<? extends AcceptableValue>) aClass);
            }
        }
        return functionRegister;
    }

    /**
     * @return the annotation of every concrete AnnotationListener, by its simple name, the manifest name AnnotationScan
     * reads; each listener's own annotation(), on its singleton {@code INSTANCE} or an instance its constructor builds
     * from nulls (listeners keep their arguments for notify)
     */
    static Collection<Class<? extends Annotation>> listenedAnnotations(ImmutableSet<ClassPath.ClassInfo> allClasses)
        throws ReflectiveOperationException {
        Map<String, Class<? extends Annotation>> annotations = new TreeMap<>();
        List<Class<?>> candidates = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            collectNested(classInfo.load(), candidates);
        }
        for (Class<?> type : candidates) {
            if (!AnnotationListener.class.isAssignableFrom(type) || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Class<? extends Annotation> annotation = listenerInstance(type).annotation();
            Class<? extends Annotation> clash = annotations.put(annotation.getSimpleName(), annotation);
            if (clash != null && clash != annotation) {
                throw new IllegalStateException("Two listened annotations share the manifest name "
                    + annotation.getSimpleName() + ": " + clash.getName() + ", " + annotation.getName());
            }
        }
        return annotations.values();
    }

    private static void collectNested(Class<?> type, List<Class<?>> into) {
        into.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectNested(nested, into);
        }
    }

    private static AnnotationListener listenerInstance(Class<?> type) throws ReflectiveOperationException {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                return (AnnotationListener) field.get(null);
            }
        }
        Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
            .min(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow();
        constructor.setAccessible(true);
        return (AnnotationListener) constructor.newInstance(new Object[constructor.getParameterCount()]);
    }

    /**
     * Scan for classes annotated with the given annotation type. As upstream's scans, a class that does not load fails
     * the build: every class scanned here is on the image's class path.
     */
    private static List<String> scanAnnotation(
        ImmutableSet<ClassPath.ClassInfo> allClasses,
        Class<? extends Annotation> annotationType) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (aClass.isAnnotationPresent(annotationType)) {
                result.add(aClass.getName());
            }
        }
        Collections.sort(result);
        log.info("Scanned @{}: {} classes", annotationType.getSimpleName(), result.size());
        return result;
    }

    /**
     * Scan for concrete classes implementing or extending the given type.
     */
    private static List<String> scanImplementations(
        ImmutableSet<ClassPath.ClassInfo> allClasses, Class<?> type) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (!aClass.isInterface()
                && !Modifier.isAbstract(aClass.getModifiers())
                && type.isAssignableFrom(aClass)) {
                result.add(aClass.getName());
            }
        }
        Collections.sort(result);
        log.info("Scanned {}: {} classes", type.getSimpleName(), result.size());
        return result;
    }

    /**
     * @return every concrete {@link StreamData} class, nested ones included
     */
    private static List<String> scanStreamData(ImmutableSet<ClassPath.ClassInfo> allClasses) {
        List<Class<?>> classes = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            collectNested(classInfo.load(), classes);
        }
        List<String> result = classes.stream()
            .filter(c -> StreamData.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()))
            .map(Class::getName).sorted().collect(Collectors.toList());
        log.info("Scanned StreamData: {} classes", result.size());
        return result;
    }

    /**
     * The {@code builder()} of every {@code @Stream} class and the {@code StorageBuilder} classes nested in every
     * {@code @MeterFunction} class, which the storage instantiates through
     * {@code getDeclaredConstructor().newInstance()}.
     */
    private static List<String> scanStorageBuilders(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        List<String> result = new ArrayList<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            // @Stream-annotated classes declare builder in annotation
            if (aClass.isAnnotationPresent(org.apache.skywalking.oap.server.core.analysis.Stream.class)) {
                Class<?> builderClass =
                    aClass.getAnnotation(org.apache.skywalking.oap.server.core.analysis.Stream.class).builder();
                if (builderClass != null && builderClass != void.class) {
                    result.add(builderClass.getName());
                }
            }
            // @MeterFunction classes have StorageBuilder inner classes
            if (aClass.isAnnotationPresent(MeterFunction.class)) {
                for (Class<?> inner : aClass.getDeclaredClasses()) {
                    if (StorageBuilder.class.isAssignableFrom(inner)) {
                        result.add(inner.getName());
                    }
                }
            }
        }
        // Deduplicate (multiple @Stream classes may share the same builder)
        result = result.stream().distinct().sorted().collect(Collectors.toList());
        log.info("Scanned StorageBuilder classes: {} unique entries", result.size());
        return result;
    }

    /**
     * Scan for Armeria HTTP handler classes — classes with methods annotated with
     * {@code @Post}, {@code @Get}, or {@code @Path} from {@code com.linecorp.armeria.server.annotation}.
     * Also collects the classes {@code @ExceptionHandler}, {@code @Decorator}, {@code @RequestConverter} and
     * {@code @ResponseConverter} name.
     * Armeria's annotatedService().build(handler) uses reflection to discover these annotations;
     * without reflection metadata, routes are silently not registered (returning 404).
     */
    private static List<String> scanArmeriaHandlers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            Class<?> aClass = classInfo.load();
            if (aClass.isInterface() || Modifier.isAbstract(aClass.getModifiers())) {
                continue;
            }
            // Check if any method (including inherited) has Armeria routing annotations
            boolean matched = false;
            for (Method method : aClass.getMethods()) {
                if (isRoute(method)) {
                    matched = true;
                    // Include declaring class of inherited annotated methods (e.g. abstract base handlers)
                    // so Armeria can reflect on their @Get/@Path annotations at runtime
                    Class<?> declaring = method.getDeclaringClass();
                    if (declaring != aClass && declaring != Object.class) {
                        result.add(declaring.getName());
                    }
                }
            }
            if (matched) {
                result.add(aClass.getName());
                collectNamedClasses(aClass, ExceptionHandler.class, result);
                collectNamedClasses(aClass, Decorator.class, result);
                collectNamedClasses(aClass, RequestConverter.class, result);
                collectNamedClasses(aClass, ResponseConverter.class, result);
            }
        }

        // Also scan for ExceptionHandlerFunction implementations (instantiated by Armeria via reflection)
        result.addAll(scanImplementations(allClasses, ExceptionHandlerFunction.class));

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned Armeria HTTP handlers: {} classes", sorted.size());
        return sorted;
    }

    private static boolean isRoute(Method method) {
        return method.isAnnotationPresent(Get.class) || method.isAnnotationPresent(Post.class)
            || method.isAnnotationPresent(com.linecorp.armeria.server.annotation.Path.class);
    }

    /**
     * Collect the classes the {@code annotation}s on the handler class and its methods name ({@code value()}): Armeria
     * instantiates them reflectively while building the annotated service, and a miss fails the boot.
     */
    private static void collectNamedClasses(Class<?> handlerClass, Class<? extends Annotation> annotation,
                                            Set<String> result) {
        List<Annotation> annotations = new ArrayList<>(List.of(handlerClass.getAnnotationsByType(annotation)));
        for (Method method : handlerClass.getMethods()) {
            annotations.addAll(List.of(method.getAnnotationsByType(annotation)));
        }
        for (Annotation anno : annotations) {
            Object value;
            try {
                value = annotation.getMethod("value").invoke(anno);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read the @" + annotation.getSimpleName() + " of "
                    + handlerClass.getName(), e);
            }
            for (Class<?> named : value instanceof Class[] ? (Class<?>[]) value : new Class<?>[] {(Class<?>) value}) {
                result.add(named.getName());
            }
        }
    }

    /**
     * The classes Armeria binds a route's request and response bodies to: each route's parameters that are no
     * {@code @Param} or {@code @Header} value, its return type, the classes their fields hold and their superclasses, as
     * far as they are this project's classes, its enums included (Gson reads their fields); a protobuf message of any
     * package, which Armeria converts through {@code JsonFormat}, stands for its descriptor closure. A body a route
     * builds itself (an {@code HttpResponse} of an object) is not in a signature: its class stays on the
     * {@code configPojos} list by hand.
     */
    static List<String> scanHttpBodyTypes(List<String> handlers) {
        Set<Class<?>> bodies = new LinkedHashSet<>();
        for (String handler : handlers) {
            Class<?> handlerClass = loadClass(handler);
            // A route a generic superclass declares binds the bodies the handler gives its type variables
            Map<TypeVariable<?>, Type> bindings = typeBindings(handlerClass);
            for (Method method : handlerClass.getMethods()) {
                if (!isRoute(method)) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(Param.class) && !parameter.isAnnotationPresent(Header.class)) {
                        collectBodyTypes(parameter.getParameterizedType(), bindings, bodies);
                    }
                }
                collectBodyTypes(method.getGenericReturnType(), bindings, bodies);
            }
        }
        List<String> result = bodies.stream().map(Class::getName).sorted().collect(Collectors.toList());
        log.info("Scanned HTTP body types: {} classes", result.size());
        return result;
    }

    private static void collectBodyTypes(Type type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> bodies) {
        for (Class<?> element : valueTypes(type, bindings)) {
            Class<?> message = generatedMessage(element);
            if (message != null) {
                bodies.add(message);
                continue;
            }
            // An enum's own fields are what Gson reads; it holds no other body
            if (!element.getName().startsWith("org.apache.skywalking.") || element.isInterface()
                || element.isAnnotation() || !bodies.add(element) || element.isEnum()
                || Message.class.isAssignableFrom(element)) {
                continue;
            }
            // Jackson and Gson read the fields and accessors each class of the hierarchy declares
            Map<TypeVariable<?>, Type> hierarchy = typeBindings(element);
            for (Class<?> declaring = element; declaring != null
                && declaring.getName().startsWith("org.apache.skywalking."); declaring = declaring.getSuperclass()) {
                bodies.add(declaring);
                for (Field field : declaring.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                        collectBodyTypes(field.getGenericType(), hierarchy, bodies);
                    }
                }
            }
        }
    }

    /**
     * The POJOs HTTP handlers serialize through Jackson or Gson, found by package (the patterns below): the query
     * plugins' entities (PromQL, LogQL, TraceQL, ...), the admin-server family's responses, the status module's
     * debugging bodies and the alarm status. Nested classes are included, interfaces left out.
     */
    private static List<String> scanQueryEntityClasses(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        // Match: org.apache.skywalking.oap.query.<plugin>.entity[.<sub>].<ClassName>
        //        org.apache.skywalking.oap.server.admin.<module>.response.<ClassName>
        //        org.apache.skywalking.oap.server.admin.status[.<sub>].Debugging*Rsp (the /debugging/* YAML bodies)
        //        org.apache.skywalking.oap.server.core.alarm.provider.status.<ClassName> (the /status/alarm/* bodies,
        //        Gson both ways: each node's status is printed, then parsed and merged by the node answering)
        Set<String> result = new HashSet<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            String name = classInfo.getName();
            boolean queryEntity = name.startsWith("org.apache.skywalking.oap.query.") && name.contains(".entity.");
            boolean adminResponse = name.startsWith("org.apache.skywalking.oap.server.admin.")
                && name.contains(".response.");
            boolean debuggingResponse = name.startsWith("org.apache.skywalking.oap.server.admin.status.")
                && classInfo.getSimpleName().startsWith("Debugging") && classInfo.getSimpleName().endsWith("Rsp");
            boolean alarmStatus = name.startsWith("org.apache.skywalking.oap.server.core.alarm.provider.status.");
            if (!queryEntity && !adminResponse && !debuggingResponse && !alarmStatus) {
                continue;
            }
            Class<?> aClass = classInfo.load();
            if (aClass.isInterface()) {
                continue;
            }
            result.add(aClass.getName());
            // Also include inner static classes (e.g. SearchResponse$Trace, StreamLog$Result)
            for (Class<?> inner : aClass.getDeclaredClasses()) {
                if (!inner.isInterface()) {
                    result.add(inner.getName());
                }
            }
        }

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        log.info("Scanned query entity classes: {} classes", sorted.size());
        return sorted;
    }

    /**
     * Scan for GraphQL resolver classes — implementations of
     * {@code graphql.kickstart.tools.GraphQLQueryResolver} or
     * {@code graphql.kickstart.tools.GraphQLMutationResolver}.
     * graphql-java-tools reflects on methods to map GraphQL schema operations to Java methods.
     */
    private static List<String> scanGraphQLResolvers(
        ImmutableSet<ClassPath.ClassInfo> allClasses) {

        Set<String> result = new TreeSet<>(scanImplementations(allClasses, GraphQLQueryResolver.class));
        result.addAll(scanImplementations(allClasses, GraphQLMutationResolver.class));
        log.info("Scanned GraphQL resolvers: {} classes", result.size());
        return new ArrayList<>(result);
    }

    /**
     * Scan GraphQL schema files (.graphqls) for type/input/enum definitions and match
     * them to Java classes on the classpath by simple name.
     * graphql-java-tools (kickstart) maps schema type names to Java class simple names;
     * reflection is used to access fields/getters for field resolution.
     */
    private static List<String> scanGraphQLTypes(
        ImmutableSet<ClassPath.ClassInfo> allClasses) throws IOException {

        // Build simple name → list of FQCNs index for all SkyWalking classes.
        // Multiple classes can share a simple name (e.g., AlarmMessage exists in
        // core.alarm, core.alarm.grpc, and core.query.type packages).
        // We include ALL matching classes for reflection to avoid missing the
        // graphql-java-tools type resolver's target class.
        Map<String, List<String>> simpleNameIndex = new HashMap<>();
        for (ClassPath.ClassInfo classInfo : allClasses) {
            simpleNameIndex.computeIfAbsent(classInfo.getSimpleName(), k -> new ArrayList<>())
                .add(classInfo.getName());
        }

        // Parse .graphqls files from classpath
        Set<String> graphqlTypeNames = new HashSet<>();
        Pattern typePattern = Pattern.compile("^\\s*(type|input|enum)\\s+(\\w+)");

        for (String schemaFile : listGraphQLSchemaFiles()) {
            try (BufferedReader reader = new BufferedReader(ResourceUtils.read(schemaFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = typePattern.matcher(line);
                    if (m.find()) {
                        graphqlTypeNames.add(m.group(2));
                    }
                }
            }
        }

        // Remove root types that are resolvers, not DTOs
        graphqlTypeNames.remove("Query");
        graphqlTypeNames.remove("Mutation");
        graphqlTypeNames.remove("Subscription");

        // Match schema type names to Java classes (include all FQCN variants)
        List<String> result = new ArrayList<>();
        Set<String> unmatched = new HashSet<>();
        for (String typeName : graphqlTypeNames) {
            List<String> fqcns = simpleNameIndex.get(typeName);
            if (fqcns != null) {
                result.addAll(fqcns);
            } else {
                unmatched.add(typeName);
            }
        }
        Collections.sort(result);
        if (!unmatched.isEmpty()) {
            log.info("GraphQL types not matched to Java classes (may be scalars or external): {}",
                unmatched.stream().sorted().collect(Collectors.joining(", ")));
        }
        log.info("Scanned GraphQL types: {} matched from {} schema types",
            result.size(), graphqlTypeNames.size());
        return result;
    }

    /**
     * List all query-protocol/*.graphqls files from the classpath.
     * Scans both filesystem directories and JAR entries since the schema files
     * are packaged inside the query-graphql-plugin JAR.
     */
    private static List<String> listGraphQLSchemaFiles() throws IOException {
        Set<String> files = new TreeSet<>();
        for (URL url : Collections.list(ClasspathScans.class.getClassLoader().getResources("query-protocol"))) {
            if ("file".equals(url.getProtocol())) {
                try (Stream<Path> children = Files.list(Path.of(url.toURI()))) {
                    children.map(f -> f.getFileName().toString()).filter(name -> name.endsWith(".graphqls"))
                            .forEach(name -> files.add("query-protocol/" + name));
                } catch (URISyntaxException e) {
                    throw new IOException("Unreadable schema directory " + url, e);
                }
            } else if ("jar".equals(url.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        if (entry.getName().startsWith("query-protocol/") && entry.getName().endsWith(".graphqls")) {
                            files.add(entry.getName());
                        }
                    }
                }
            } else {
                throw new IllegalStateException("query-protocol/ is at " + url + ", which the scan cannot list");
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No query-protocol/*.graphqls on the precompiler's class path: the "
                + "GraphQL types the image reflects on are read from them");
        }
        return new ArrayList<>(files);
    }

    static Class<?> loadClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(fqcn + " is not on the precompiler's class path", e);
        }
    }
}
