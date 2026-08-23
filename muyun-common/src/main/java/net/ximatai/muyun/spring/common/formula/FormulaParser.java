package net.ximatai.muyun.spring.common.formula;

import net.ximatai.muyun.spring.common.formula.FormulaAst.AssignNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.AstNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.BinaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FieldNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.FuncNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.OthersNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.UnaryNode;
import net.ximatai.muyun.spring.common.formula.FormulaAst.ValueNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class FormulaParser {
    private static final List<List<String>> BINARY_OPERATOR_GROUPS = List.of(
            List.of("||"),
            List.of("&&"),
            List.of("==", "!=", ">=", "<=", ">", "<"),
            List.of("+", "-"),
            List.of("*", "/", "%")
    );
    private static final Set<String> UNARY_OPERATORS = Set.of("!", "-", "+");

    private final List<FormulaTokenizer.Token> tokens;
    private int index;

    FormulaParser(String expression, FormulaTokenizer tokenizer) {
        this.tokens = tokenizer.tokenize(expression);
    }

    AstNode parseExpression() {
        AstNode expression = parseAssignment();
        if (isWhen(peek())) {
            if (!(expression instanceof AssignNode assignment)) {
                throw new FormulaEvaluationException("FORMULA_PARSE_ERROR", "WHEN requires a formula assignment");
            }
            next();
            expression = new AssignNode(assignment.left, assignment.right, parseBinary(0));
        }
        FormulaTokenizer.Token token = peek();
        if (token != null) {
            throw new FormulaEvaluationException("FORMULA_PARSE_ERROR", "unexpected formula token: " + token.value());
        }
        return expression;
    }

    private AstNode parseAssignment() {
        AstNode left = parseBinary(0);
        FormulaTokenizer.Token token = peek();
        if (isOperator(token, "=")) {
            next();
            return new AssignNode(left, parseAssignment());
        }
        return left;
    }

    private AstNode parseBinary(int minPrec) {
        AstNode left = parseUnary();
        while (true) {
            FormulaTokenizer.Token token = peek();
            if (token == null || token.type() != FormulaTokenizer.TokenType.OP) {
                break;
            }
            int prec = binaryPrecedence(token.value());
            if (prec < minPrec) {
                break;
            }
            String op = token.value();
            next();
            left = new BinaryNode(op, left, parseBinary(prec + 1));
        }
        return left;
    }

    private AstNode parseUnary() {
        FormulaTokenizer.Token token = peek();
        if (token != null
                && token.type() == FormulaTokenizer.TokenType.OP
                && UNARY_OPERATORS.contains(token.value())) {
            next();
            return new UnaryNode(token.value(), parseUnary());
        }
        return parsePrimary();
    }

    private AstNode parsePrimary() {
        FormulaTokenizer.Token token = peek();
        if (token == null) {
            return new AstNode();
        }
        if (token.type() == FormulaTokenizer.TokenType.VALUE) {
            String raw = token.value();
            if (isField(raw)) {
                next();
                return new FieldNode(raw.substring(1, raw.length() - 1));
            }
            if (isFunctionName(raw) && isNextLeftParen()) {
                if ("OTHERS".equalsIgnoreCase(raw)) {
                    return parseOthers();
                }
                return parseFunction(raw);
            }
            next();
            return new ValueNode(parseValueToken(raw), isStringLiteral(raw));
        }
        if (token.type() == FormulaTokenizer.TokenType.LPAREN) {
            next();
            AstNode expression = parseAssignment();
            if (peek() == null || peek().type() != FormulaTokenizer.TokenType.RPAREN) {
                throw new FormulaEvaluationException("FORMULA_PARSE_ERROR", "formula right parenthesis is missing");
            }
            next();
            return expression;
        }
        next();
        return new AstNode();
    }

    private OthersNode parseOthers() {
        next();
        next();
        FormulaTokenizer.Token field = next();
        if (field == null || field.type() != FormulaTokenizer.TokenType.VALUE || !isField(field.value())
                || peek() == null || peek().type() != FormulaTokenizer.TokenType.RPAREN) {
            throw new FormulaEvaluationException("FORMULA_PARSE_ERROR", "others requires exactly one child field");
        }
        next();
        return new OthersNode(field.value().substring(1, field.value().length() - 1));
    }

    private FuncNode parseFunction(String name) {
        next();
        next();
        List<AstNode> args = new ArrayList<>();
        if (peek() != null && peek().type() == FormulaTokenizer.TokenType.RPAREN) {
            next();
            return new FuncNode(name, args);
        }
        while (peek() != null && peek().type() != FormulaTokenizer.TokenType.RPAREN) {
            args.add(parseAssignment());
            if (peek() != null && peek().type() == FormulaTokenizer.TokenType.COMMA) {
                next();
            }
        }
        if (peek() == null || peek().type() != FormulaTokenizer.TokenType.RPAREN) {
            throw new FormulaEvaluationException(
                    "FORMULA_PARSE_ERROR",
                    "formula function right parenthesis is missing: " + name
            );
        }
        next();
        return new FuncNode(name, args);
    }

    private FormulaTokenizer.Token peek() {
        return index < tokens.size() ? tokens.get(index) : null;
    }

    private FormulaTokenizer.Token next() {
        return index < tokens.size() ? tokens.get(index++) : null;
    }

    private boolean isOperator(FormulaTokenizer.Token token, String op) {
        return token != null && token.type() == FormulaTokenizer.TokenType.OP && op.equals(token.value());
    }

    private boolean isWhen(FormulaTokenizer.Token token) {
        return token != null && token.type() == FormulaTokenizer.TokenType.VALUE && "WHEN".equalsIgnoreCase(token.value());
    }

    private boolean isField(String raw) {
        return raw != null && raw.startsWith("{") && raw.endsWith("}") && raw.length() >= 3;
    }

    private boolean isFunctionName(String raw) {
        return raw != null && raw.matches("^[A-Za-z_][A-Za-z0-9_]*$");
    }

    private boolean isNextLeftParen() {
        return index + 1 < tokens.size() && tokens.get(index + 1).type() == FormulaTokenizer.TokenType.LPAREN;
    }

    private Object parseValueToken(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        if ("null".equals(value) || "undefined".equals(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }
        if (isStringLiteral(value)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private boolean isStringLiteral(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        return (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
    }

    private int binaryPrecedence(String op) {
        for (int i = 0; i < BINARY_OPERATOR_GROUPS.size(); i++) {
            if (BINARY_OPERATOR_GROUPS.get(i).contains(op)) {
                return i;
            }
        }
        return -1;
    }
}
