package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;

public record BusinessRuleSnapshotRule(String code, FormulaRuleKind kind, FormulaRulePhase phase,
                                       String targetField, String expression, boolean enabled,
                                       FormulaIssueLevel severity, String messageTemplate,
                                       boolean stopOnError, boolean editable, String readOnlyReason) {
}
