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

package org.apache.skywalking.mal.rt;

import com.google.common.collect.ImmutableMap;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import org.apache.skywalking.mal.rt.core.entity.MALUnit;
import org.apache.skywalking.mal.rt.core.MALVisitor;
import org.apache.skywalking.mal.rt.grammar.MALCoreLexer;
import org.apache.skywalking.mal.rt.grammar.MALCoreParser;
import org.apache.skywalking.mal.rt.kernel.MALKernel;
import org.apache.skywalking.oap.meter.analyzer.dsl.Sample;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyBuilder;
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.config.group.EndpointNameGrouping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.google.common.collect.ImmutableMap.of;

class MalTest {

    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {
                        "SimpleMAL",
                        "(http_success_request.sum(['region']) + test.sum(['region']) * 100 ).service(['region'], Layer.MYSQL)",
                        "/*\n" +
                                " * Licensed to the Apache Software Foundation (ASF) under one or more\n" +
                                " * contributor license agreements.  See the NOTICE file distributed with\n" +
                                " * this work for additional information regarding copyright ownership.\n" +
                                " * The ASF licenses this file to You under the Apache License, Version 2.0\n" +
                                " * (the \"License\"); you may not use this file except in compliance with\n" +
                                " * the License.  You may obtain a copy of the License at\n" +
                                " *\n" +
                                " *     http://www.apache.org/licenses/LICENSE-2.0\n" +
                                " *\n" +
                                " * Unless required by applicable law or agreed to in writing, software\n" +
                                " * distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
                                " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
                                " * See the License for the specific language governing permissions and\n" +
                                " * limitations under the License.\n" +
                                " *\n" +
                                " */\n" +
                                "\n" +
                                "package org.apache.skywalking.mal.rt.generated;\n" +
                                "import java.util.LinkedList;\n" +
                                "import java.util.Map;\n" +
                                "import java.util.HashMap;\n" +
                                "import org.apache.skywalking.mal.rt.entity.Value;\n" +
                                "import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;\n" +
                                "\n" +
                                "public class SimpleMAL {\n" +
                                "\n" +
                                "    private LinkedList<Value> stack = new LinkedList<>();\n" +
                                "    private Map<String, SampleFamily> sampleFamilyMap;\n" +
                                "\n" +
                                "    public SimpleMAL (Map<String, SampleFamily> map) {\n" +
                                "        this.sampleFamilyMap = map;\n" +
                                "    }\n" +
                                "\n" +
                                "    public void execute() {\n" +
                                "        java.util.List<java.lang.String> var0 = java.util.List.of(\"region\");\n" +
                                "        java.util.List<java.lang.String> var2 = java.util.List.of(\"region\");\n" +
                                "        java.util.List<java.lang.String> var10 = java.util.List.of(\"region\");\n" +
                                "        org.apache.skywalking.oap.server.core.analysis.Layer var11 = org.apache.skywalking.oap.server.core.analysis.Layer.valueOf(18);\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(sampleFamilyMap.get( \"http_success_request\")));\n" +
                                "        org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily var1 = org.apache.skywalking.mal.rt.util.SampleFamilyUtil.sum(stack.pop().getValueAsSampleFamily(),var0);;\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(var1));\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(sampleFamilyMap.get( \"test\")));\n" +
                                "        org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily var3 = org.apache.skywalking.mal.rt.util.SampleFamilyUtil.sum(stack.pop().getValueAsSampleFamily(),var2);;\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(var3));\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.ScalarValue.as(100.0));\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var4 = stack.pop();\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var5 = stack.pop();\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var6 = var4.multiply(var5);\n" +
                                "        stack.push(var6);\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var7 = stack.pop();\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var8 = stack.pop();\n" +
                                "        org.apache.skywalking.mal.rt.entity.Value var9 = var7.plus(var8);;\n" +
                                "        stack.push(var9);\n" +
                                "        org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily var12 = org.apache.skywalking.mal.rt.util.SampleFamilyUtil.service(stack.pop().getValueAsSampleFamily(),var10,var11);;\n" +
                                "        stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(var12));\n" +
                                "    }\n" +
                                "\n" +
                                "}\n",
                                17,
                                4
                }

        });
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    void analyse(String name, String mal, String expect, int methodPartNum, int paramPartNum) throws IOException {

        NamingControl namingControl = new NamingControl(100, 100, 100, new EndpointNameGrouping());
        MeterEntity.setNamingControl(namingControl);
        MALCoreLexer malLexer = new MALCoreLexer(CharStreams.fromString(mal));
        CommonTokenStream tokens = new CommonTokenStream(malLexer);
        MALCoreParser malParser = new MALCoreParser(tokens);
        MALCoreParser.MalExpressionContext malExpressionContext = malParser.malExpression();


        MALVisitor malVisitor = new MALVisitor();

        List<MALUnit> list = malVisitor.visitMalExpression(malExpressionContext);

        MALKernel malKernel = new MALKernel(list);
        malKernel.execute();
        List<String> methodPart = malKernel.getMethodPart();
        List<String> paramPart = malKernel.getParamPart();

        assert methodPart.size() == methodPartNum;
        assert paramPart.size() == paramPartNum;

        malKernel.generateClassFile(getClassLocation(), name);

        String generatedFilePath = getClassLocation() + File.separator + name + ".java";

        String content = Files.readString(Paths.get(generatedFilePath));

        assert replaceNotImportantContent(content).equals(replaceNotImportantContent(expect));

    }

    public Path getClassLocation() {
        URL location = this.getClass().getProtectionDomain().getCodeSource().getLocation();
        try {
            return Paths.get(location.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String replaceNotImportantContent(String s) {
        return s
                .replace(" ", "")   // 删除空格
                .replace("\t", "")  // 删除制表符
                .replace("\n", ""); // 删除换行符
    }

}