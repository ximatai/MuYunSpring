package net.ximatai.muyun.spring.common.formula;

import net.ximatai.muyun.spring.common.formula.FormulaAst.AstNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.BinaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FieldNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.ValueNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiler for the small string-only formula subset used by descriptor-owned page copy. */
final class FormulaPageTextProfile {
    static final int MAX_EXPRESSION_LENGTH = 512;
    static final int MAX_STRING_LITERAL_LENGTH = 128;
    private static final int MAX_NODE_COUNT = 64;
    private static final int MAX_NODE_DEPTH = 12;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");

    private FormulaPageTextProfile() {
    }

    static FormulaProgram compile(FormulaExpressionSupport.ParsedExpression parsed) {
        if (parsed == null || parsed.expression() == null || parsed.expression().isBlank()
                || parsed.expression().length() > MAX_EXPRESSION_LENGTH) throw unsupported(parsed == null ? null : parsed.expression());
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        CompileBudget budget = new CompileBudget();
        FormulaNode root = compileText(parsed.ast(), fields, budget, 1);
        return new FormulaProgram(FormulaProgram.CURRENT_SCHEMA_VERSION, FormulaExecutionProfile.PAGE_TEXT, root, fields);
    }

    private static FormulaNode compileText(AstNode node, Set<String> fields, CompileBudget budget, int depth) {
        budget.visit(depth);
        if (node instanceof FieldNode field && FIELD.matcher(field.dataIndex).matches()) {
            fields.add(field.dataIndex);
            return new FormulaNode(FormulaNode.Kind.FIELD, null, field.dataIndex, null, List.of());
        }
        if (node instanceof ValueNode value && value.value instanceof String text && value.stringLiteral
                && text.length() <= MAX_STRING_LITERAL_LENGTH) {
            return new FormulaNode(FormulaNode.Kind.VALUE, null, null, text, List.of());
        }
        if (node instanceof BinaryNode binary && "+".equals(binary.op)) {
            return new FormulaNode(FormulaNode.Kind.BINARY, "+", null, null, List.of(
                    compileText(binary.left, fields, budget, depth + 1),
                    compileText(binary.right, fields, budget, depth + 1)));
        }
        throw unsupportedNode(node);
    }

    private static FormulaEvaluationException unsupported(String expression) {
        return new FormulaEvaluationException("FORMULA_PAGE_TEXT_UNSUPPORTED",
                "formula is not supported by PAGE_TEXT profile: " + expression);
    }

    private static FormulaEvaluationException unsupportedNode(AstNode node) {
        return unsupported(node == null ? null : node.type.name());
    }

    private static final class CompileBudget {
        private int count;
        private void visit(int depth) {
            if (++count > MAX_NODE_COUNT || depth > MAX_NODE_DEPTH) {
                throw unsupported("program exceeds PAGE_TEXT complexity limits");
            }
        }
    }
}
