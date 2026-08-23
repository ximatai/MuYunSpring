package net.ximatai.muyun.spring.common.formula;

import java.util.List;

/**
 * JSON-safe formula AST node issued by the server.
 *
 * <p>This is the shared wire contract for local formula engines. A profile decides which node kinds,
 * operators and functions may actually execute in a particular context.</p>
 */
public record FormulaNode(
        Kind kind,
        String operator,
        String field,
        Object value,
        List<FormulaNode> arguments
) {
    public FormulaNode {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public enum Kind {
        VALUE, FIELD, OTHERS, UNARY, BINARY, FUNCTION, ASSIGN
    }
}
