package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;

/** One aggregate-child calculation shared by persistence reconciliation and browser descriptors. */
public record AggregateChildFormulaDefinition(String relationCode, FormulaRule rule, List<String> triggerFields) {
    public AggregateChildFormulaDefinition {
        relationCode = PlatformNameRules.requireIdentifier(relationCode, "aggregate child formula relation code");
        if (rule == null || !rule.enabled()) {
            throw new IllegalArgumentException("aggregate child formula requires an enabled rule");
        }
        triggerFields = triggerFields == null ? List.of() : triggerFields.stream()
                .map(field -> PlatformNameRules.requireFieldName(field, "aggregate child formula trigger field"))
                .distinct().toList();
        if (triggerFields.isEmpty()) {
            throw new IllegalArgumentException("aggregate child formula requires at least one trigger field: " + rule.id());
        }
    }
}
