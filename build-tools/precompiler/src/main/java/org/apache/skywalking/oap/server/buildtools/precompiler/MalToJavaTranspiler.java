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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;

/**
 * Transpiles Groovy MAL expressions to Java source code at build time.
 * Parses expression strings into Groovy AST (via CompilationUnit at CONVERSION phase),
 * walks AST nodes, and produces equivalent Java classes implementing MalExpression or MalFilter.
 *
 * <p>Supported AST patterns:
 * <ul>
 *   <li>Variable references: sample family lookups, DownsamplingType constants, KNOWN_TYPES</li>
 *   <li>Method chains: .sum(), .service(), .tagEqual(), .rate(), .histogram(), etc.</li>
 *   <li>Binary arithmetic with operand-swap logic per upstream ExpandoMetaClass
 *       (N-SF: sf.minus(N).negative(), N/SF: sf.newValue(v-&gt;N/v))</li>
 *   <li>tag() closures: TagFunction lambda (assignment, remove, string concat, if/else)</li>
 *   <li>Filter closures: MalFilter class (==, !=, in, truthiness, negation, &amp;&amp;, ||)</li>
 *   <li>forEach() closures: ForEachFunction lambda (var decls, if/else-if, early return)</li>
 *   <li>instance() with PropertiesExtractor closure: Map.of() from map literals</li>
 *   <li>Elvis (?:), safe navigation (?.), ternary (? :)</li>
 *   <li>Batch compilation via javax.tools.JavaCompiler + manifest writing</li>
 * </ul>
 *
 * <p>See MAL-TRANSPILER-DESIGN.md for the full AST-to-Java mapping reference.
 */
@Slf4j
public class MalToJavaTranspiler {

    static final String GENERATED_PACKAGE =
        "org.apache.skywalking.oap.server.core.source.oal.rt.mal";

    private static final Set<String> DOWNSAMPLING_CONSTANTS = Set.of(
        "AVG", "SUM", "LATEST", "SUM_PER_MIN", "MAX", "MIN"
    );

    /** Known enum/class names that appear as property bases (e.g. Layer.GENERAL). */
    private static final Set<String> KNOWN_TYPES = Set.of(
        "Layer", "DetectPoint", "K8sRetagType", "ProcessRegistry", "TimeUnit"
    );

    // ---- Batch state tracking ----

    /** Generated expression sources: className → Java source code. */
    private final Map<String, String> expressionSources = new LinkedHashMap<>();

    /** Generated filter sources: className → Java source code. */
    private final Map<String, String> filterSources = new LinkedHashMap<>();

    /** Filter literal to class name mapping for manifest. */
    private final Map<String, String> filterLiteralToClass = new LinkedHashMap<>();

    /**
     * Transpile a MAL expression to a Java class source implementing MalExpression.
     *
     * @param className  simple class name (e.g. "MalExpr_meter_jvm_heap")
     * @param expression the Groovy expression string
     * @return generated Java source code
     */
    public String transpileExpression(String className, String expression) {
        ModuleNode ast = parseToAST(expression);
        Statement body = extractBody(ast);

        Set<String> sampleNames = new LinkedHashSet<>();
        collectSampleNames(body, sampleNames);

        String javaBody = visitStatement(body);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        sb.append("import java.util.*;\n");
        sb.append("import org.apache.skywalking.oap.meter.analyzer.dsl.*;\n");
        sb.append("import org.apache.skywalking.oap.meter.analyzer.dsl.SampleFamilyFunctions.*;\n");
        sb.append("import org.apache.skywalking.oap.server.core.analysis.Layer;\n");
        sb.append("import org.apache.skywalking.oap.server.core.source.DetectPoint;\n");
        sb.append("import org.apache.skywalking.oap.meter.analyzer.dsl.tagOpt.K8sRetagType;\n");
        sb.append("import org.apache.skywalking.oap.meter.analyzer.dsl.registry.ProcessRegistry;\n\n");

        sb.append("public class ").append(className).append(" implements MalExpression {\n");
        sb.append("    @Override\n");
        sb.append("    public SampleFamily run(Map<String, SampleFamily> samples) {\n");

        if (!sampleNames.isEmpty()) {
            sb.append("        ExpressionParsingContext.get().ifPresent(ctx -> {\n");
            for (String name : sampleNames) {
                sb.append("            ctx.getSamples().add(\"").append(escapeJava(name)).append("\");\n");
            }
            sb.append("        });\n");
        }

        sb.append("        return ").append(javaBody).append(";\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Transpile a MAL filter literal to a Java class source implementing MalFilter.
     * Filter literals are closures like: { tags -> tags.job_name == 'vm-monitoring' }
     *
     * @param className     simple class name (e.g. "MalFilter_0")
     * @param filterLiteral the Groovy closure literal string
     * @return generated Java source code
     */
    public String transpileFilter(String className, String filterLiteral) {
        ModuleNode ast = parseToAST(filterLiteral);
        Statement body = extractBody(ast);

        // The filter literal is a closure expression at the top level
        ClosureExpression closure = extractClosure(body);
        Parameter[] params = closure.getParameters();
        String tagsVar = (params != null && params.length > 0) ? params[0].getName() : "tags";

        // Get the body expression — may need to unwrap inner block/closure
        Expression bodyExpr = extractFilterBodyExpr(closure.getCode(), tagsVar);

        // Generate the boolean condition
        String condition = visitFilterCondition(bodyExpr, tagsVar);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        sb.append("import java.util.*;\n");
        sb.append("import org.apache.skywalking.oap.meter.analyzer.dsl.*;\n\n");

        sb.append("public class ").append(className).append(" implements MalFilter {\n");
        sb.append("    @Override\n");
        sb.append("    public boolean test(Map<String, String> tags) {\n");
        sb.append("        return ").append(condition).append(";\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Extract the ClosureExpression from a script body.
     * The filter literal parses as a script whose body is a single ClosureExpression.
     */
    private ClosureExpression extractClosure(Statement body) {
        List<Statement> stmts = getStatements(body);
        if (stmts.size() == 1 && stmts.get(0) instanceof ExpressionStatement) {
            Expression expr = ((ExpressionStatement) stmts.get(0)).getExpression();
            if (expr instanceof ClosureExpression) {
                return (ClosureExpression) expr;
            }
        }
        throw new IllegalStateException(
            "Filter literal must be a single closure expression, got: "
                + (stmts.isEmpty() ? "empty" : stmts.get(0).getClass().getSimpleName()));
    }

    /**
     * Extract the boolean expression from a filter closure body.
     * Handles the "wrapped block" pattern: { tags -> {compound} }
     * where the inner {} is another ClosureExpression that must be unwrapped.
     */
    private Expression extractFilterBodyExpr(Statement code, String tagsVar) {
        List<Statement> stmts = getStatements(code);
        if (stmts.isEmpty()) {
            throw new IllegalStateException("Empty filter closure body");
        }

        // Get the last statement's expression
        Statement last = stmts.get(stmts.size() - 1);
        Expression expr;
        if (last instanceof ExpressionStatement) {
            expr = ((ExpressionStatement) last).getExpression();
        } else if (last instanceof ReturnStatement) {
            expr = ((ReturnStatement) last).getExpression();
        } else if (last instanceof BlockStatement) {
            // Nested block — recurse to unwrap
            return extractFilterBodyExpr(last, tagsVar);
        } else {
            throw new UnsupportedOperationException(
                "Unsupported filter body statement: " + last.getClass().getSimpleName());
        }

        // Unwrap inner closure: { tags -> {compound} } → the inner closure body
        if (expr instanceof ClosureExpression) {
            ClosureExpression inner = (ClosureExpression) expr;
            return extractFilterBodyExpr(inner.getCode(), tagsVar);
        }

        return expr;
    }

    // ---- AST Parsing ----

    ModuleNode parseToAST(String expression) {
        CompilerConfiguration cc = new CompilerConfiguration();
        CompilationUnit cu = new CompilationUnit(cc);
        cu.addSource("Script", expression);
        cu.compile(Phases.CONVERSION);
        List<ModuleNode> modules = cu.getAST().getModules();
        if (modules.isEmpty()) {
            throw new IllegalStateException("No AST modules produced for: " + expression);
        }
        return modules.get(0);
    }

    Statement extractBody(ModuleNode module) {
        BlockStatement block = module.getStatementBlock();
        if (block != null && !block.getStatements().isEmpty()) {
            return block;
        }
        List<ClassNode> classes = module.getClasses();
        if (!classes.isEmpty()) {
            return module.getStatementBlock();
        }
        throw new IllegalStateException("Empty AST body");
    }

    // ---- Sample Name Collection ----

    private void collectSampleNames(Statement stmt, Set<String> names) {
        if (stmt instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) stmt).getStatements()) {
                collectSampleNames(s, names);
            }
        } else if (stmt instanceof ExpressionStatement) {
            collectSampleNamesFromExpr(((ExpressionStatement) stmt).getExpression(), names);
        } else if (stmt instanceof ReturnStatement) {
            collectSampleNamesFromExpr(((ReturnStatement) stmt).getExpression(), names);
        }
    }

    void collectSampleNamesFromExpr(Expression expr, Set<String> names) {
        if (expr instanceof VariableExpression) {
            String name = ((VariableExpression) expr).getName();
            if (!DOWNSAMPLING_CONSTANTS.contains(name)
                && !KNOWN_TYPES.contains(name)
                && !name.equals("this") && !name.equals("time")) {
                names.add(name);
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            collectSampleNamesFromExpr(bin.getLeftExpression(), names);
            collectSampleNamesFromExpr(bin.getRightExpression(), names);
        } else if (expr instanceof PropertyExpression) {
            PropertyExpression pe = (PropertyExpression) expr;
            collectSampleNamesFromExpr(pe.getObjectExpression(), names);
        } else if (expr instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) expr;
            collectSampleNamesFromExpr(mce.getObjectExpression(), names);
            collectSampleNamesFromExpr(mce.getArguments(), names);
        } else if (expr instanceof ArgumentListExpression) {
            for (Expression e : ((ArgumentListExpression) expr).getExpressions()) {
                collectSampleNamesFromExpr(e, names);
            }
        } else if (expr instanceof TupleExpression) {
            for (Expression e : ((TupleExpression) expr).getExpressions()) {
                collectSampleNamesFromExpr(e, names);
            }
        }
    }

    // ---- Statement Visiting ----

    String visitStatement(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) stmt).getStatements();
            if (stmts.size() == 1) {
                return visitStatement(stmts.get(0));
            }
            // Multi-statement: last one is the return value
            return visitStatement(stmts.get(stmts.size() - 1));
        } else if (stmt instanceof ExpressionStatement) {
            return visitExpression(((ExpressionStatement) stmt).getExpression());
        } else if (stmt instanceof ReturnStatement) {
            return visitExpression(((ReturnStatement) stmt).getExpression());
        }
        throw new UnsupportedOperationException(
            "Unsupported statement: " + stmt.getClass().getSimpleName());
    }

    // ---- Expression Visiting ----

    String visitExpression(Expression expr) {
        if (expr instanceof VariableExpression) {
            return visitVariable((VariableExpression) expr);
        } else if (expr instanceof ConstantExpression) {
            return visitConstant((ConstantExpression) expr);
        } else if (expr instanceof MethodCallExpression) {
            return visitMethodCall((MethodCallExpression) expr);
        } else if (expr instanceof PropertyExpression) {
            return visitProperty((PropertyExpression) expr);
        } else if (expr instanceof ListExpression) {
            return visitList((ListExpression) expr);
        } else if (expr instanceof BinaryExpression) {
            return visitBinary((BinaryExpression) expr);
        } else if (expr instanceof ClosureExpression) {
            // Bare closure not in a recognized method context — shouldn't happen in MAL
            throw new UnsupportedOperationException(
                "Bare ClosureExpression outside method call context: " + expr.getText());
        }
        throw new UnsupportedOperationException(
            "Unsupported expression (not yet implemented): "
                + expr.getClass().getSimpleName() + " = " + expr.getText());
    }

    private String visitVariable(VariableExpression expr) {
        String name = expr.getName();
        if (DOWNSAMPLING_CONSTANTS.contains(name)) {
            return "DownsamplingType." + name;
        }
        if (KNOWN_TYPES.contains(name)) {
            return name;
        }
        if (name.equals("this")) {
            return "this";
        }
        // Sample family lookup
        return "samples.getOrDefault(\"" + escapeJava(name) + "\", SampleFamily.EMPTY)";
    }

    private String visitConstant(ConstantExpression expr) {
        Object value = expr.getValue();
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escapeJava((String) value) + "\"";
        }
        if (value instanceof Integer) {
            return value.toString();
        }
        if (value instanceof Long) {
            return value + "L";
        }
        if (value instanceof Double) {
            return value.toString();
        }
        if (value instanceof Float) {
            return value + "f";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return value.toString();
    }

    // ---- MethodCall, Property, List ----

    private String visitMethodCall(MethodCallExpression expr) {
        String methodName = expr.getMethodAsString();
        Expression objExpr = expr.getObjectExpression();
        ArgumentListExpression args = toArgList(expr.getArguments());

        // tag(closure) → TagFunction lambda
        if ("tag".equals(methodName) && args.getExpressions().size() == 1
            && args.getExpression(0) instanceof ClosureExpression) {
            String obj = visitExpression(objExpr);
            String lambda = visitTagClosure((ClosureExpression) args.getExpression(0));
            return obj + ".tag((TagFunction) " + lambda + ")";
        }

        // forEach(list, closure) → ForEachFunction lambda
        if ("forEach".equals(methodName) && args.getExpressions().size() == 2
            && args.getExpression(1) instanceof ClosureExpression) {
            String obj = visitExpression(objExpr);
            String list = visitExpression(args.getExpression(0));
            String lambda = visitForEachClosure((ClosureExpression) args.getExpression(1));
            return obj + ".forEach(" + list + ", (ForEachFunction) " + lambda + ")";
        }

        // instance(..., closure) → last arg is PropertiesExtractor lambda (Step 9)
        if ("instance".equals(methodName) && !args.getExpressions().isEmpty()) {
            Expression lastArg = args.getExpression(args.getExpressions().size() - 1);
            if (lastArg instanceof ClosureExpression) {
                String obj = visitExpression(objExpr);
                List<String> argStrs = new ArrayList<>();
                for (int i = 0; i < args.getExpressions().size() - 1; i++) {
                    argStrs.add(visitExpression(args.getExpression(i)));
                }
                String lambda = visitPropertiesExtractorClosure((ClosureExpression) lastArg);
                argStrs.add("(PropertiesExtractor) " + lambda);
                return obj + ".instance(" + String.join(", ", argStrs) + ")";
            }
        }

        String obj = visitExpression(objExpr);

        // Static method calls: ClassExpression.method(...)
        // e.g. ProcessRegistry.generateVirtualLocalProcess(...)
        if (objExpr instanceof ClassExpression) {
            String typeName = objExpr.getType().getNameWithoutPackage();
            List<String> argStrs = visitArgList(args);
            return typeName + "." + methodName + "(" + String.join(", ", argStrs) + ")";
        }

        // Regular instance method call: obj.method(args)
        List<String> argStrs = visitArgList(args);
        return obj + "." + methodName + "(" + String.join(", ", argStrs) + ")";
    }

    private String visitProperty(PropertyExpression expr) {
        Expression obj = expr.getObjectExpression();
        String prop = expr.getPropertyAsString();

        // Enum access: Layer.GENERAL, DetectPoint.SERVER, K8sRetagType.Pod2Service
        // At CONVERSION phase, Groovy doesn't resolve types, so these appear as
        // PropertyExpression(VariableExpression("Layer"), "GENERAL") not ClassExpression.
        if (obj instanceof ClassExpression) {
            return obj.getType().getNameWithoutPackage() + "." + prop;
        }
        if (obj instanceof VariableExpression) {
            String varName = ((VariableExpression) obj).getName();
            if (KNOWN_TYPES.contains(varName)) {
                return varName + "." + prop;
            }
        }

        // Fallback: obj.prop
        return visitExpression(obj) + "." + prop;
    }

    private String visitList(ListExpression expr) {
        List<String> elements = new ArrayList<>();
        for (Expression e : expr.getExpressions()) {
            elements.add(visitExpression(e));
        }
        return "List.of(" + String.join(", ", elements) + ")";
    }

    // ---- Binary Arithmetic ----

    private String visitBinary(BinaryExpression expr) {
        int opType = expr.getOperation().getType();

        if (isArithmetic(opType)) {
            return visitArithmetic(expr.getLeftExpression(), expr.getRightExpression(), opType);
        }

        // Non-arithmetic binary ops handled in closure-specific visitors
        throw new UnsupportedOperationException(
            "Unsupported binary operator (not yet implemented): "
                + expr.getOperation().getText() + " in " + expr.getText());
    }

    /**
     * Arithmetic with operand-swap logic per upstream ExpandoMetaClass:
     * <pre>
     *   SF + SF  → left.plus(right)
     *   SF - SF  → left.minus(right)
     *   SF * SF  → left.multiply(right)
     *   SF / SF  → left.div(right)
     *   SF op N  → sf.op(N)
     *   N + SF   → sf.plus(N)          (swap)
     *   N - SF   → sf.minus(N).negative()
     *   N * SF   → sf.multiply(N)      (swap)
     *   N / SF   → sf.newValue(v → N / v)
     *   N op N   → plain arithmetic
     * </pre>
     */
    private String visitArithmetic(Expression left, Expression right, int opType) {
        boolean leftNum = isNumberLiteral(left);
        boolean rightNum = isNumberLiteral(right);
        String leftStr = visitExpression(left);
        String rightStr = visitExpression(right);

        if (leftNum && rightNum) {
            // N op N — plain arithmetic
            return "(" + leftStr + " " + opSymbol(opType) + " " + rightStr + ")";
        }

        if (!leftNum && rightNum) {
            // SF op N
            return leftStr + "." + opMethod(opType) + "(" + rightStr + ")";
        }

        if (leftNum && !rightNum) {
            // N op SF — operand swap per ExpandoMetaClass
            switch (opType) {
                case Types.PLUS:
                    return rightStr + ".plus(" + leftStr + ")";
                case Types.MINUS:
                    return rightStr + ".minus(" + leftStr + ").negative()";
                case Types.MULTIPLY:
                    return rightStr + ".multiply(" + leftStr + ")";
                case Types.DIVIDE:
                    return rightStr + ".newValue(v -> " + leftStr + " / v)";
                default:
                    break;
            }
        }

        // SF op SF
        return leftStr + "." + opMethod(opType) + "(" + rightStr + ")";
    }

    private boolean isNumberLiteral(Expression expr) {
        if (expr instanceof ConstantExpression) {
            return ((ConstantExpression) expr).getValue() instanceof Number;
        }
        return false;
    }

    private boolean isArithmetic(int opType) {
        return opType == Types.PLUS || opType == Types.MINUS
            || opType == Types.MULTIPLY || opType == Types.DIVIDE;
    }

    private String opMethod(int opType) {
        switch (opType) {
            case Types.PLUS: return "plus";
            case Types.MINUS: return "minus";
            case Types.MULTIPLY: return "multiply";
            case Types.DIVIDE: return "div";
            default: return "???";
        }
    }

    private String opSymbol(int opType) {
        switch (opType) {
            case Types.PLUS: return "+";
            case Types.MINUS: return "-";
            case Types.MULTIPLY: return "*";
            case Types.DIVIDE: return "/";
            default: return "?";
        }
    }

    // ---- tag() Closure ----

    /**
     * Translate a tag closure to a TagFunction lambda.
     * Input:  {tags -> tags.key = 'val'; tags.remove('x'); ...}
     * Output: (tags -> { tags.put("key", "val"); tags.remove("x"); ... return tags; })
     */
    private String visitTagClosure(ClosureExpression closure) {
        Parameter[] params = closure.getParameters();
        String tagsVar = (params != null && params.length > 0) ? params[0].getName() : "tags";
        List<Statement> stmts = getStatements(closure.getCode());

        StringBuilder sb = new StringBuilder();
        sb.append("(").append(tagsVar).append(" -> {\n");
        for (Statement s : stmts) {
            sb.append("            ").append(visitTagStatement(s, tagsVar)).append("\n");
        }
        sb.append("            return ").append(tagsVar).append(";\n");
        sb.append("        })");
        return sb.toString();
    }

    private String visitTagStatement(Statement stmt, String tagsVar) {
        if (stmt instanceof ExpressionStatement) {
            return visitTagExpr(((ExpressionStatement) stmt).getExpression(), tagsVar) + ";";
        }
        if (stmt instanceof ReturnStatement) {
            return "return " + tagsVar + ";";
        }
        if (stmt instanceof IfStatement) {
            return visitTagIf((IfStatement) stmt, tagsVar);
        }
        throw new UnsupportedOperationException(
            "Unsupported tag closure statement: " + stmt.getClass().getSimpleName());
    }

    // ---- If/Else + Compound Conditions in tag() ----

    /**
     * Translate an if/else inside a tag closure.
     * <pre>
     *   if (tags['gc'] == 'Copy' || tags['gc'] == 'ParNew') { tags.gc = 'young' }
     *   else { tags.gc = 'old' }
     * </pre>
     * → Java if/else with .equals() for string comparisons.
     */
    private String visitTagIf(IfStatement ifStmt, String tagsVar) {
        String condition = visitTagCondition(ifStmt.getBooleanExpression().getExpression(), tagsVar);
        List<Statement> ifBody = getStatements(ifStmt.getIfBlock());
        Statement elseBlock = ifStmt.getElseBlock();

        StringBuilder sb = new StringBuilder();
        sb.append("if (").append(condition).append(") {\n");
        for (Statement s : ifBody) {
            sb.append("                ").append(visitTagStatement(s, tagsVar)).append("\n");
        }
        sb.append("            }");

        if (elseBlock != null && !(elseBlock instanceof EmptyStatement)) {
            sb.append(" else {\n");
            List<Statement> elseBody = getStatements(elseBlock);
            for (Statement s : elseBody) {
                sb.append("                ").append(visitTagStatement(s, tagsVar)).append("\n");
            }
            sb.append("            }");
        }

        return sb.toString();
    }

    /**
     * Translate a condition expression inside a tag closure.
     * Handles ==, !=, ||, && comparisons against tag values.
     * Groovy == translates to .equals() in Java for null-safety.
     */
    private String visitTagCondition(Expression expr, String tagsVar) {
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            int opType = bin.getOperation().getType();

            if (opType == Types.COMPARE_EQUAL) {
                return visitTagEquals(bin.getLeftExpression(), bin.getRightExpression(), tagsVar, false);
            }
            if (opType == Types.COMPARE_NOT_EQUAL) {
                return visitTagEquals(bin.getLeftExpression(), bin.getRightExpression(), tagsVar, true);
            }
            if (opType == Types.LOGICAL_OR) {
                return visitTagCondition(bin.getLeftExpression(), tagsVar)
                    + " || " + visitTagCondition(bin.getRightExpression(), tagsVar);
            }
            if (opType == Types.LOGICAL_AND) {
                return visitTagCondition(bin.getLeftExpression(), tagsVar)
                    + " && " + visitTagCondition(bin.getRightExpression(), tagsVar);
            }
        }
        if (expr instanceof BooleanExpression) {
            return visitTagCondition(((BooleanExpression) expr).getExpression(), tagsVar);
        }
        // Fallback: treat as a tag value expression (truthiness)
        return visitTagValue(expr, tagsVar);
    }

    /**
     * Translate == or != comparison in tag context.
     * Puts constant string on the left for null-safety:
     *   tags['gc'] == 'Copy' → "Copy".equals(tags.get("gc"))
     *   tags['gc'] != 'idle' → !"idle".equals(tags.get("gc"))
     */
    private String visitTagEquals(Expression left, Expression right, String tagsVar, boolean negate) {
        // Null comparison: expr == null or expr != null
        if (isNullConstant(right)) {
            String leftStr = visitTagValue(left, tagsVar);
            return negate ? leftStr + " != null" : leftStr + " == null";
        }
        if (isNullConstant(left)) {
            String rightStr = visitTagValue(right, tagsVar);
            return negate ? rightStr + " != null" : rightStr + " == null";
        }

        String leftStr = visitTagValue(left, tagsVar);
        String rightStr = visitTagValue(right, tagsVar);

        // If right side is a string constant, put it on left for null-safety
        if (right instanceof ConstantExpression && ((ConstantExpression) right).getValue() instanceof String) {
            String result = rightStr + ".equals(" + leftStr + ")";
            return negate ? "!" + result : result;
        }
        // If left side is a string constant
        if (left instanceof ConstantExpression && ((ConstantExpression) left).getValue() instanceof String) {
            String result = leftStr + ".equals(" + rightStr + ")";
            return negate ? "!" + result : result;
        }
        // Neither side is constant string — use Objects.equals
        String result = "Objects.equals(" + leftStr + ", " + rightStr + ")";
        return negate ? "!" + result : result;
    }

    // ---- Filter Conditions ----

    /**
     * Visit a condition expression in a filter context.
     * Extends tag condition handling with:
     * - {@code in} operator: {@code tags.key in ['a','b']} → {@code List.of("a","b").contains(tags.get("key"))}
     * - Truthiness: bare {@code tags.key} → {@code tags.get("key") != null && !tags.get("key").isEmpty()}
     * - Negation: {@code !tags.key} → {@code tags.get("key") == null || tags.get("key").isEmpty()}
     */
    private String visitFilterCondition(Expression expr, String tagsVar) {
        // NotExpression: !tags.key → truthiness negation
        if (expr instanceof NotExpression) {
            Expression inner = ((NotExpression) expr).getExpression();
            String val = visitTagValue(inner, tagsVar);
            return "(" + val + " == null || " + val + ".isEmpty())";
        }

        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            int opType = bin.getOperation().getType();

            // Reuse == and != from tag conditions
            if (opType == Types.COMPARE_EQUAL) {
                return visitTagEquals(bin.getLeftExpression(), bin.getRightExpression(), tagsVar, false);
            }
            if (opType == Types.COMPARE_NOT_EQUAL) {
                return visitTagEquals(bin.getLeftExpression(), bin.getRightExpression(), tagsVar, true);
            }
            // Logical operators — recurse with visitFilterCondition
            if (opType == Types.LOGICAL_OR) {
                return visitFilterCondition(bin.getLeftExpression(), tagsVar)
                    + " || " + visitFilterCondition(bin.getRightExpression(), tagsVar);
            }
            if (opType == Types.LOGICAL_AND) {
                return visitFilterCondition(bin.getLeftExpression(), tagsVar)
                    + " && " + visitFilterCondition(bin.getRightExpression(), tagsVar);
            }
            // IN operator: tags.key in ['a', 'b'] → List.of("a","b").contains(tags.get("key"))
            if (opType == Types.KEYWORD_IN) {
                String val = visitTagValue(bin.getLeftExpression(), tagsVar);
                String list = visitTagValue(bin.getRightExpression(), tagsVar);
                return list + ".contains(" + val + ")";
            }
        }

        if (expr instanceof BooleanExpression) {
            return visitFilterCondition(((BooleanExpression) expr).getExpression(), tagsVar);
        }

        // Fallback: treat as truthiness check on a tag value
        // tags.key → tags.get("key") != null && !tags.get("key").isEmpty()
        String val = visitTagValue(expr, tagsVar);
        return "(" + val + " != null && !" + val + ".isEmpty())";
    }

    /**
     * Visit an expression inside a tag closure body.
     * Handles assignment (put), remove, and other tag operations.
     */
    private String visitTagExpr(Expression expr, String tagsVar) {
        // Assignment: tags.key = val  or  tags[key] = val
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            if (bin.getOperation().getType() == Types.ASSIGN) {
                return visitTagAssignment(bin.getLeftExpression(), bin.getRightExpression(), tagsVar);
            }
        }
        // Method call: tags.remove('key')
        if (expr instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) expr;
            if ("remove".equals(mce.getMethodAsString()) && isTagsVar(mce.getObjectExpression(), tagsVar)) {
                ArgumentListExpression args = toArgList(mce.getArguments());
                return tagsVar + ".remove(" + visitTagValue(args.getExpression(0), tagsVar) + ")";
            }
        }
        return visitTagValue(expr, tagsVar);
    }

    /**
     * Visit a tag assignment: LHS = RHS.
     * tags.key = val  → tags.put("key", val)
     * tags[expr] = val → tags.put(expr, val)
     */
    private String visitTagAssignment(Expression left, Expression right, String tagsVar) {
        String val = visitTagValue(right, tagsVar);

        // tags.key = val → PropertyExpression
        if (left instanceof PropertyExpression) {
            PropertyExpression prop = (PropertyExpression) left;
            if (isTagsVar(prop.getObjectExpression(), tagsVar)) {
                return tagsVar + ".put(\"" + escapeJava(prop.getPropertyAsString()) + "\", " + val + ")";
            }
        }
        // tags[expr] = val → BinaryExpression with LEFT_SQUARE_BRACKET
        if (left instanceof BinaryExpression) {
            BinaryExpression sub = (BinaryExpression) left;
            if (sub.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                && isTagsVar(sub.getLeftExpression(), tagsVar)) {
                String key = visitTagValue(sub.getRightExpression(), tagsVar);
                return tagsVar + ".put(" + key + ", " + val + ")";
            }
        }
        throw new UnsupportedOperationException(
            "Unsupported tag assignment target: " + left.getClass().getSimpleName() + " = " + left.getText());
    }

    /**
     * Visit a value expression inside a tag closure.
     * Translates tags.key → tags.get("key"), tags['key'] → tags.get("key"),
     * string concat, method calls on tag values, etc.
     */
    String visitTagValue(Expression expr, String tagsVar) {
        // tags.key → tags.get("key")
        if (expr instanceof PropertyExpression) {
            PropertyExpression prop = (PropertyExpression) expr;
            if (isTagsVar(prop.getObjectExpression(), tagsVar)) {
                return tagsVar + ".get(\"" + escapeJava(prop.getPropertyAsString()) + "\")";
            }
            // Not tags — could be enum like Layer.GENERAL
            return visitProperty(prop);
        }
        // tags[expr] → tags.get(expr)
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            if (bin.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                && isTagsVar(bin.getLeftExpression(), tagsVar)) {
                return tagsVar + ".get(" + visitTagValue(bin.getRightExpression(), tagsVar) + ")";
            }
            // String concatenation or other binary in tag context
            if (bin.getOperation().getType() == Types.PLUS) {
                return visitTagValue(bin.getLeftExpression(), tagsVar)
                    + " + " + visitTagValue(bin.getRightExpression(), tagsVar);
            }
        }
        // Elvis operator — must check BEFORE TernaryExpression since it extends it
        // expr ?: 'default' → (expr != null ? expr : "default")
        if (expr instanceof ElvisOperatorExpression) {
            ElvisOperatorExpression elvis = (ElvisOperatorExpression) expr;
            String val = visitTagValue(elvis.getTrueExpression(), tagsVar);
            String defaultVal = visitTagValue(elvis.getFalseExpression(), tagsVar);
            return "(" + val + " != null ? " + val + " : " + defaultVal + ")";
        }
        // Ternary operator — tags.ApiId ? exprA : exprB
        if (expr instanceof TernaryExpression) {
            TernaryExpression tern = (TernaryExpression) expr;
            String cond = visitFilterCondition(tern.getBooleanExpression().getExpression(), tagsVar);
            String trueVal = visitTagValue(tern.getTrueExpression(), tagsVar);
            String falseVal = visitTagValue(tern.getFalseExpression(), tagsVar);
            return "(" + cond + " ? " + trueVal + " : " + falseVal + ")";
        }
        // Method call / safe navigation — tags['key']?.trim() → null check
        if (expr instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) expr;
            String obj = visitTagValue(mce.getObjectExpression(), tagsVar);
            ArgumentListExpression args = toArgList(mce.getArguments());
            List<String> argStrs = new ArrayList<>();
            for (Expression a : args.getExpressions()) {
                argStrs.add(visitTagValue(a, tagsVar));
            }
            String call = obj + "." + mce.getMethodAsString() + "(" + String.join(", ", argStrs) + ")";
            if (mce.isSafe()) {
                return "(" + obj + " != null ? " + call + " : null)";
            }
            return call;
        }
        // Variable reference — could be tagsVar itself or a closure parameter
        if (expr instanceof VariableExpression) {
            String name = ((VariableExpression) expr).getName();
            if (name.equals(tagsVar)) {
                return tagsVar;
            }
            // Other variables: could be a local var or closure param
            return name;
        }
        // Constant
        if (expr instanceof ConstantExpression) {
            return visitConstant((ConstantExpression) expr);
        }
        // List
        if (expr instanceof ListExpression) {
            return visitList((ListExpression) expr);
        }
        // Map literal — ['pod': tags.pod, 'namespace': tags.namespace]
        if (expr instanceof MapExpression) {
            MapExpression map = (MapExpression) expr;
            List<String> entries = new ArrayList<>();
            for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                entries.add(visitTagValue(entry.getKeyExpression(), tagsVar));
                entries.add(visitTagValue(entry.getValueExpression(), tagsVar));
            }
            return "Map.of(" + String.join(", ", entries) + ")";
        }
        // Fallback
        return visitExpression(expr);
    }

    private boolean isTagsVar(Expression expr, String tagsVar) {
        return expr instanceof VariableExpression
            && ((VariableExpression) expr).getName().equals(tagsVar);
    }

    private List<Statement> getStatements(Statement stmt) {
        if (stmt instanceof BlockStatement) {
            return ((BlockStatement) stmt).getStatements();
        }
        return List.of(stmt);
    }

    // ---- forEach() Closure ----

    /**
     * Translate a forEach closure to a ForEachFunction lambda.
     * Input:  forEach(['client','server'], {prefix, tags -> ...})
     * Output: .forEach(List.of("client","server"), (ForEachFunction) (prefix, tags) -> { ... })
     */
    private String visitForEachClosure(ClosureExpression closure) {
        Parameter[] params = closure.getParameters();
        String prefixVar = (params != null && params.length > 0) ? params[0].getName() : "prefix";
        String tagsVar = (params != null && params.length > 1) ? params[1].getName() : "tags";

        List<Statement> stmts = getStatements(closure.getCode());

        StringBuilder sb = new StringBuilder();
        sb.append("(").append(prefixVar).append(", ").append(tagsVar).append(") -> {\n");
        for (Statement s : stmts) {
            sb.append("            ").append(visitForEachStatement(s, tagsVar)).append("\n");
        }
        sb.append("        }");
        return sb.toString();
    }

    private String visitForEachStatement(Statement stmt, String tagsVar) {
        if (stmt instanceof ExpressionStatement) {
            return visitForEachExpr(((ExpressionStatement) stmt).getExpression(), tagsVar) + ";";
        }
        if (stmt instanceof ReturnStatement) {
            // forEach is void (BiConsumer) — return always has no value
            return "return;";
        }
        if (stmt instanceof IfStatement) {
            return visitForEachIf((IfStatement) stmt, tagsVar);
        }
        throw new UnsupportedOperationException(
            "Unsupported forEach closure statement: " + stmt.getClass().getSimpleName());
    }

    /**
     * Visit an expression inside a forEach closure body.
     * Handles variable declarations, tag writes, local variable assignments.
     */
    private String visitForEachExpr(Expression expr, String tagsVar) {
        // Variable declaration: String result = ""
        if (expr instanceof DeclarationExpression) {
            DeclarationExpression decl = (DeclarationExpression) expr;
            String typeName = decl.getVariableExpression().getType().getNameWithoutPackage();
            String varName = decl.getVariableExpression().getName();
            String init = visitTagValue(decl.getRightExpression(), tagsVar);
            return typeName + " " + varName + " = " + init;
        }
        // Assignment
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            if (bin.getOperation().getType() == Types.ASSIGN) {
                Expression left = bin.getLeftExpression();
                // Tag write: tags.key = val or tags[expr] = val
                if (isTagWrite(left, tagsVar)) {
                    return visitTagAssignment(left, bin.getRightExpression(), tagsVar);
                }
                // Local variable assignment: result = '129'
                if (left instanceof VariableExpression) {
                    return ((VariableExpression) left).getName()
                        + " = " + visitTagValue(bin.getRightExpression(), tagsVar);
                }
            }
        }
        // Method call or other expression
        return visitTagExpr(expr, tagsVar);
    }

    /**
     * Translate if/else-if/else chains inside a forEach closure.
     * Handles chained else-if by detecting IfStatement as else block.
     */
    private String visitForEachIf(IfStatement ifStmt, String tagsVar) {
        String condition = visitTagCondition(ifStmt.getBooleanExpression().getExpression(), tagsVar);
        List<Statement> ifBody = getStatements(ifStmt.getIfBlock());
        Statement elseBlock = ifStmt.getElseBlock();

        StringBuilder sb = new StringBuilder();
        sb.append("if (").append(condition).append(") {\n");
        for (Statement s : ifBody) {
            sb.append("                ").append(visitForEachStatement(s, tagsVar)).append("\n");
        }
        sb.append("            }");

        if (elseBlock instanceof IfStatement) {
            // else if chain
            sb.append(" else ").append(visitForEachIf((IfStatement) elseBlock, tagsVar));
        } else if (elseBlock != null && !(elseBlock instanceof EmptyStatement)) {
            sb.append(" else {\n");
            for (Statement s : getStatements(elseBlock)) {
                sb.append("                ").append(visitForEachStatement(s, tagsVar)).append("\n");
            }
            sb.append("            }");
        }

        return sb.toString();
    }

    private boolean isTagWrite(Expression left, String tagsVar) {
        if (left instanceof PropertyExpression) {
            return isTagsVar(((PropertyExpression) left).getObjectExpression(), tagsVar);
        }
        if (left instanceof BinaryExpression) {
            BinaryExpression sub = (BinaryExpression) left;
            return sub.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                && isTagsVar(sub.getLeftExpression(), tagsVar);
        }
        return false;
    }

    private boolean isNullConstant(Expression expr) {
        return expr instanceof ConstantExpression && ((ConstantExpression) expr).getValue() == null;
    }

    // ---- PropertiesExtractor Closure ----

    /**
     * Translate a PropertiesExtractor closure.
     * Input:  { tags -> ['pod': tags.pod, 'namespace': tags.namespace] }
     * Output: (tags -> Map.of("pod", tags.get("pod"), "namespace", tags.get("namespace")))
     */
    private String visitPropertiesExtractorClosure(ClosureExpression closure) {
        Parameter[] params = closure.getParameters();
        String tagsVar = (params != null && params.length > 0) ? params[0].getName() : "tags";
        List<Statement> stmts = getStatements(closure.getCode());

        Statement last = stmts.get(stmts.size() - 1);
        Expression bodyExpr;
        if (last instanceof ExpressionStatement) {
            bodyExpr = ((ExpressionStatement) last).getExpression();
        } else if (last instanceof ReturnStatement) {
            bodyExpr = ((ReturnStatement) last).getExpression();
        } else {
            throw new UnsupportedOperationException(
                "Unsupported PropertiesExtractor closure body: " + last.getClass().getSimpleName());
        }
        return "(" + tagsVar + " -> " + visitTagValue(bodyExpr, tagsVar) + ")";
    }

    // ---- Batch Registration, Compilation, and Manifest Writing ----

    /**
     * Register a transpiled expression for batch compilation.
     */
    public void registerExpression(String className, String source) {
        expressionSources.put(className, source);
    }

    /**
     * Register a transpiled filter for batch compilation.
     */
    public void registerFilter(String className, String filterLiteral, String source) {
        filterSources.put(className, source);
        filterLiteralToClass.put(filterLiteral, GENERATED_PACKAGE + "." + className);
    }

    /**
     * Compile all registered sources using javax.tools.JavaCompiler.
     *
     * @param sourceDir  directory to write .java source files (package dirs created automatically)
     * @param outputDir  directory for compiled .class files
     * @param classpath  classpath for javac (semicolon/colon-separated JAR paths)
     * @throws IOException if file I/O fails
     */
    public void compileAll(File sourceDir, File outputDir, String classpath) throws IOException {
        Map<String, String> allSources = new LinkedHashMap<>();
        allSources.putAll(expressionSources);
        allSources.putAll(filterSources);

        if (allSources.isEmpty()) {
            log.info("No MAL sources to compile.");
            return;
        }

        // Write .java files
        String packageDir = GENERATED_PACKAGE.replace('.', File.separatorChar);
        File srcPkgDir = new File(sourceDir, packageDir);
        if (!srcPkgDir.exists() && !srcPkgDir.mkdirs()) {
            throw new IOException("Failed to create source dir: " + srcPkgDir);
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create output dir: " + outputDir);
        }

        List<File> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : allSources.entrySet()) {
            File javaFile = new File(srcPkgDir, entry.getKey() + ".java");
            Files.writeString(javaFile.toPath(), entry.getValue());
            javaFiles.add(javaFile);
        }

        // Compile
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
                throw new RuntimeException(
                    "Java compilation failed for " + javaFiles.size() + " MAL sources:\n"
                        + errorWriter);
            }
        }

        log.info("Compiled {} MAL sources to {}", allSources.size(), outputDir);
    }

    /**
     * Write mal-expressions.txt manifest: one FQCN per line.
     */
    public void writeExpressionManifest(File outputDir) throws IOException {
        File manifestDir = new File(outputDir, "META-INF");
        if (!manifestDir.exists() && !manifestDir.mkdirs()) {
            throw new IOException("Failed to create META-INF dir: " + manifestDir);
        }

        List<String> lines = expressionSources.keySet().stream()
            .map(name -> GENERATED_PACKAGE + "." + name)
            .collect(Collectors.toList());
        Files.write(new File(manifestDir, "mal-expressions.txt").toPath(), lines);
        log.info("Wrote mal-expressions.txt with {} entries", lines.size());
    }

    /**
     * Write mal-filter-expressions.properties manifest: literal=FQCN.
     */
    public void writeFilterManifest(File outputDir) throws IOException {
        File manifestDir = new File(outputDir, "META-INF");
        if (!manifestDir.exists() && !manifestDir.mkdirs()) {
            throw new IOException("Failed to create META-INF dir: " + manifestDir);
        }

        List<String> lines = filterLiteralToClass.entrySet().stream()
            .map(e -> escapeProperties(e.getKey()) + "=" + e.getValue())
            .collect(Collectors.toList());
        Files.write(new File(manifestDir, "mal-filter-expressions.properties").toPath(), lines);
        log.info("Wrote mal-filter-expressions.properties with {} entries", lines.size());
    }

    /** Escape a properties key (= and : need escaping). */
    private static String escapeProperties(String s) {
        return s.replace("\\", "\\\\")
                .replace("=", "\\=")
                .replace(":", "\\:")
                .replace(" ", "\\ ");
    }

    // ---- Argument Utilities ----

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

    private List<String> visitArgList(ArgumentListExpression args) {
        List<String> result = new ArrayList<>();
        for (Expression arg : args.getExpressions()) {
            result.add(visitExpression(arg));
        }
        return result;
    }

    // ---- Utility ----

    static String escapeJava(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
