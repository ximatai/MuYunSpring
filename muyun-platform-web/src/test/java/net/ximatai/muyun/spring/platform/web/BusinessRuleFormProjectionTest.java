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

        var serverDescriptor = BusinessRuleFormProjection.projectLenient(descriptor, List.of(
                new FormulaRule("contractAmountSum", "{contractAmount} = SUM({lines.lineAmount})",
                        FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, "contractAmount"),
                new FormulaRule("lineAmount", "{customer.credit}", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, "lines.lineAmount")));
        var serverOnly = serverDescriptor.defaultEditor();
        assertThat(serverOnly.formComputeRules()).isEmpty();
        assertThat(serverOnly.fields().getFirst().readOnly().constant()).isTrue();
        assertThat(serverOnly.fields().getFirst().readOnly().disabledHint()).contains("保存时自动计算");
        assertThat(serverOnly.fields().getFirst().calculationTiming())
                .isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.ON_SAVE);
        var childOutput = serverDescriptor.editorContributions().getFirst().editor().fields().getFirst();
        assertThat(childOutput.readOnly().constant()).isTrue();
        assertThat(childOutput.required().constant()).isFalse();
        assertThat(childOutput.readOnly().disabledHint()).contains("保存时自动计算");
        assertThat(childOutput.withReadOnly(UiRule.constant(true)).withUiState(UiRule.constant(true), UiRule.constant(true))
                .withAssistantPolicy(childOutput.assistantPolicy()).withInputRequirements(childOutput.inputRequirements())
                .withComputedValue("updated hint")
                .calculationTiming()).isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.ON_SAVE);
        assertThat(projected.fields().getFirst().calculationTiming())
                .isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.IMMEDIATE);

        assertThat(projected.formComputeRules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("contractAmountSum");
            assertThat(rule.triggerFields()).containsExactly("lines.lineAmount");
            assertThat(rule.program().root().arguments().get(1).operator()).isEqualTo("SUM");
        });
        assertThat(projected.fields()).singleElement().satisfies(field ->
                assertThat(field.readOnly().constant()).isTrue());
    }

    @Test
    void projectsSameRowDependenciesBeforeTheParentSummaryForBothModuleKinds() {
        for (ModuleKind kind : List.of(ModuleKind.STATIC, ModuleKind.DYNAMIC)) {
            var parent = form("parent", field("total"));
            var child = new ResolvedViewDescriptor("child", ModuleViewKind.FORM, ModuleUiClientType.WEB, null,
                    List.of(relationField("line", "quantity"), relationField("line", "unitPrice"),
                            relationField("line", "amount"), relationField("line", "rounded")));
            var lines = new ResolvedDetailRelationDescriptor("line_rows", "明细", false, "sales.contract", "contract",
                    "sales.contract", "line", "lines", null, null, null,
                    new ResolvedDetailRelationEditing(ResolvedDetailRelationEditing.Mode.INLINE,
                            ResolvedDetailRelationEditing.SaveMode.AGGREGATE_DRAFT),
                    true, "line_rows", null, List.of(), ResolvedUiRule.constant(true));
            var descriptor = new ResolvedModuleUiDescriptor(null, "sales.contract", kind, "合同", List.of(), null,
                    List.of(), null, parent, List.of(), List.of(new ResolvedPageDetailEditorContribution("line", child)),
                    List.of(lines));
            var rules = List.of(new FormulaRule("total", "{total} = SUM({lines.rounded})"),
                    new FormulaRule("rounded", "ROUND({lines.amount}, 2)", FormulaRuleKind.CALCULATION,
                            FormulaRulePhase.BEFORE_SAVE, "lines.rounded"),
                    new FormulaRule("amount", "{lines.quantity} * {lines.unitPrice}", FormulaRuleKind.CALCULATION,
                            FormulaRulePhase.BEFORE_SAVE, "lines.amount"));
            var projected = BusinessRuleFormProjection.projectLenient(descriptor, rules);
            assertThat(projected.defaultEditor().formComputeRules()).extracting(ResolvedFormComputeRuleDescriptor::code)
                    .containsExactly("total");
            assertThat(projected.defaultEditor().fields().getFirst().calculationTiming())
                    .isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.IMMEDIATE);
            var childEditor = projected.editorContributions().getFirst().editor();
            assertThat(childEditor.formComputeRules()).extracting(ResolvedFormComputeRuleDescriptor::targetField)
                    .containsExactly("amount", "rounded");
            assertThat(childEditor.formComputeRules().getFirst().triggerFields())
                    .containsExactlyInAnyOrder("quantity", "unitPrice");
            assertThat(childEditor.fields().subList(2, 4)).allSatisfy(output -> {
                assertThat(output.readOnly().constant()).isTrue();
                assertThat(output.calculationTiming()).isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.IMMEDIATE);
            });
            var hiddenChild = child.withFields(child.fields().stream().map(output ->
                    output.fieldRef().fieldName().equals("quantity") ? output.withUiState(UiRule.constant(false), UiRule.constant(true))
                            : output).toList());
            var unavailable = BusinessRuleFormProjection.projectLenient(descriptor.withEditorContributions(
                    List.of(new ResolvedPageDetailEditorContribution("line", hiddenChild))), rules);
            assertThat(unavailable.editorContributions().getFirst().editor().formComputeRules()).isEmpty();
            assertThat(unavailable.defaultEditor().formComputeRules()).isEmpty();
            assertThat(unavailable.defaultEditor().fields().getFirst().calculationTiming())
                    .isEqualTo(ResolvedViewFieldDescriptor.CalculationTiming.ON_SAVE);
        }
    }

    @Test
    void preservesManualInputsForConditionalSelfDependentDisabledAndConflictingWriters() {
        for (ModuleKind kind : List.of(ModuleKind.STATIC, ModuleKind.DYNAMIC)) {
            var editor = new ResolvedViewDescriptor("editor", ModuleViewKind.FORM, ModuleUiClientType.WEB, null,
                    List.of(field("amount"), field("guarded"), field("manual"), field("disabled"), field("conflict")));
            var descriptor = new ResolvedModuleUiDescriptor(null, "sales.contract", kind, "合同", List.of(),
                    null, List.of(), null, editor, List.of(), List.of(), List.of());
            var projected = BusinessRuleFormProjection.projectLenient(descriptor, List.of(
                    new FormulaRule("amount", "{amount} = {customer.credit}"),
                    new FormulaRule("guarded", "{guarded} = 10 WHEN {enabled}"),
                    new FormulaRule("manual", "{manual} = {manual} * 2"),
                    new FormulaRule("disabled", "{disabled} = 10", false),
                    new FormulaRule("conflict1", "{conflict} = {hidden}"),
                    new FormulaRule("conflict2", "{conflict} = 10 WHEN {enabled}")
            )).defaultEditor();
            assertThat(projected.formComputeRules()).isEmpty();
            assertThat(projected.fields().getFirst().readOnly().constant()).isTrue();
            assertThat(projected.fields().getFirst().required().constant()).isFalse();
            assertThat(projected.fields().subList(1, 5)).allSatisfy(field -> {
                assertThat(field.readOnly().constant()).isFalse();
                assertThat(field.required().constant()).isTrue();
            });
        }
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
        return new ResolvedViewFieldDescriptor(field, field.fieldName(), UiRule.constant(true), UiRule.constant(true),
                UiRule.constant(false), null, FieldValueType.DECIMAL, null, 1, null, null, null, null, null, null);
    }
}
