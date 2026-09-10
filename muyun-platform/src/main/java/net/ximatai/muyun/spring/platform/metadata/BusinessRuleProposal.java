package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;

/** Editable first-phase business rule form. Calculation expressions contain only the right-hand side. */
public record BusinessRuleProposal(
        String code,
        FormulaRuleKind kind,
        String targetField,
        String expression,
        boolean enabled,
        String messageTemplate
) {
}
