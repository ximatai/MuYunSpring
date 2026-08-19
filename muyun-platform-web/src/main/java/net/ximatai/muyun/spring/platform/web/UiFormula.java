package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;

/**
 * A FormulaEngine expression compiled to the deterministic Web UI profile.
 *
 * <p>The raw expression is retained for diagnostics and authoring. {@link #program} is the server-issued AST that
 * every Web client executes; clients must never parse {@link #expression} as JavaScript or as a second grammar.</p>
 */
public record UiFormula(String expression, FormulaProgram program) {
    private static final FormulaEngine FORMULA_ENGINE = new FormulaEngine();

    public UiFormula(String expression) {
        this(expression, compile(expression));
    }

    private static FormulaProgram compile(String expression) {
        try {
            return FORMULA_ENGINE.compileWebUiProgram(expression);
        } catch (FormulaEvaluationException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    /**
     * Creates a Boolean predicate from the portable UI formula grammar.
     */
    public static UiFormula booleanExpression(String expression) {
        return new UiFormula(expression);
    }

    UiFormula negated() {
        if (expression.matches("!\\(PRESENT\\(\\{[A-Za-z][A-Za-z0-9_]*}\\)\\)")) {
            return new UiFormula(expression.substring(2, expression.length() - 1));
        }
        return new UiFormula("!(" + expression + ")");
    }
}
