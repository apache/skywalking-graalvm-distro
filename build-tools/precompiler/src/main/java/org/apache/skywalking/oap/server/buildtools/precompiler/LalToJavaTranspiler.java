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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.syntax.Types;

/**
 * Transpiles LAL (Log Analysis Language) Groovy scripts to Java source code at build time.
 * Parses DSL strings into Groovy AST (via CompilationUnit at CONVERSION phase),
 * walks AST nodes, and produces Java classes implementing LalExpression.
 *
 * <p>See LAL-TRANSPILER-DESIGN.md for the full AST-to-Java mapping.
 */
@Slf4j
public class LalToJavaTranspiler {

    static final String GENERATED_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.lal";

    /** DSL method names that take a Closure and map to Consumer overloads. */
    private static final Set<String> CONSUMER_METHODS = Set.of(
        "text", "extractor", "sink", "slowSql", "sampledTrace", "metrics", "sampler"
    );

    /** DSL method names called on the extractor spec (not on filterSpec). */
    private static final Set<String> EXTRACTOR_METHODS = Set.of(
        "service", "instance", "endpoint", "tag", "traceId", "segmentId",
        "spanId", "timestamp", "layer", "slowSql", "sampledTrace", "metrics"
    );

    /** DSL method names called on the sink spec. */
    private static final Set<String> SINK_METHODS = Set.of(
        "sampler", "enforcer", "dropper"
    );

    /** Static class names used in LAL scripts. */
    private static final Set<String> STATIC_TYPES = Set.of("ProcessRegistry");

    // ---- Batch state ----

    private final Map<String, String> lalSources = new LinkedHashMap<>();
    private final Map<String, String> hashToClass = new LinkedHashMap<>();

    private int tempVarCounter;

    /**
     * Transpile a LAL DSL script to a Java class implementing LalExpression.
     *
     * @param className  simple class name (e.g. "LalExpr_3")
     * @param dslText    the Groovy DSL string (filter { ... })
     * @return Java source code
     */
    public String transpile(String className, String dslText) {
        tempVarCounter = 0;
        ModuleNode module = parseToAST(dslText);
        Statement body = extractBody(module);

        StringBuilder sb = new StringBuilder();
        emitHeader(sb, className);

        // The top-level body should be a filter { ... } call.
        // Unwrap it and emit the filter body directly.
        List<Statement> stmts = flattenStatements(body);
        for (Statement stmt : stmts) {
            emitStatement(sb, stmt, "filterSpec", "binding", 2);
        }

        emitFooter(sb);
        return sb.toString();
    }

    public void register(String className, String hash, String source) {
        lalSources.put(className, source);
        hashToClass.put(hash, GENERATED_PACKAGE + "." + className);
    }

    // ---- AST Parsing ----

    ModuleNode parseToAST(String expression) {
        CompilerConfiguration cc = new CompilerConfiguration();
        CompilationUnit cu = new CompilationUnit(cc);
        cu.addSource("Script", expression);
        cu.compile(Phases.CONVERSION);
        List<ModuleNode> modules = cu.getAST().getModules();
        if (modules.isEmpty()) {
            throw new IllegalStateException("No AST modules produced");
        }
        return modules.get(0);
    }

    Statement extractBody(ModuleNode module) {
        BlockStatement block = module.getStatementBlock();
        if (block != null && !block.getStatements().isEmpty()) {
            return block;
        }
        throw new IllegalStateException("Empty AST body");
    }

    // ---- Code Generation ----

    private void emitHeader(StringBuilder sb, String className) {
        sb.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        sb.append("import java.util.Map;\n");
        sb.append("import org.apache.skywalking.oap.log.analyzer.dsl.Binding;\n");
        sb.append("import org.apache.skywalking.oap.log.analyzer.dsl.LalExpression;\n");
        sb.append("import org.apache.skywalking.oap.log.analyzer.dsl.spec.filter.FilterSpec;\n\n");
        sb.append("@SuppressWarnings(\"unchecked\")\n");
        sb.append("public class ").append(className).append(" implements LalExpression {\n\n");
        emitHelpers(sb);
        sb.append("    @Override\n");
        sb.append("    public void execute(FilterSpec filterSpec, Binding binding) {\n");
    }

    private void emitHelpers(StringBuilder sb) {
        sb.append("    private static Object getAt(Object obj, String key) {\n");
        sb.append("        if (obj == null) return null;\n");
        sb.append("        if (obj instanceof Binding.Parsed) return ((Binding.Parsed) obj).getAt(key);\n");
        sb.append("        if (obj instanceof Map) return ((Map<String, Object>) obj).get(key);\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        sb.append("    private static long toLong(Object obj) {\n");
        sb.append("        if (obj instanceof Number) return ((Number) obj).longValue();\n");
        sb.append("        if (obj instanceof String) return Long.parseLong((String) obj);\n");
        sb.append("        return 0L;\n");
        sb.append("    }\n\n");

        sb.append("    private static int toInt(Object obj) {\n");
        sb.append("        if (obj instanceof Number) return ((Number) obj).intValue();\n");
        sb.append("        if (obj instanceof String) return Integer.parseInt((String) obj);\n");
        sb.append("        return 0;\n");
        sb.append("    }\n\n");

        sb.append("    private static boolean toBoolean(Object obj) {\n");
        sb.append("        if (obj instanceof Boolean) return (Boolean) obj;\n");
        sb.append("        if (obj instanceof String) return Boolean.parseBoolean((String) obj);\n");
        sb.append("        return obj != null;\n");
        sb.append("    }\n\n");

        sb.append("    private static boolean isTruthy(Object obj) {\n");
        sb.append("        if (obj == null) return false;\n");
        sb.append("        if (obj instanceof Boolean) return (Boolean) obj;\n");
        sb.append("        if (obj instanceof String) return !((String) obj).isEmpty();\n");
        sb.append("        if (obj instanceof Number) return ((Number) obj).doubleValue() != 0;\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");

        sb.append("    private static boolean isNonEmptyString(Object obj) {\n");
        sb.append("        if (obj == null) return false;\n");
        sb.append("        String s = obj.toString();\n");
        sb.append("        return s != null && !s.trim().isEmpty();\n");
        sb.append("    }\n\n");
    }

    private void emitFooter(StringBuilder sb) {
        sb.append("    }\n");
        sb.append("}\n");
    }

    private void emitStatement(StringBuilder sb, Statement stmt,
                               String receiver, String bindingVar, int indent) {
        if (stmt instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) stmt).getStatements()) {
                emitStatement(sb, s, receiver, bindingVar, indent);
            }
        } else if (stmt instanceof ExpressionStatement) {
            emitExpressionStatement(sb, ((ExpressionStatement) stmt).getExpression(),
                receiver, bindingVar, indent);
        } else if (stmt instanceof IfStatement) {
            emitIfStatement(sb, (IfStatement) stmt, receiver, bindingVar, indent);
        } else if (stmt instanceof EmptyStatement) {
            // skip
        } else {
            throw new UnsupportedOperationException(
                "Unsupported statement: " + stmt.getClass().getSimpleName());
        }
    }

    private void emitExpressionStatement(StringBuilder sb, Expression expr,
                                         String receiver, String bindingVar, int indent) {
        if (expr instanceof MethodCallExpression) {
            emitMethodCall(sb, (MethodCallExpression) expr, receiver, bindingVar, indent);
        } else if (expr instanceof BinaryExpression) {
            // Named argument syntax: tag key: val → emitted as tag(Map.of(key, val))
            emitBinaryAsNamedArg(sb, (BinaryExpression) expr, receiver, bindingVar, indent);
        } else {
            throw new UnsupportedOperationException(
                "Unsupported expression statement: " + expr.getClass().getSimpleName()
                    + " (" + expr.getText() + ")");
        }
    }

    private void emitMethodCall(StringBuilder sb, MethodCallExpression mce,
                                String receiver, String bindingVar, int indent) {
        String methodName = mce.getMethodAsString();
        Expression objExpr = mce.getObjectExpression();
        ArgumentListExpression args = toArgList(mce.getArguments());
        List<Expression> argExprs = args.getExpressions();

        // Top-level filter { ... } → unwrap and emit body
        if ("filter".equals(methodName) && isThisOrImplicit(objExpr)) {
            if (!argExprs.isEmpty() && argExprs.get(0) instanceof ClosureExpression) {
                ClosureExpression closure = (ClosureExpression) argExprs.get(0);
                emitStatement(sb, closure.getCode(), receiver, bindingVar, indent);
                return;
            }
        }

        // json {} → filterSpec.json() (no-arg, empty closure)
        if ("json".equals(methodName) && isThisOrImplicit(objExpr)) {
            indent(sb, indent);
            sb.append(receiver).append(".json();\n");
            return;
        }

        // text { regexp /pattern/ } → filterSpec.text(tp -> tp.regexp("pattern"))
        if ("text".equals(methodName) && isThisOrImplicit(objExpr)
                && !argExprs.isEmpty() && argExprs.get(0) instanceof ClosureExpression) {
            ClosureExpression closure = (ClosureExpression) argExprs.get(0);
            String tpVar = "tp";
            indent(sb, indent);
            sb.append(receiver).append(".text(").append(tpVar).append(" -> {\n");
            emitStatement(sb, closure.getCode(), tpVar, bindingVar, indent + 1);
            indent(sb, indent);
            sb.append("});\n");
            return;
        }

        // regexp /pattern/ → tp.regexp("pattern")
        if ("regexp".equals(methodName) && isThisOrImplicit(objExpr)) {
            indent(sb, indent);
            sb.append(receiver).append(".regexp(");
            if (!argExprs.isEmpty()) {
                sb.append(visitValueExpression(argExprs.get(0), bindingVar));
            }
            sb.append(");\n");
            return;
        }

        // Consumer overload methods: extractor, sink, slowSql, sampledTrace, metrics, sampler
        if (CONSUMER_METHODS.contains(methodName) && isThisOrImplicit(objExpr)
                && !argExprs.isEmpty() && argExprs.get(0) instanceof ClosureExpression) {
            ClosureExpression closure = (ClosureExpression) argExprs.get(0);
            String lambdaVar = lambdaVarFor(methodName);
            String childReceiver = childReceiverFor(methodName, receiver, lambdaVar);
            indent(sb, indent);
            sb.append(receiver).append(".").append(methodName).append("(")
              .append(lambdaVar).append(" -> {\n");
            emitStatement(sb, closure.getCode(), childReceiver, bindingVar, indent + 1);
            indent(sb, indent);
            sb.append("});\n");
            return;
        }

        // rateLimit("${expr}") { rpm N } → sp.rateLimit(idExpr, rls -> rls.rpm(N))
        if ("rateLimit".equals(methodName) && isThisOrImplicit(objExpr)) {
            Expression idArg = argExprs.get(0);
            ClosureExpression closure = argExprs.size() > 1
                && argExprs.get(1) instanceof ClosureExpression
                ? (ClosureExpression) argExprs.get(1) : null;

            indent(sb, indent);
            sb.append(receiver).append(".rateLimit(")
              .append(visitValueExpression(idArg, bindingVar));
            if (closure != null) {
                String rlsVar = "rls";
                sb.append(", ").append(rlsVar).append(" -> {\n");
                emitStatement(sb, closure.getCode(), rlsVar, bindingVar, indent + 1);
                indent(sb, indent);
                sb.append("}");
            }
            sb.append(");\n");
            return;
        }

        // abort {} → filterSpec.abort()
        if ("abort".equals(methodName) && isThisOrImplicit(objExpr)) {
            indent(sb, indent);
            sb.append(receiver).append(".abort();\n");
            return;
        }

        // enforcer {} or dropper {} → sink.enforcer() / sink.dropper()
        if (("enforcer".equals(methodName) || "dropper".equals(methodName))
                && isThisOrImplicit(objExpr)) {
            indent(sb, indent);
            sb.append(receiver).append(".").append(methodName).append("();\n");
            return;
        }

        // tag("KEY") → receiver.tag("KEY") (returns String)
        // This is handled in visitValueExpression for use as condition value.
        // When used as a statement: just call it.
        if ("tag".equals(methodName) && isThisOrImplicit(objExpr)
                && !argExprs.isEmpty() && argExprs.get(0) instanceof ConstantExpression) {
            // tag("KEY") as standalone statement (rare, but possible)
            indent(sb, indent);
            sb.append(receiver).append(".tag(\"")
              .append(MalToJavaTranspiler.escapeJava(argExprs.get(0).getText()))
              .append("\");\n");
            return;
        }

        // Simple value-setting methods on extractor/sink/sampledTrace/slowSql/metrics:
        // service(val), layer(val), timestamp(val), id(val), statement(val),
        // latency(val), uri(val), reason(val), processId(val), destProcessId(val),
        // detectPoint(val), componentId(val), rpm(val), name(val), value(val), etc.
        if (isThisOrImplicit(objExpr)) {
            indent(sb, indent);
            sb.append(receiver).append(".").append(methodName).append("(");
            for (int i = 0; i < argExprs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(visitValueExpression(argExprs.get(i), bindingVar));
            }
            sb.append(");\n");
            return;
        }

        // Static method: ProcessRegistry.generateVirtualLocalProcess(...)
        if (objExpr instanceof VariableExpression
                && STATIC_TYPES.contains(((VariableExpression) objExpr).getName())) {
            String typeName = ((VariableExpression) objExpr).getName();
            indent(sb, indent);
            sb.append("org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry.")
              .append(methodName).append("(");
            for (int i = 0; i < argExprs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(visitValueExpression(argExprs.get(i), bindingVar));
            }
            sb.append(");\n");
            return;
        }

        throw new UnsupportedOperationException(
            "Unsupported method call: " + methodName + " on " + objExpr.getClass().getSimpleName()
                + " (" + mce.getText() + ")");
    }

    /**
     * Handle Groovy named argument syntax: tag key: val
     * In the AST, this becomes BinaryExpression[left=MethodCall(tag), right=MapEntry...]
     * Actually, Groovy parses `tag key: val` as `tag(key: val)` → MethodCall with MapExpression arg.
     * But `tag level: parsed.level` may also parse as a BinaryExpression depending on context.
     */
    private void emitBinaryAsNamedArg(StringBuilder sb, BinaryExpression expr,
                                      String receiver, String bindingVar, int indent) {
        throw new UnsupportedOperationException(
            "Unsupported binary expression as statement: " + expr.getText());
    }

    // ---- If/Else ----

    private void emitIfStatement(StringBuilder sb, IfStatement ifStmt,
                                 String receiver, String bindingVar, int indent) {
        indent(sb, indent);
        sb.append("if (");
        sb.append(visitCondition(ifStmt.getBooleanExpression(), bindingVar));
        sb.append(") {\n");

        emitStatement(sb, ifStmt.getIfBlock(), receiver, bindingVar, indent + 1);

        Statement elseBlock = ifStmt.getElseBlock();
        if (elseBlock != null && !(elseBlock instanceof EmptyStatement)) {
            indent(sb, indent);
            if (elseBlock instanceof IfStatement) {
                sb.append("} else ");
                // Emit else-if without extra indent
                emitIfStatementInline(sb, (IfStatement) elseBlock, receiver, bindingVar, indent);
                return;
            } else {
                sb.append("} else {\n");
                emitStatement(sb, elseBlock, receiver, bindingVar, indent + 1);
                indent(sb, indent);
                sb.append("}\n");
            }
        } else {
            indent(sb, indent);
            sb.append("}\n");
        }
    }

    private void emitIfStatementInline(StringBuilder sb, IfStatement ifStmt,
                                       String receiver, String bindingVar, int indent) {
        sb.append("if (");
        sb.append(visitCondition(ifStmt.getBooleanExpression(), bindingVar));
        sb.append(") {\n");

        emitStatement(sb, ifStmt.getIfBlock(), receiver, bindingVar, indent + 1);

        Statement elseBlock = ifStmt.getElseBlock();
        if (elseBlock != null && !(elseBlock instanceof EmptyStatement)) {
            indent(sb, indent);
            if (elseBlock instanceof IfStatement) {
                sb.append("} else ");
                emitIfStatementInline(sb, (IfStatement) elseBlock, receiver, bindingVar, indent);
            } else {
                sb.append("} else {\n");
                emitStatement(sb, elseBlock, receiver, bindingVar, indent + 1);
                indent(sb, indent);
                sb.append("}\n");
            }
        } else {
            indent(sb, indent);
            sb.append("}\n");
        }
    }

    // ---- Condition Visiting ----

    private String visitCondition(BooleanExpression boolExpr, String bindingVar) {
        return visitConditionExpr(boolExpr.getExpression(), bindingVar);
    }

    private String visitConditionExpr(Expression expr, String bindingVar) {
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            int op = bin.getOperation().getType();

            if (op == Types.COMPARE_EQUAL) {
                String left = visitValueExpression(bin.getLeftExpression(), bindingVar);
                String right = visitValueExpression(bin.getRightExpression(), bindingVar);
                // String equality: use .equals()
                if (bin.getRightExpression() instanceof ConstantExpression
                        && ((ConstantExpression) bin.getRightExpression()).getValue() instanceof String) {
                    return "\"" + MalToJavaTranspiler.escapeJava(
                        (String) ((ConstantExpression) bin.getRightExpression()).getValue())
                        + "\".equals(" + left + ")";
                }
                return "java.util.Objects.equals(" + left + ", " + right + ")";
            }

            if (op == Types.COMPARE_NOT_EQUAL) {
                String left = visitValueExpression(bin.getLeftExpression(), bindingVar);
                String right = visitValueExpression(bin.getRightExpression(), bindingVar);
                if (bin.getRightExpression() instanceof ConstantExpression
                        && ((ConstantExpression) bin.getRightExpression()).getValue() instanceof String) {
                    return "!\"" + MalToJavaTranspiler.escapeJava(
                        (String) ((ConstantExpression) bin.getRightExpression()).getValue())
                        + "\".equals(" + left + ")";
                }
                return "!java.util.Objects.equals(" + left + ", " + right + ")";
            }

            if (op == Types.COMPARE_LESS_THAN) {
                String left = visitValueExpression(bin.getLeftExpression(), bindingVar);
                String right = visitValueExpression(bin.getRightExpression(), bindingVar);
                return "toInt(" + left + ") < " + right;
            }

            if (op == Types.COMPARE_GREATER_THAN_EQUAL) {
                String left = visitValueExpression(bin.getLeftExpression(), bindingVar);
                String right = visitValueExpression(bin.getRightExpression(), bindingVar);
                return "toInt(" + left + ") >= " + right;
            }

            if (op == Types.LOGICAL_AND) {
                return visitConditionExpr(bin.getLeftExpression(), bindingVar)
                    + " && " + visitConditionExpr(bin.getRightExpression(), bindingVar);
            }

            if (op == Types.LOGICAL_OR) {
                return visitConditionExpr(bin.getLeftExpression(), bindingVar)
                    + " || " + visitConditionExpr(bin.getRightExpression(), bindingVar);
            }

            throw new UnsupportedOperationException(
                "Unsupported condition operator: " + bin.getOperation().getText());
        }

        if (expr instanceof NotExpression) {
            String inner = visitConditionExpr(((NotExpression) expr).getExpression(), bindingVar);
            return "!" + inner;
        }

        if (expr instanceof BooleanExpression) {
            return visitConditionExpr(((BooleanExpression) expr).getExpression(), bindingVar);
        }

        if (expr instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) expr;
            String methodName = mce.getMethodAsString();
            // parsed?.commonProperties?.responseFlags?.toString()?.trim()
            // → safe navigation chain
            if ("toString".equals(methodName) || "trim".equals(methodName)) {
                String obj = visitValueExpression(mce.getObjectExpression(), bindingVar);
                return "isNonEmptyString(" + obj + ")";
            }
            return visitValueExpression(expr, bindingVar);
        }

        // Truthiness check: a variable/property used as condition
        return "isTruthy(" + visitValueExpression(expr, bindingVar) + ")";
    }

    // ---- Value Expression Visiting ----

    String visitValueExpression(Expression expr, String bindingVar) {
        if (expr instanceof ConstantExpression) {
            return visitConstant((ConstantExpression) expr);
        }

        if (expr instanceof VariableExpression) {
            return visitVariable((VariableExpression) expr, bindingVar);
        }

        if (expr instanceof PropertyExpression) {
            return visitProperty((PropertyExpression) expr, bindingVar);
        }

        if (expr instanceof CastExpression) {
            return visitCast((CastExpression) expr, bindingVar);
        }

        if (expr instanceof MethodCallExpression) {
            return visitMethodCallValue((MethodCallExpression) expr, bindingVar);
        }

        if (expr instanceof GStringExpression) {
            return visitGString((GStringExpression) expr, bindingVar);
        }

        if (expr instanceof MapExpression) {
            return visitMapExpression((MapExpression) expr, bindingVar);
        }

        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            int op = bin.getOperation().getType();
            if (op == Types.COMPARE_EQUAL || op == Types.COMPARE_NOT_EQUAL
                    || op == Types.LOGICAL_AND || op == Types.LOGICAL_OR
                    || op == Types.COMPARE_LESS_THAN || op == Types.COMPARE_GREATER_THAN_EQUAL) {
                return visitConditionExpr(expr, bindingVar);
            }
        }

        if (expr instanceof NotExpression) {
            return "!" + visitValueExpression(((NotExpression) expr).getExpression(), bindingVar);
        }

        throw new UnsupportedOperationException(
            "Unsupported value expression: " + expr.getClass().getSimpleName()
                + " (" + expr.getText() + ")");
    }

    private String visitConstant(ConstantExpression expr) {
        Object value = expr.getValue();
        if (value instanceof String) {
            return "\"" + MalToJavaTranspiler.escapeJava((String) value) + "\"";
        }
        if (value instanceof Integer) {
            return value.toString();
        }
        if (value instanceof Long) {
            return value + "L";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Double) {
            return value + "d";
        }
        if (value == null) {
            return "null";
        }
        return value.toString();
    }

    private String visitVariable(VariableExpression expr, String bindingVar) {
        String name = expr.getName();
        if ("parsed".equals(name)) {
            return bindingVar + ".parsed()";
        }
        if ("log".equals(name)) {
            return bindingVar + ".log()";
        }
        if ("this".equals(name)) {
            return "filterSpec";
        }
        if (STATIC_TYPES.contains(name)) {
            return "org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry";
        }
        // Unknown variable — may be a local binding variable
        return name;
    }

    private String visitProperty(PropertyExpression expr, String bindingVar) {
        Expression objExpr = expr.getObjectExpression();
        String propName = expr.getPropertyAsString();
        boolean isSafe = expr.isSafe();

        // parsed.field → binding.parsed().getAt("field")
        // parsed?.response?.responseCode?.value → null-safe chain
        String obj = visitValueExpression(objExpr, bindingVar);

        // log.service → binding.log().getService()
        if (objExpr instanceof VariableExpression
                && "log".equals(((VariableExpression) objExpr).getName())) {
            return visitLogProperty(propName, bindingVar);
        }

        // For parsed access and nested map access, use getAt()
        if (isSafe) {
            String tmp = nextTempVar();
            // We can't emit multi-line expressions here; use inline ternary
            return "(" + obj + " == null ? null : getAt(" + obj + ", \"" + propName + "\"))";
        }

        return "getAt(" + obj + ", \"" + propName + "\")";
    }

    private String visitLogProperty(String propName, String bindingVar) {
        switch (propName) {
            case "service":
                return bindingVar + ".log().getService()";
            case "serviceInstance":
                return bindingVar + ".log().getServiceInstance()";
            case "endpoint":
                return bindingVar + ".log().getEndpoint()";
            case "timestamp":
                return bindingVar + ".log().getTimestamp()";
            default:
                return bindingVar + ".log().get" + capitalize(propName) + "()";
        }
    }

    private String visitCast(CastExpression expr, String bindingVar) {
        String inner = visitValueExpression(expr.getExpression(), bindingVar);
        String typeName = expr.getType().getName();

        switch (typeName) {
            case "java.lang.String":
            case "String":
                return "String.valueOf(" + inner + ")";
            case "java.lang.Long":
            case "Long":
            case "long":
                return "toLong(" + inner + ")";
            case "java.lang.Integer":
            case "Integer":
            case "int":
                return "toInt(" + inner + ")";
            case "java.lang.Boolean":
            case "Boolean":
            case "boolean":
                return "toBoolean(" + inner + ")";
            default:
                return "((" + typeName + ") " + inner + ")";
        }
    }

    private String visitMethodCallValue(MethodCallExpression mce, String bindingVar) {
        String methodName = mce.getMethodAsString();
        Expression objExpr = mce.getObjectExpression();
        ArgumentListExpression args = toArgList(mce.getArguments());
        boolean isSafe = mce.isSafe();

        // tag("KEY") on filterSpec → filterSpec.tag("KEY")
        if ("tag".equals(methodName) && isThisOrImplicit(objExpr)) {
            List<Expression> argExprs = args.getExpressions();
            if (!argExprs.isEmpty() && argExprs.get(0) instanceof ConstantExpression) {
                return "filterSpec.tag(\""
                    + MalToJavaTranspiler.escapeJava(argExprs.get(0).getText()) + "\")";
            }
        }

        // ProcessRegistry.generateVirtualLocalProcess(...)
        if (objExpr instanceof VariableExpression
                && STATIC_TYPES.contains(((VariableExpression) objExpr).getName())) {
            StringBuilder sb = new StringBuilder();
            sb.append("org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry.");
            sb.append(methodName).append("(");
            List<Expression> argExprs = args.getExpressions();
            for (int i = 0; i < argExprs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(visitValueExpression(argExprs.get(i), bindingVar));
            }
            sb.append(")");
            return sb.toString();
        }

        // toString(), trim() on safe navigation chain
        if ("toString".equals(methodName) || "trim".equals(methodName)) {
            String obj = visitValueExpression(objExpr, bindingVar);
            if (isSafe) {
                return "(" + obj + " == null ? null : " + obj + "." + methodName + "())";
            }
            return obj + "." + methodName + "()";
        }

        // Generic method call
        String obj = visitValueExpression(objExpr, bindingVar);
        StringBuilder sb = new StringBuilder();
        if (isSafe) {
            sb.append("(").append(obj).append(" == null ? null : ");
        }
        sb.append(obj).append(".").append(methodName).append("(");
        List<Expression> argExprs = args.getExpressions();
        for (int i = 0; i < argExprs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(visitValueExpression(argExprs.get(i), bindingVar));
        }
        sb.append(")");
        if (isSafe) {
            sb.append(")");
        }
        return sb.toString();
    }

    private String visitGString(GStringExpression expr, String bindingVar) {
        List<ConstantExpression> strings = expr.getStrings();
        List<Expression> values = expr.getValues();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.size(); i++) {
            String text = strings.get(i).getText();
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append("\"").append(MalToJavaTranspiler.escapeJava(text)).append("\"");
            }
            if (i < values.size()) {
                String val = visitValueExpression(values.get(i), bindingVar);
                if (sb.length() > 0) sb.append(" + ");
                sb.append(val);
            }
        }
        return sb.length() > 0 ? sb.toString() : "\"\"";
    }

    private String visitMapExpression(MapExpression expr, String bindingVar) {
        List<MapEntryExpression> entries = expr.getMapEntryExpressions();
        if (entries.isEmpty()) {
            return "java.util.Collections.emptyMap()";
        }
        if (entries.size() == 1) {
            MapEntryExpression e = entries.get(0);
            return "Map.of(" + visitValueExpression(e.getKeyExpression(), bindingVar)
                + ", " + visitValueExpression(e.getValueExpression(), bindingVar) + ")";
        }
        StringBuilder sb = new StringBuilder("Map.of(");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(", ");
            MapEntryExpression e = entries.get(i);
            sb.append(visitValueExpression(e.getKeyExpression(), bindingVar));
            sb.append(", ");
            sb.append(visitValueExpression(e.getValueExpression(), bindingVar));
        }
        sb.append(")");
        return sb.toString();
    }

    // ---- Helpers ----

    private List<Statement> flattenStatements(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            return ((BlockStatement) stmt).getStatements();
        }
        return List.of(stmt);
    }

    private boolean isThisOrImplicit(Expression expr) {
        if (expr instanceof VariableExpression) {
            String name = ((VariableExpression) expr).getName();
            return "this".equals(name);
        }
        return false;
    }

    private String lambdaVarFor(String methodName) {
        switch (methodName) {
            case "extractor": return "ext";
            case "sink": return "s";
            case "slowSql": return "sql";
            case "sampledTrace": return "st";
            case "metrics": return "m";
            case "sampler": return "sp";
            case "text": return "tp";
            default: return "x";
        }
    }

    private String childReceiverFor(String methodName, String parentReceiver, String lambdaVar) {
        // For methods on ExtractorSpec, SinkSpec, etc., the lambda var IS the receiver
        return lambdaVar;
    }

    private ArgumentListExpression toArgList(Expression args) {
        if (args instanceof ArgumentListExpression) {
            return (ArgumentListExpression) args;
        }
        if (args instanceof TupleExpression) {
            ArgumentListExpression ale = new ArgumentListExpression();
            for (Expression e : ((TupleExpression) args).getExpressions()) {
                ale.addExpression(e);
            }
            return ale;
        }
        ArgumentListExpression ale = new ArgumentListExpression();
        ale.addExpression(args);
        return ale;
    }

    private String nextTempVar() {
        return "_t" + (tempVarCounter++);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("        ");
        }
    }

    // ---- Compilation & Manifest ----

    /**
     * Compile all registered LAL sources using javax.tools.JavaCompiler.
     */
    public void compileAll(File sourceDir, File outputDir, String classpath) throws IOException {
        if (lalSources.isEmpty()) {
            log.info("No LAL sources to compile.");
            return;
        }

        String packageDir = GENERATED_PACKAGE.replace('.', File.separatorChar);
        File srcPkgDir = new File(sourceDir, packageDir);
        if (!srcPkgDir.exists() && !srcPkgDir.mkdirs()) {
            throw new IOException("Failed to create source dir: " + srcPkgDir);
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create output dir: " + outputDir);
        }

        List<File> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : lalSources.entrySet()) {
            File javaFile = new File(srcPkgDir, entry.getKey() + ".java");
            Files.writeString(javaFile.toPath(), entry.getValue());
            javaFiles.add(javaFile);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available — requires JDK");
        }

        StringWriter errorWriter = new StringWriter();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromFiles(javaFiles);

            List<String> options = Arrays.asList(
                "-d", outputDir.getAbsolutePath(),
                "-classpath", classpath
            );

            JavaCompiler.CompilationTask task = compiler.getTask(
                errorWriter, fileManager, null, options, null, compilationUnits);

            boolean success = task.call();
            if (!success) {
                // Log each generated source for debugging
                for (Map.Entry<String, String> entry : lalSources.entrySet()) {
                    log.error("Generated source for {}:\n{}", entry.getKey(), entry.getValue());
                }
                throw new RuntimeException(
                    "Java compilation failed for " + javaFiles.size() + " LAL sources:\n"
                        + errorWriter);
            }
        }

        log.info("Compiled {} LAL sources to {}", lalSources.size(), outputDir);
    }

    /**
     * Write lal-expressions.txt manifest: hash=FQCN format.
     */
    public void writeManifest(File outputDir) throws IOException {
        File manifestDir = new File(outputDir, "META-INF");
        if (!manifestDir.exists() && !manifestDir.mkdirs()) {
            throw new IOException("Failed to create META-INF dir: " + manifestDir);
        }

        List<String> lines = hashToClass.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .sorted()
            .collect(Collectors.toList());
        Files.write(new File(manifestDir, "lal-expressions.txt").toPath(), lines);
        log.info("Wrote lal-expressions.txt with {} entries", lines.size());
    }
}
