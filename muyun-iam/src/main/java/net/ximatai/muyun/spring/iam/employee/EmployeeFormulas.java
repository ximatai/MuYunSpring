package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition;

/** Formula declarations shared by Employee's persistence and UI integration paths. */
public final class EmployeeFormulas {
    public static final String PRIMARY_POSITION_EXCLUSIVE = "primaryPositionExclusive";
    public static final String PRIMARY_POSITION_EXCLUSIVE_EXPRESSION =
            "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}";

    private EmployeeFormulas() {
    }

    public static AggregateChildFormulaDefinition primaryPositionExclusive() {
        return new AggregateChildFormulaDefinition("positions",
                new FormulaRule(PRIMARY_POSITION_EXCLUSIVE, PRIMARY_POSITION_EXCLUSIVE_EXPRESSION),
                java.util.List.of("primaryPosition"));
    }
}
