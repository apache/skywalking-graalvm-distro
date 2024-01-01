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

package org.apache.skywalking.mal.rt.core;

import org.apache.skywalking.mal.rt.core.entity.MALParam;
import org.apache.skywalking.mal.rt.core.entity.MALUnit;
import org.apache.skywalking.mal.rt.grammar.MALCoreParser;
import org.apache.skywalking.mal.rt.grammar.MALCoreParserBaseVisitor;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.Layer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MALVisitor extends MALCoreParserBaseVisitor<List<MALUnit>> {
    @Override
    public List<MALUnit> visitMalExpression(MALCoreParser.MalExpressionContext ctx) {
        return visit(ctx.mainExpr());
    }

    @Override
    public List<MALUnit> visitMainExpr(MALCoreParser.MainExprContext ctx) {
        if (ctx.DOT() != null) {
            List<MALUnit> left = visit(ctx.mainExpr(0));
            List<MALUnit> right = visit(ctx.methodCall());

            left.add(right.get(0));
            return left;
        }
        if (ctx.op != null) {
            List<MALUnit> left = visit(ctx.mainExpr(0));
            List<MALUnit> right = visit(ctx.mainExpr(1));
            left.addAll(right);
            left.add(MALUnit.createSimpleData(ctx.op.getText(), MALUnit.UnitType.BINARY_FUNCTION));
            return left;
        }

        if (ctx.IDENTIFIER() != null) {
            List<MALUnit> list = new ArrayList<>();
            list.add(MALUnit.createSimpleData(ctx.IDENTIFIER().getText(), MALUnit.UnitType.SAMPLE_FAMILY));
            return list;
        }

        if (ctx.NUMBER() != null) {
            List<MALUnit> list = new ArrayList<>();
            list.add(MALUnit.createSimpleData(ctx.NUMBER().getText(), MALUnit.UnitType.SCALAR));
            return list;
        }

        if (ctx.OPEN_PAREN() != null) {
            List<MALUnit> list = visit(ctx.mainExpr(0));
            list.add(MALUnit.createSimpleData("()", MALUnit.UnitType.UNARY_FUNCTION));
            return list;
        }

        throw new UnexpectedException("should not reach here");
    }

    @Override
    public List<MALUnit> visitMethodCall(MALCoreParser.MethodCallContext ctx) {

        String methodName = ctx.IDENTIFIER().getText();

        MALUnit malUnit = new MALUnit(methodName, null, MALUnit.UnitType.UNARY_FUNCTION);
        if (ctx.paramList() != null) {
            List<MALCoreParser.ParamContext> param = ctx.paramList().param();
            List<MALParam> params = parseParams(param);
            malUnit.setArguments(params);
        }
        return List.of(malUnit);
    }

    private List<MALParam> parseParams(List<MALCoreParser.ParamContext> params) {
        return params.stream().map(
                this::parseParam
        ).collect(Collectors.toList());
    }

    private MALParam parseParam(MALCoreParser.ParamContext param) {
        String text = param.getText();
        if (param.LAYER() != null) {
            return new MALParam(name2Layer(text), MALParam.ParamType.LAYER);
        }

        if (param.NUMBER() != null) {
            return new MALParam(Double.parseDouble(text), MALParam.ParamType.NUMBER);
        }

        if (param.closure() != null) {
            return new MALParam(parseClosure(param.closure()), MALParam.ParamType.CLOSURE);
        }

        if (param.stringList() != null) {
            List<String> list = new ArrayList<>();
            param.stringList().STRING().forEach(
                    str -> list.add(str.getText().substring(1, str.getText().length() - 1))
            );
            return new MALParam(list, MALParam.ParamType.STRING_LIST);
        }

        if (param.K8sRetagType() != null) {
            return new MALParam(param.K8sRetagType().getText(), MALParam.ParamType.K8S_RETAG_TYPE);
        }

        if (param.percentiles() != null) {
            List<Integer> list = new ArrayList<>();
            param.percentiles().NUMBER().forEach(
                    number -> list.add(Integer.parseInt(number.getText()))
            );
            return new MALParam(list, MALParam.ParamType.PERCENTILES);
        }

        if (param.STRING() != null) {
            return new MALParam(param.STRING().getText(), MALParam.ParamType.STRING);
        }

        if (param.DOWNSAMPLING_TYPE() != null) {
            return new MALParam(param.DOWNSAMPLING_TYPE().getText(), MALParam.ParamType.DOWNSAMPLING_TYPE);
        }

        throw new UnexpectedException("should not reach here");
    }

    private String parseClosure(MALCoreParser.ClosureContext ctx) {
        return ctx.getText();
    }


    private Layer name2Layer(String name) {
        return Layer.nameOf(name.substring(name.indexOf(".") + 1));
    }
}
