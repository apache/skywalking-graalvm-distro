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

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.skywalking.mal.rt.closure.ClosureVisitor;
import org.apache.skywalking.mal.rt.closure.entity.ClosureAnalyseResult;
import org.apache.skywalking.mal.rt.grammar.ClosureLexer;
import org.apache.skywalking.mal.rt.grammar.ClosureParser;
import org.apache.skywalking.mal.rt.kernel.MALClassFileGenerator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

public class ClosureTest {


    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {
                        "Simple",
                        "{tags.host_name = 'mysql::' + tags.host_name}",
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
                                "\n" +
                                "public class SimpleTest implements org.apache.skywalking.mal.rt.entity.GraalClosure{\n" +
                                "\n" +
                                "    @Override\n" +
                                "    public Object execute(Map<String, Object> variables) {\n" +
                                "        Map<String, String> tags = (Map<String, String>) variables.get(\"tags\");" +
                                "        tags.put(\"host_name\", \"mysql::\"+tags.get(\"host_name\"));\n" +
                                "\n" +
                                "        return tags;\n" +
                                "    }\n" +
                                "\n" +
                                "}\n"

                },
                {
                        "Complex",
                        "{ prefix, tags ->\n" +
                        " if (tags[prefix + '_process_id'] != null) {\n" +
                        "   return\n" +
                        " }\n" +
                        " if (tags[prefix + '_local'] == 'true') {\n" +
                        "   tags[prefix + '_process_id'] = ProcessRegistry.generateVirtualLocalProcess(tags.service, tags.instance)\n" +
                        "   return\n" +
                        " }\n" +
                        " tags[prefix + '_process_id'] = ProcessRegistry.generateVirtualRemoteProcess(tags.service, tags.instance, tags[prefix + '_address'])\n" +
                        "}\n",
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
                                "\n" +
                                "public class ComplexTest implements org.apache.skywalking.mal.rt.entity.GraalClosure{\n" +
                                "\n" +
                                "    @Override\n" +
                                "    public Object execute(Map<String, Object> variables) {\n" +
                                "        Map<String, String> tags = (Map<String, String>) variables.get(\"tags\");\n" +
                                "        String prefix = (String)variables.get(\"prefix\");if (tags.get(prefix+\"_process_id\")!=null) {return null;}\n" +
                                "        if (tags.get(prefix+\"_local\").equals(\"true\")) {tags.put(prefix+\"_process_id\", org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry.generateVirtualLocalProcess(tags.get(\"service\"),tags.get(\"instance\")));return null;}\n" +
                                "        tags.put(prefix+\"_process_id\", org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry.generateVirtualRemoteProcess(tags.get(\"service\"),tags.get(\"instance\"),tags.get(prefix+\"_address\")));\n" +
                                "\n" +
                                "        return tags;\n" +
                                "    }\n" +
                                "\n" +
                                "}\n"
                }

        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void test(String name, String exp, String expect) throws IOException {

        ClosureLexer closureLexer = new ClosureLexer(CharStreams.fromString(exp));
        CommonTokenStream tokens = new CommonTokenStream(closureLexer);

        ClosureParser closureParser = new ClosureParser(tokens);
        ClosureParser.ClosureContext closure = closureParser.closure();
        ClosureVisitor closureVisitor = new ClosureVisitor();
        ClosureAnalyseResult analyse = closureVisitor.analyse(closure);
        Path classLocation = getClassLocation();
        MALClassFileGenerator.generateClosureClass(classLocation,
                 name + "Test", analyse);

        String generatedFilePath = getClassLocation() + File.separator + name + "Test.java";

        String content = Files.readString(Paths.get(generatedFilePath));
        assert replaceNotImportantContent(content).equals(replaceNotImportantContent(expect));
    }

    private Path getClassLocation() {
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
