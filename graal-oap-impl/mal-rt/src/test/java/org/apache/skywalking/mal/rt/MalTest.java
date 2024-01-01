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

import java.net.URISyntaxException;
import java.net.URL;
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
                        "(http_success_request.sum(['region']) + test.sum(['region']) * 100 ).service(['region'], Layer.MYSQL)"
                }

        });
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    void analyse(String name, String mal) {

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

        malKernel.generateClassFile(getClassLocation(), name);


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