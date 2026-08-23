package net.ximatai.muyun.spring.common.formula;

import net.ximatai.muyun.spring.common.formula.FormulaAst.AssignNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.AstNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.BinaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FieldNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FuncNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.OthersNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.UnaryNode;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class FormulaExpressionSupport {
    private FormulaExpressionSupport() {
    }

    static ParsedExpression parse(String ruleId, String expression, FormulaTokenizer tokenizer) {
        String normalized = extractExpression(expression);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        FormulaParser parser = new FormulaParser(normalized, tokenizer);
        return new ParsedExpression(ruleId, normalized, parser.parseExpression());
    }

    static String extractExpression(String formulaContent) {
        if (formulaContent == null || formulaContent.isBlank()) {
            return null;
        }
        String raw = formulaContent.trim();
        if (!raw.startsWith("{")) {
            return raw;
        }
        int expressionKey = raw.indexOf("\"expression\"");
        if (expressionKey < 0) {
            return raw;
        }
        int colon = raw.indexOf(':', expressionKey);
        if (colon < 0) {
            return raw;
        }
        int firstQuote = raw.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return raw;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = firstQuote + 1; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaped) {
                value.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                return value.toString().trim();
            }
            value.append(ch);
        }
        return raw;
    }

    static boolean containsAssignment(AstNode node) {
        if (node == null) {
            return false;
        }
        if (node instanceof AssignNode) {
            return true;
        }
        if (node instanceof UnaryNode unaryNode) {
            return containsAssignment(unaryNode.arg);
        }
        if (node instanceof BinaryNode binaryNode) {
            return containsAssignment(binaryNode.left) || containsAssignment(binaryNode.right);
        }
        if (node instanceof FuncNode funcNode) {
            return funcNode.args.stream().filter(Objects::nonNull).anyMatch(FormulaExpressionSupport::containsAssignment);
        }
        return false;
    }

    static boolean containsOthersAssignment(AstNode node) {
        if (!(node instanceof AssignNode assignment)) {
            return false;
        }
        return assignment.left instanceof OthersNode;
    }

    static Set<String> referencedFields(AstNode node) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        collectReferencedFields(node, fields);
        return Set.copyOf(fields);
    }

    static Set<String> assignedFields(AstNode node) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        collectAssignedFields(node, fields);
        return Set.copyOf(fields);
    }

    private static void collectAssignedFields(AstNode node, Set<String> fields) {
        if (node == null) {
            return;
        }
        if (node instanceof AssignNode assignNode) {
            if (assignNode.left instanceof FieldNode fieldNode) {
                fields.add(FormulaFieldPath.parse(fieldNode.dataIndex).dataIndex());
            } else if (assignNode.left instanceof OthersNode othersNode) {
                fields.add(FormulaFieldPath.parse(othersNode.dataIndex).dataIndex());
            }
            collectAssignedFields(assignNode.left, fields);
            collectAssignedFields(assignNode.right, fields);
            collectAssignedFields(assignNode.condition, fields);
            return;
        }
        if (node instanceof UnaryNode unaryNode) {
            collectAssignedFields(unaryNode.arg, fields);
            return;
        }
        if (node instanceof BinaryNode binaryNode) {
            collectAssignedFields(binaryNode.left, fields);
            collectAssignedFields(binaryNode.right, fields);
            return;
        }
        if (node instanceof FuncNode funcNode) {
            funcNode.args.stream().filter(Objects::nonNull).forEach(arg -> collectAssignedFields(arg, fields));
        }
    }

    private static void collectReferencedFields(AstNode node, Set<String> fields) {
        if (node == null) {
            return;
        }
        if (node instanceof FieldNode fieldNode) {
            fields.add(FormulaFieldPath.parse(fieldNode.dataIndex).dataIndex());
            return;
        }
        if (node instanceof AssignNode assignNode) {
            collectReferencedFields(assignNode.left, fields);
            collectReferencedFields(assignNode.right, fields);
            collectReferencedFields(assignNode.condition, fields);
            return;
        }
        if (node instanceof OthersNode othersNode) {
            fields.add(FormulaFieldPath.parse(othersNode.dataIndex).dataIndex());
            return;
        }
        if (node instanceof UnaryNode unaryNode) {
            collectReferencedFields(unaryNode.arg, fields);
            return;
        }
        if (node instanceof BinaryNode binaryNode) {
            collectReferencedFields(binaryNode.left, fields);
            collectReferencedFields(binaryNode.right, fields);
            return;
        }
        if (node instanceof FuncNode funcNode) {
            funcNode.args.stream().filter(Objects::nonNull).forEach(arg -> collectReferencedFields(arg, fields));
        }
    }

    static boolean hasNestedAssignment(AstNode node) {
        return hasNestedAssignment(node, true);
    }

    private static boolean hasNestedAssignment(AstNode node, boolean root) {
        if (node == null) {
            return false;
        }
        if (node instanceof AssignNode assignNode) {
            return !root
                    || hasNestedAssignment(assignNode.left, false)
                    || hasNestedAssignment(assignNode.right, false)
                    || hasNestedAssignment(assignNode.condition, false);
        }
        if (node instanceof UnaryNode unaryNode) {
            return hasNestedAssignment(unaryNode.arg, false);
        }
        if (node instanceof BinaryNode binaryNode) {
            return hasNestedAssignment(binaryNode.left, false) || hasNestedAssignment(binaryNode.right, false);
        }
        if (node instanceof FuncNode funcNode) {
            return funcNode.args.stream().filter(Objects::nonNull)
                    .anyMatch(arg -> hasNestedAssignment(arg, false));
        }
        return false;
    }

    record ParsedExpression(String id, String expression, AstNode ast) {
    }
}
