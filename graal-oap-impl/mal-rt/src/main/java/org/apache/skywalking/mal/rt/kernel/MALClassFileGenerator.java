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
 *
 */

package org.apache.skywalking.mal.rt.kernel;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.Version;
import org.apache.skywalking.mal.rt.closure.entity.ClosureAnalyseResult;
import org.apache.skywalking.mal.rt.entity.Value;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.server.core.UnexpectedException;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MALClassFileGenerator {

    private static final String CLOSURE_VARIABLE_SUFFIX = "_0";
    static Configuration configuration = new Configuration(new Version("2.3.28"));

    static {
        configuration.setEncoding(Locale.ENGLISH, "UTF-8");
        configuration.setClassLoaderForTemplateLoading(MALKernel.class.getClassLoader(), "/templates");
    }

    public static void generate(Path generatedFilePath, String className, List<String> varDeclares, List<String> methodBody) {
        Path file = generatedFilePath.resolve(className + ".java");
        BufferedWriter out = null;
        Template template = null;

        try {
            out = new BufferedWriter(new FileWriter(file.toFile()));
            template = configuration.getTemplate("mal.ftl");
        } catch (IOException e) {
            throw new UnexpectedException("file mal.ftl not found.");
        }
        StringBuilder methodBodyString = new StringBuilder();

        varDeclares.forEach(
                param -> {
                    methodBodyString.append(param);
                    methodBodyString.append("\n");
                }
        );

        methodBody.forEach(
                method -> {
                    methodBodyString.append(method);
                    methodBodyString.append("\n");
                }
        );

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("className", className);
        templateData.put("imports", Arrays.asList("java.util.LinkedList", "java.util.Map", "java.util.HashMap"
                , Value.class.getName(), SampleFamily.class.getName()));
        templateData.put("executeBody", methodBodyString.toString());

        try {
            template.process(templateData, out);
            out.close();
        } catch (TemplateException | IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static void generateClosureClass(Path generatedFilePath, String className, ClosureAnalyseResult result) {
        BufferedWriter out = null;
        Template template = null;
        Path file = generatedFilePath.resolve(className + ".java");
        try {
            out = new BufferedWriter(new FileWriter(file.toFile()));
            template = configuration.getTemplate("closure.ftl");
        } catch (IOException e) {
            throw new UnexpectedException("file closure.ftl not found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Map<String, String> tags = (Map<String, String>) variables.get(\"tags\");\n");
        if (result.getParams() != null) {
            result.getParams().forEach(
                    param -> {
                        if (param.getKey().equals("tags")) {
                            return;
                        }
                        sb.append(param.getValue());
                        sb.append(" ");
                        sb.append(param.getKey());
                        sb.append(" = (");
                        sb.append(param.getValue());
                        sb.append(")");
                        sb.append("variables.get(\"" + param.getKey() + "\");\n");
                    }
            );
        }

        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }

        Map<String, Object> templateData = new HashMap<>();
        templateData.put("className", className);
        templateData.put("imports", Arrays.asList("java.util.LinkedList", "java.util.Map", "java.util.HashMap"));
        templateData.put("executeBody", sb + result.getExprs() + "\n\treturn tags;");

        try {
            template.process(templateData, out);
            out.close();
        } catch (TemplateException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void mkdir(String generatedPath) {
        try {
            Files.createDirectories(Paths.get(generatedPath));
        } catch (IOException e) {
            throw new UnexpectedException("unable to create dir.");
        }
    }
}
