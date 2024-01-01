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

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

public class ClosureTest {


    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {
                        "Simple",
                        "{tags.host_name = 'mysql::' + tags.host_name}"
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
                        "}\n"
                }

        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void test(String name, String exp) {

        ClosureLexer closureLexer = new ClosureLexer(CharStreams.fromString(exp));
        CommonTokenStream tokens = new CommonTokenStream(closureLexer);

        ClosureParser closureParser = new ClosureParser(tokens);
        ClosureParser.ClosureContext closure = closureParser.closure();
        ClosureVisitor closureVisitor = new ClosureVisitor();
        ClosureAnalyseResult analyse = closureVisitor.analyse(closure);
        MALClassFileGenerator.generateClosureClass(getClassLocation(),
                 name + "Test", analyse);
    }

    public Path getClassLocation() {
        URL location = this.getClass().getProtectionDomain().getCodeSource().getLocation();
        try {
            Path currentDirectory = Paths.get(location.toURI()).getParent();
            return currentDirectory;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

}
