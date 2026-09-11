package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRuleFormValidationProjectionTest {
    @Test
    void projectsBlockingMainRecordValidationButLeavesChildRulesServerAuthoritative() {
        ResolvedViewDescriptor editor = new ResolvedViewDescriptor("contract_editor", ModuleViewKind.FORM,
                ModuleUiClientType.WEB, "合同", List.of(field("contractAmount")));
        ResolvedModuleUiDescriptor descriptor = new ResolvedModuleUiDescriptor(null, "sales.contract", ModuleKind.DYNAMIC,
                "合同", List.of(), null, List.of(), null, editor, List.of(), List.of(), List.of());

        ResolvedViewDescriptor projected = BusinessRuleFormProjection.projectLenient(descriptor, List.of(
                validation("amountPositive", "{contractAmount} > 0", "合同金额必须大于 0"),
                validation("lineAmountPresent", "SUM({lines.amount}) > 0", "至少录入一行明细")
        )).defaultEditor();

        assertThat(projected.formValidationRules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("amountPositive");
            assertThat(rule.program().profile().name()).isEqualTo("FORM_VALIDATION");
            assertThat(rule.inputFields()).containsExactly("contractAmount");
            assertThat(rule.targetField()).isEqualTo("contractAmount");
            assertThat(rule.message()).isEqualTo("合同金额必须大于 0");
        });
    }

    private static FormulaRule validation(String code, String expression, String message) {
        return new FormulaRule(code, expression, FormulaRuleKind.VALIDATION, FormulaRulePhase.BEFORE_SAVE,
                "contractAmount", FormulaIssueLevel.ERROR, message, true, true);
    }

    private static ResolvedViewFieldDescriptor field(String name) {
        return new ResolvedViewFieldDescriptor(ViewFieldRef.main(name), name, UiRule.constant(true),
                UiRule.constant(false), UiRule.constant(false), null, FieldValueType.DECIMAL, null,
                1, null, null, null, null, null, null);
    }
}
