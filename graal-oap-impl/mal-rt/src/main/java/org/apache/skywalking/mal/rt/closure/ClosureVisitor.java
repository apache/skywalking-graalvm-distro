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

package org.apache.skywalking.mal.rt.closure;

import org.apache.skywalking.mal.rt.closure.entity.ClosureAnalyseResult;
import org.apache.skywalking.mal.rt.grammar.ClosureParser;
import org.apache.skywalking.mal.rt.grammar.ClosureParserBaseVisitor;
import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;
import org.apache.skywalking.oap.server.core.UnexpectedException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClosureVisitor extends ClosureParserBaseVisitor<String> {

    private final ClosureAnalyseResult result = new ClosureAnalyseResult();

    private boolean leftOperator = false;

    private boolean leftOperatorIsMap = false;

    private static final String DEFAULT_TYPE = "String";

    private static final Map<String, String> staticCallClass2FullName;

    static {
        staticCallClass2FullName = new HashMap<>();
        staticCallClass2FullName.put("ProcessRegistry", ProcessRegistry.class.getName());
    }

    public ClosureAnalyseResult analyse(ClosureParser.ClosureContext ctx) {
        visitClosure(ctx);
        return result;
    }
    @Override
    public String visitClosure(ClosureParser.ClosureContext ctx) {
        if (ctx.closureParam() != null) {
            visit(ctx.closureParam());

        }
        String visit = visit(ctx.closureContent());
        result.setExprs(visit);
        //todo: class
        return visit;
    }

    @Override
    public String visitClosureParam(ClosureParser.ClosureParamContext ctx) {
        if (ctx.idList() != null) {
            result.setParams(ctx.idList().IDENTIFIER()
                    .stream()
                    .map(id -> new ClosureAnalyseResult.KeyValuePair(id.getText(), DEFAULT_TYPE))
                    .collect(Collectors.toList())
            );
            return null;
        }

        if (ctx.varDeclareList() != null) {
            result.setParams(ctx.varDeclareList().varDeclare()
                    .stream()
                    .map(dec -> new ClosureAnalyseResult.KeyValuePair(dec.IDENTIFIER(1).getText(), dec.IDENTIFIER(0).getText()))
                    .collect(Collectors.toList()));
            return null;
        }

        throw new UnexpectedException("should not reach here");
    }

    @Override
    public String visitClosureContent(ClosureParser.ClosureContentContext ctx) {
        StringBuilder sb = new StringBuilder();
        List<ClosureParser.StatContext> stat = ctx.stat();
        for (ClosureParser.StatContext s: stat) {
            sb.append(visit(s));
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public String visitStat(ClosureParser.StatContext ctx) {
        if (ctx.varDeclare() != null) {
           return visit(ctx.varDeclare()) + ";";
        }

        if (ctx.IF() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("if (");
            String ifCondition = visit(ctx.ifCondition);
            sb.append(ifCondition);
            sb.append(") ");
            sb.append(visit(ctx.block(0)));
            int i = 1;
            for (ClosureParser.GroovyExprContext context: ctx.elseIFCondition) {
                sb.append("else if(");
                sb.append(visit(context));
                sb.append(visit(ctx.block(i)));
                i++;
            }
            if (ctx.ELSE() != null) {
                sb.append("else");
                sb.append(visit(ctx.block(ctx.block().size() - 1)));
            }
            return sb.toString();
        }

        if (ctx.RETURN() != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("return ");
            if (checkCollection(ctx.groovyExpr())) {
                sb.append(visit(ctx.groovyExpr(0)));
            } else {
                sb.append("null");
            }
            sb.append(";");
            return sb.toString();
        }

        if (ctx.EQUAL() != null) {
            this.leftOperator = true;
            String left = visit(ctx.groovyExpr(0));
            this.leftOperator = false;
            String right = visit(ctx.groovyExpr(1));
            if (leftOperatorIsMap) {
                leftOperatorIsMap = false;
                return left + right + ");";
            }
            return left + " = " + right + ";";
        }

        return visit(ctx.groovyExpr(0)) + ";";
    }

    @Override
    public String visitBlock(ClosureParser.BlockContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (ClosureParser.StatContext context: ctx.stat()) {
            sb.append(visit(context));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String visitVarDeclare(ClosureParser.VarDeclareContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.IDENTIFIER(0).getText());
        sb.append(" ");
        sb.append(sb.append(ctx.IDENTIFIER(1).getText()));
        if (ctx.groovyExpr() != null) {
            sb.append(" = ");
            sb.append(visit(ctx.groovyExpr()));
        }
        return sb.toString();
    }

    @Override
    public String visitGroovyExpr(ClosureParser.GroovyExprContext ctx) {
        if (ctx.propertyCall != null) {
            String arrayName = ctx.IDENTIFIER(0).getText();
            String indexName = ctx.IDENTIFIER(1).getText();
            indexName = "\"" + indexName + "\"";
            if (leftOperator) {
                leftOperatorIsMap = true;
                return arrayName + ".put(" + indexName + ", ";
            }
            return arrayName + ".get(" + indexName + ")";
        }

        if (ctx.staticCall != null) {
            String method = visit(ctx.groovyMethodCall());
            return staticCallClass2FullName.get(ctx.CLASS_NAME().getText()) + "." + method;
        }

        if (ctx.methodCall != null) {
            String caller = visit(ctx.groovyExpr(0));
            String method = visit(ctx.groovyMethodCall());
            return caller + "." + method;
        }

        if (ctx.simpleMethodCall != null) {
            return visit(ctx.groovyMethodCall());
        }

        if (ctx.arrayCall != null) {
            String arrayName = ctx.IDENTIFIER(0).getText();
            String indexName = visit(ctx.groovyExpr(0));
            if (indexName.startsWith("'")) {
                return arrayName + "[" + indexName + "]";
            }
            if (leftOperator) {
                leftOperatorIsMap = true;
                return arrayName + ".put(" + indexName + ", ";
            }
            return arrayName + ".get(" + indexName + ")";
        }

        if (ctx.op != null) {
            String left = visit(ctx.groovyExpr(0));
            String right = visit(ctx.groovyExpr(1));
            if (ctx.op.getText().equals("==")) {
                return left + ".equals(" + right + ")";
            }
            return left + ctx.op.getText() + right;
        }

        if (ctx.NOT() != null) {
            return ctx.NOT().getText() + ctx.groovyExpr(0).getText();
        }

        if (ctx.OPEN_PAREN() != null) {
            return "(" + ctx.groovyExpr(0).getText() + ")";
        }

        if (ctx.STRING() != null) {
            String text = ctx.STRING().getText();
            return "\"" + text.substring(1, text.length() - 1) + "\"";
        }

        return ctx.getText();
    }

    @Override
    public String visitGroovyMethodCall(ClosureParser.GroovyMethodCallContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.IDENTIFIER().getText());
        sb.append("(");
        if (ctx.groovyParamList() != null) {
            sb.append(visit(ctx.groovyParamList()));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String visitGroovyParamList(ClosureParser.GroovyParamListContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (checkCollection(ctx.groovyExpr())) {
            ctx.groovyExpr().forEach(
                    groovyExprContext -> {
                        sb.append(visit(groovyExprContext));
                        sb.append(",");
                    }
            );
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private boolean checkCollection(Collection c) {
        return c != null && !c.isEmpty();
    }
}
