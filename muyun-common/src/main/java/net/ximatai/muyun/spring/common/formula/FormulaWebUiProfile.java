package net.ximatai.muyun.spring.common.formula;

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

/** Compiles the FormulaEngine AST to the deliberately small, deterministic WEB_UI execution policy. */
final class FormulaWebUiProfile {
    static final int MAX_EXPRESSION_LENGTH = 512;
    static final int MAX_IN_LITERALS = 20;
    static final int MAX_STRING_LITERAL_LENGTH = 128;
    private static final int MAX_NODE_COUNT = 64;
    private static final int MAX_NODE_DEPTH = 12;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private FormulaWebUiProfile() {
    }

    static FormulaProgram compile(FormulaExpressionSupport.ParsedExpression parsed) {
        if (parsed == null || parsed.expression() == null || parsed.expression().isBlank()
                || parsed.expression().length() > MAX_EXPRESSION_LENGTH) {
            throw unsupported(parsed == null ? null : parsed.expression());
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        CompileBudget budget = new CompileBudget();
        FormulaNode root = compileBoolean(parsed.ast(), fields, budget, 1);
        return new FormulaProgram(FormulaProgram.CURRENT_SCHEMA_VERSION, FormulaExecutionProfile.WEB_UI, root, fields);
    }

    private static FormulaNode compileBoolean(AstNode node, Set<String> fields, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (node instanceof UnaryNode unary && "!".equals(unary.op)) {
            return unary("!", compileBoolean(unary.arg, fields, budget, depth + 1));
        }
        if (node instanceof BinaryNode binary && ("&&".equals(binary.op) || "||".equals(binary.op))) {
            return binary(binary.op, compileBoolean(binary.left, fields, budget, depth + 1),
                    compileBoolean(binary.right, fields, budget, depth + 1));
        }
        if (node instanceof BinaryNode binary && ("==".equals(binary.op) || "!=".equals(binary.op))) {
            return binary(binary.op, compileField(binary.left, fields, budget, depth + 1),
                    compileLiteral(binary.right, budget, depth + 1));
        }
        if (node instanceof FuncNode function) {
            String name = FormulaFunctions.normalize(function.name);
            if (("PRESENT".equals(name) || "ISNULL".equals(name)) && function.args.size() == 1) {
                return function(name, List.of(compileField(function.args.getFirst(), fields, budget, depth + 1)));
            }
            if ("IN".equals(name) && function.args.size() >= 2 && function.args.size() <= MAX_IN_LITERALS + 1) {
                List<FormulaNode> args = new ArrayList<>();
                args.add(compileField(function.args.getFirst(), fields, budget, depth + 1));
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
        if (!(node instanceof FieldNode field) || !FIELD.matcher(field.dataIndex).matches()) throw unsupportedNode(node);
        fields.add(field.dataIndex);
        return new FormulaNode(FormulaNode.Kind.FIELD, null, field.dataIndex, null, List.of());
    }

    private static FormulaNode compileLiteral(AstNode node, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (!(node instanceof ValueNode value) || value.value == null
                || !(value.value instanceof String || value.value instanceof Double || value.value instanceof Boolean)
                || value.value instanceof Double number && !Double.isFinite(number)) {
            throw unsupportedNode(node);
        }
        if (value.value instanceof String text && (!value.stringLiteral || text.length() > MAX_STRING_LITERAL_LENGTH)) {
            throw unsupportedNode(node);
        }
        return new FormulaNode(FormulaNode.Kind.VALUE, null, null, value.value, List.of());
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
        return new FormulaEvaluationException("FORMULA_WEB_UI_UNSUPPORTED",
                "formula is not supported by WEB_UI profile: " + expression);
    }

    private static FormulaEvaluationException unsupportedNode(AstNode node) {
        return unsupported(node == null ? null : node.type.name());
    }

    private static final class CompileBudget {
        private int count;

        private void visit(int depth) {
            if (++count > MAX_NODE_COUNT || depth > MAX_NODE_DEPTH) {
                throw unsupported("program exceeds WEB_UI complexity limits");
            }
        }
    }
}
