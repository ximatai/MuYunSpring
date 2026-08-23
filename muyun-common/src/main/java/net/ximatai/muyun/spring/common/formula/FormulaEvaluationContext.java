package net.ximatai.muyun.spring.common.formula;

import java.util.List;

public interface FormulaEvaluationContext {
    Object get(FormulaFieldPath fieldPath, FormulaEvaluationScope scope);

    FormulaFieldWriteResult set(FormulaFieldPath fieldPath, Object value, FormulaEvaluationScope scope);

    List<?> rows(String tableKey);

    /**
     * The row that caused a row-to-row calculation. Ordinary formulas keep the main-record scope.
     */
    default FormulaEvaluationScope changeScope() {
        return FormulaEvaluationScope.main();
    }

    default FormulaEvaluationSession beginSession() {
        throw new FormulaEvaluationException(
                "FORMULA_STAGED_SESSION_REQUIRED",
                "formula calculation requires staged evaluation context"
        );
    }
}
