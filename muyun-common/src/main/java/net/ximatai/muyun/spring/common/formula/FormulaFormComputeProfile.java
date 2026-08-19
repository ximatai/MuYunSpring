package net.ximatai.muyun.spring.common.formula;

import net.ximatai.muyun.spring.common.formula.FormulaAst.AssignNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.AstNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.BinaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FieldNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FuncNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.UnaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.ValueNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiler for the deterministic subset of the FormulaEngine language that is safe to execute
 * against a browser form draft. It intentionally compiles exactly one root assignment.
 */
final class FormulaFormComputeProfile {
    static final int MAX_EXPRESSION_LENGTH = 1024;
    static final int MAX_IN_LITERALS = 20;
    static final int MAX_STRING_LITERAL_LENGTH = 256;
    private static final int MAX_NODE_COUNT = 128;
    private static final int MAX_NODE_DEPTH = 16;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private FormulaFormComputeProfile() {
    }

    static FormulaProgram compile(FormulaExpressionSupport.ParsedExpression parsed) {
        if (parsed == null || parsed.expression() == null || parsed.expression().isBlank()
                || parsed.expression().length() > MAX_EXPRESSION_LENGTH
                || !(parsed.ast() instanceof AssignNode assignment)) {
            throw unsupported(parsed == null ? null : parsed.expression());
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        CompileBudget budget = new CompileBudget();
        budget.visit(1);
        FormulaNode target = compileField(assignment.left, fields, budget, 2);
        FormulaNode value = compileScalar(assignment.right, fields, budget, 2);
        return new FormulaProgram(FormulaProgram.CURRENT_SCHEMA_VERSION, FormulaExecutionProfile.FORM_COMPUTE,
                assign(target, value), fields);
    }

    private static FormulaNode compileScalar(AstNode node, Set<String> fields, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (node instanceof ValueNode) {
            return compileLiteral(node, budget, depth + 1);
        }
        if (node instanceof FieldNode) {
            return compileField(node, fields, budget, depth + 1);
        }
        if (node instanceof UnaryNode unary && Set.of("!", "+", "-").contains(unary.op)) {
            return unary(unary.op, compileScalar(unary.arg, fields, budget, depth + 1));
        }
        if (node instanceof BinaryNode binary && Set.of(
                "+", "-", "*", "/", "%", ">", "<", ">=", "<=", "==", "!=", "&&", "||"
        ).contains(binary.op)) {
            return binary(binary.op, compileScalar(binary.left, fields, budget, depth + 1),
                    compileScalar(binary.right, fields, budget, depth + 1));
        }
        if (node instanceof FuncNode function) {
            String name = FormulaFunctions.normalize(function.name);
            if (("PRESENT".equals(name) || "ISNULL".equals(name)) && function.args.size() == 1) {
                return function(name, List.of(compileScalar(function.args.getFirst(), fields, budget, depth + 1)));
            }
            if ("IN".equals(name) && function.args.size() >= 2 && function.args.size() <= MAX_IN_LITERALS + 1) {
                List<FormulaNode> args = new ArrayList<>();
                args.add(compileScalar(function.args.getFirst(), fields, budget, depth + 1));
                for (int index = 1; index < function.args.size(); index++) {
                    args.add(compileLiteral(function.args.get(index), budget, depth + 1));
                }
                return function("IN", args);
            }
        }
        throw unsupportedNode(node);
    }

    private static FormulaNode compileField(AstNode node, Set<String> fields, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (!(node instanceof FieldNode field) || !FIELD.matcher(field.dataIndex).matches()) {
            throw unsupportedNode(node);
        }
        fields.add(field.dataIndex);
        return new FormulaNode(FormulaNode.Kind.FIELD, null, field.dataIndex, null, List.of());
    }

    private static FormulaNode compileLiteral(AstNode node, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (!(node instanceof ValueNode value)
                || !(value.value == null || value.value instanceof String || value.value instanceof Double || value.value instanceof Boolean)
                || value.value instanceof Double number && !Double.isFinite(number)
                || value.value instanceof String text && text.length() > MAX_STRING_LITERAL_LENGTH) {
            throw unsupportedNode(node);
        }
        return new FormulaNode(FormulaNode.Kind.VALUE, null, null, value.value, List.of());
    }

    private static FormulaNode assign(FormulaNode target, FormulaNode value) {
        return new FormulaNode(FormulaNode.Kind.ASSIGN, "=", null, null, List.of(target, value));
    }

    private static FormulaNode unary(String operator, FormulaNode argument) {
        return new FormulaNode(FormulaNode.Kind.UNARY, operator, null, null, List.of(argument));
    }

    private static FormulaNode binary(String operator, FormulaNode left, FormulaNode right) {
        return new FormulaNode(FormulaNode.Kind.BINARY, operator, null, null, List.of(left, right));
    }

    private static FormulaNode function(String operator, List<FormulaNode> arguments) {
        return new FormulaNode(FormulaNode.Kind.FUNCTION, operator, null, null, arguments);
    }

    private static FormulaEvaluationException unsupported(String expression) {
        return new FormulaEvaluationException("FORMULA_FORM_COMPUTE_UNSUPPORTED",
                "formula is not supported by FORM_COMPUTE profile: " + expression);
    }

    private static FormulaEvaluationException unsupportedNode(AstNode node) {
        return unsupported(node == null ? null : node.type.name());
    }

    private static final class CompileBudget {
        private int count;

        private void visit(int depth) {
            if (++count > MAX_NODE_COUNT || depth > MAX_NODE_DEPTH) {
                throw unsupported("program exceeds FORM_COMPUTE complexity limits");
            }
        }
    }
}
