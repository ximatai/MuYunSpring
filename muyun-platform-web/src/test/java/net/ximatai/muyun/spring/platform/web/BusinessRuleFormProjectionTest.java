package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationEditing;
import net.ximatai.muyun.spring.platform.ui.ResolvedUiRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRuleFormProjectionTest {
    @Test
    void projectsDirectChildAggregateAgainstTheRelationCodeUsedBySaveTimeRules() {
        ResolvedViewDescriptor contractEditor = form("contract_editor", field("contractAmount"));
        ResolvedViewDescriptor lineEditor = form("line_editor", relationField("contract_line", "lineAmount"));
        ResolvedDetailRelationDescriptor lines = new ResolvedDetailRelationDescriptor(
                "line_rows", "合同明细", false, "sales.contract", "contract", "sales.contract", "contract_line",
                "lines", null, null, null,
                new ResolvedDetailRelationEditing(ResolvedDetailRelationEditing.Mode.INLINE,
                        ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT),
                true, "line_rows", null, List.of(), ResolvedUiRule.constant(Boolean.TRUE));
        ResolvedModuleUiDescriptor descriptor = new ResolvedModuleUiDescriptor(null, "sales.contract", ModuleKind.DYNAMIC,
                "合同", List.of(), null, List.of(), null, contractEditor, List.of(),
                List.of(new ResolvedPageDetailEditorContribution("contract_line", lineEditor)), List.of(lines));

        ResolvedViewDescriptor projected = BusinessRuleFormProjection.projectLenient(descriptor, List.of(
                new FormulaRule("contractAmountSum", "{contractAmount} = SUM({lines.lineAmount})",
                        FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, "contractAmount")
        )).defaultEditor();

        assertThat(projected.formComputeRules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("contractAmountSum");
            assertThat(rule.triggerFields()).containsExactly("lines.lineAmount");
            assertThat(rule.program().root().arguments().get(1).operator()).isEqualTo("SUM");
        });
        assertThat(projected.fields()).singleElement().satisfies(field ->
                assertThat(field.readOnly().constant()).isTrue());
    }

    private static ResolvedViewDescriptor form(String code, ResolvedViewFieldDescriptor field) {
        return new ResolvedViewDescriptor(code, ModuleViewKind.FORM, ModuleUiClientType.WEB, null, List.of(field));
    }

    private static ResolvedViewFieldDescriptor field(String name) {
        return resolvedField(ViewFieldRef.main(name));
    }

    private static ResolvedViewFieldDescriptor relationField(String relation, String name) {
        return resolvedField(ViewFieldRef.relation(relation, name));
    }

    private static ResolvedViewFieldDescriptor resolvedField(ViewFieldRef field) {
        return new ResolvedViewFieldDescriptor(field, field.fieldName(), UiRule.constant(true), UiRule.constant(false),
                UiRule.constant(false), null, FieldValueType.DECIMAL, null, 1, null, null, null, null, null, null);
    }
}
