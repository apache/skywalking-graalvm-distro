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

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.skywalking.mal.rt.closure.ClosureVisitor;
import org.apache.skywalking.mal.rt.closure.entity.ClosureAnalyseResult;
import org.apache.skywalking.mal.rt.core.entity.MALParam;
import org.apache.skywalking.mal.rt.core.entity.MALUnit;

import org.apache.skywalking.mal.rt.entity.SampleFamilyValue;
import org.apache.skywalking.mal.rt.entity.Value;
import org.apache.skywalking.mal.rt.grammar.ClosureLexer;
import org.apache.skywalking.mal.rt.grammar.ClosureParser;
import org.apache.skywalking.mal.rt.util.SampleFamilyUtil;
import org.apache.skywalking.oap.meter.analyzer.dsl.DownsamplingType;
import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamily;
import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.Layer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public class MALKernel {
    private final List<MALUnit> units;

    public static Path generatedPath = null;

    private static final String GENERATED_CLASS_PACKAGE = "org.apache.skywalking.mal.rt.generated";

    private LinkedList<Value> stack = new LinkedList<>();

    private List<String> methodPart = new ArrayList<>();

    private List<String> paramPart = new ArrayList<>();

    private AtomicInteger variableCount = new AtomicInteger(0);
    private AtomicInteger closureClassNameCount = new AtomicInteger(0);



    public MALKernel(List<MALUnit> units) {
        this.units = units;
    }

    public void execute() {
        for (MALUnit unit : units) {
            switch (unit.getType()) {
                case SAMPLE_FAMILY:
                    handleSampleFamily(unit.getName());
                    break;
                case SCALAR:
                    handleScalar(Double.parseDouble(unit.getName()));
                    break;
                case UNARY_FUNCTION:
                    handleUnaryFunction(unit);
                    break;
                case BINARY_FUNCTION:
                    handleBinaryFunction(unit);
                    break;
                default:
                    throw new UnexpectedException("unexpected function.");
            }
        }
    }

    private void handleScalar(double v) {
        methodPart.add(getScalar(v));
    }

    private void handleUnaryFunction(MALUnit unit) {

        if (Objects.equals(unit.getName(), "()")) {
            return;
        }

        String caller = "stack.pop().getValueAsSampleFamily()";

        List<MALParam> arguments = unit.getArguments();
        List<String> identifiers = new ArrayList<>();
        for (MALParam param : arguments) {
            MALParam.ParamType paramType = param.getParamType();
            String identifier = getRandomIdentifierString();
            identifiers.add(identifier);
            switch (paramType) {
                case LAYER:
                    Layer layer = (Layer) param.getParam();
                    int value = layer.value();
                    String layerClassName = Layer.class.getName();
                    paramPart.add(layerClassName + " " + identifier + " = " + layerClassName + ".valueOf(" + value + ");");
                    break;
                case CLOSURE:
                    String closure = param.getParam().toString();
                    String className =  handleClosure(closure);
                    String fullClassName = GENERATED_CLASS_PACKAGE + "." + className;
                    paramPart.add(closure + "  " + identifier + " = new " + fullClassName + "();");
                    break;
                case STRING:
                    String stringParam = (String) param.getParam();
                    paramPart.add(String.class.getName() + " " + identifier + " = " + stringParam + ";");
                    break;
                case NUMBER:
                    Double doubleParam = (Double) param.getParam();
                    paramPart.add("double " + doubleParam + " = " + doubleParam.toString() + ";");
                    break;
                case K8S_RETAG_TYPE:
                    K8sRetagType type = K8sRetagType.Pod2Service;
                    paramPart.add(K8sRetagType.class.getName() + " " + identifier + " = " + type + ";");
                    break;
                case PERCENTILES:
                    List<Integer> list = (List<Integer>) param.getParam();
                    String listClassName = List.class.getName();
                    StringBuilder sb = new StringBuilder();
                    sb.append(listClassName + "<" + Integer.class.getName() +
                            ">" + identifier + " = " + listClassName + ".of(");
                    list.forEach(cur -> {
                        sb.append(cur);
                        sb.append(",");
                    });
                    if (sb.charAt(sb.length() - 1) == ',') {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    sb.append(");");
                    paramPart.add(sb.toString());
                    break;
                case DOWNSAMPLING_TYPE:
                    DownsamplingType downsamplingType = (DownsamplingType) param.getParam();
                    String downSamplingTypeName = DownsamplingType.class.getName();
                    paramPart.add(downSamplingTypeName + " " + identifier + " = " +
                            downSamplingTypeName + "." + downsamplingType.name() + ";"
                    );
                    break;
                case STRING_LIST:
                    List<String> strings = (List<String>) param.getParam();
                    String lsClassName = List.class.getName();
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(lsClassName + "<" + String.class.getName() +
                            ">" + " " + identifier + " = " + lsClassName + ".of(");
                    strings.forEach(
                            cur -> {
                                stringBuilder.append("\"" + cur + "\"");
                                stringBuilder.append(",");
                            }
                    );
                    if (stringBuilder.charAt(stringBuilder.length() - 1) == ',') {
                        stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                    }
                    stringBuilder.append(");");
                    paramPart.add(stringBuilder.toString());
                    break;
                default:
                    throw new UnexpectedException("unresolvable param" + param);

            }
        }
        String methodName = unit.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        sb.append(caller);
        sb.append(",");
        identifiers.forEach(
                id -> {
                    sb.append(id);
                    sb.append(",");
                }
        );
        sb.deleteCharAt(sb.length() - 1);
        sb.append(");");

        String resultId = getRandomIdentifierString();
        String result = SampleFamily.class.getName() + " " + resultId + " = ";
        String expr = SampleFamilyUtil.class.getName() + "." + methodName + sb + ";";
        methodPart.add(result + expr);
        methodPart.add("stack.push(" + SampleFamilyValue.class.getName() + ".as(" + resultId + "));");
    }

    private String handleClosure(String closure) {
        ClosureLexer closureLexer = new ClosureLexer(CharStreams.fromString(closure));
        CommonTokenStream tokens = new CommonTokenStream(closureLexer);

        ClosureParser closureParser = new ClosureParser(tokens);
        ClosureParser.ClosureContext context = closureParser.closure();
        ClosureVisitor closureVisitor = new ClosureVisitor();
        ClosureAnalyseResult result = closureVisitor.analyse(context);
        String name = generateClosureClassName();
        MALClassFileGenerator.generateClosureClass(generatedPath, name, result);
        return name;
    }

    public void generateClassFile(Path generatedPath, String className) {
        MALClassFileGenerator.generate(generatedPath, className, paramPart, methodPart);
    }

    private String getRandomIdentifierString() {
        return "var" + variableCount.getAndAdd(1);
    }

    private void handleBinaryFunction(MALUnit unit) {
        String name = unit.getName();
        String id1 = getRandomIdentifierString();
        String id2 = getRandomIdentifierString();

        methodPart.add(Value.class.getName() + " " + id1 + " = stack" + ".pop();");
        methodPart.add(Value.class.getName() + " " + id2 + " = stack" + ".pop();");

        String expr = null;

        switch (name) {
            case "+":
                expr = id1 + ".plus(" + id2 + ");";
                break;
            case "-":
                expr = id1 + ".minus(" + id2 + ")";
                break;
            case "*":
                expr = id1 + ".multiply(" + id2 + ")";
                break;
            case "/":
                expr = id1 + ".div(" + id2 + ")";
                break;
            default:
                throw new UnexpectedException("unexpected char" + name);
        }
        String resId = getRandomIdentifierString();
        String resultDec = Value.class.getName() + " " + resId + " = " + expr + ";";
        methodPart.add(resultDec);
        methodPart.add("stack.push(" + resId + ");");
    }

    private void handleSampleFamily(String name) {
        methodPart.add(getSampleFamilyFromMap(name));
    }

    private String getSampleFamilyFromMap(String name) {
        return "stack.push(org.apache.skywalking.mal.rt.entity.SampleFamilyValue.as(sampleFamilyMap.get( \"" + name + "\")));";
    }

    private String getScalar(Double v) {
        return "stack.push(org.apache.skywalking.mal.rt.entity.ScalarValue.as(" + v + "));";
    }


    public List<String> getMethodPart() {
        return methodPart;
    }

    public List<String> getParamPart() {
        return paramPart;
    }


    private String generateClosureClassName() {
        return "Closure" + closureClassNameCount.getAndAdd(1);
    }

}
