package net.ximatai.muyun.spring.common.formula;

import java.util.List;

public class FormulaEvaluationSession implements FormulaEvaluationContext {
    private final FormulaEvaluationContext root;
    private final FormulaEvaluationContext context;
    private final boolean staged;

    FormulaEvaluationSession(FormulaEvaluationContext root, FormulaEvaluationContext context, boolean staged) {
        this.root = root;
        this.context = context;
        this.staged = staged;
    }

    @Override
    public Object get(FormulaFieldPath fieldPath, FormulaEvaluationScope scope) {
        return context.get(fieldPath, scope);
    }

    @Override
    public FormulaFieldWriteResult set(FormulaFieldPath fieldPath, Object value, FormulaEvaluationScope scope) {
        return context.set(fieldPath, value, scope);
    }

    @Override
    public List<?> rows(String tableKey) {
        return context.rows(tableKey);
    }

    @Override
    public FormulaEvaluationScope changeScope() {
        return context.changeScope();
    }

    public List<FormulaFieldWriteResult> commit() {
        return List.of();
    }

    public void rollback() {
    }

    public FormulaEvaluationContext root() {
        return root;
    }

    public boolean staged() {
        return staged;
    }
}
