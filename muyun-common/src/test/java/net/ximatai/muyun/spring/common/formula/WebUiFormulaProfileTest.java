package net.ximatai.muyun.spring.common.formula;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebUiFormulaProfileTest {
    private final FormulaEngine engine = new FormulaEngine();

    @Test
    void compilesFormulaEngineAstAndRetainsItsReferencedFields() {
        FormulaProgram program = engine.compileWebUiProgram(
                "PRESENT({entryType}) && IN({moduleKind}, 'standard', 'system')");

        assertThat(program.schemaVersion()).isEqualTo(FormulaProgram.CURRENT_SCHEMA_VERSION);
        assertThat(program.profile()).isEqualTo(FormulaExecutionProfile.WEB_UI);
        assertThat(program.referencedFields()).containsExactlyInAnyOrder("entryType", "moduleKind");
        assertThat(program.root().kind()).isEqualTo(FormulaNode.Kind.BINARY);
        assertThat(program.root().operator()).isEqualTo("&&");
        assertThat(engine.evaluateBoolean("PRESENT({entryType}) && IN({moduleKind}, 'standard', 'system')", FormulaRuntimeData.of(Map.of(
                "entryType", "route", "moduleKind", "system")))).isTrue();
    }

    @Test
    void acceptsOnlyDeterministicDraftLocalBooleanOperatorsAndFunctions() {
        FormulaProgram program = engine.compileWebUiProgram("ISNULL({comment}) || {approved} == true");

        assertThat(program.root().operator()).isEqualTo("||");
        assertThat(engine.evaluateBoolean("ISNULL({comment}) || {approved} == true", FormulaRuntimeData.of(Map.of("approved", true))))
                .isTrue();
    }

    @Test
    void rejectsAssignmentsAndServerOnlyFunctionsButRetainsDescriptorValidatedReferencePaths() {
        assertThatThrownBy(() -> engine.compileWebUiProgram("{status} = 'enabled'"))
                .hasMessageContaining("WEB_UI profile");
        assertThatThrownBy(() -> engine.compileWebUiProgram("NOW() == '2026-01-01'"))
                .hasMessageContaining("WEB_UI profile");
        FormulaProgram program = engine.compileWebUiProgram("PRESENT({moduleAlias.entryType})");
        assertThat(program.referencedFields()).containsExactly("moduleAlias.entryType");
    }

    @Test
    void keepsInComparisonSemanticsConsistentWithFormulaEquality() {
        assertThat(engine.evaluateBoolean("IN({priority}, 2, '3')", FormulaRuntimeData.of(Map.of("priority", "2"))))
                .isTrue();
    }

    @Test
    void compilesDeterministicAssignmentAsSharedFormComputeProgram() {
        FormulaProgram program = engine.compileFormComputeProgram(
                "{amount} = ({quantity} * {unitPrice}) + 2");

        assertThat(program.profile()).isEqualTo(FormulaExecutionProfile.FORM_COMPUTE);
        assertThat(program.root().kind()).isEqualTo(FormulaNode.Kind.ASSIGN);
        assertThat(program.root().operator()).isEqualTo("=");
        assertThat(program.root().arguments().getFirst().field()).isEqualTo("amount");
        assertThat(program.referencedFields()).containsExactlyInAnyOrder("amount", "quantity", "unitPrice");
        assertThat(engine.evaluateValue("({quantity} * {unitPrice}) + 2", FormulaRuntimeData.of(Map.of(
                "quantity", "3", "unitPrice", 4)))).isEqualTo(14d);
    }

    @Test
    void scopesQualifiedRelationCalculationForTheBrowserWithoutReparsingItsExpression() {
        FormulaProgram program = engine.compileRelationFormComputeProgram(
                "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}", "positions");

        assertThat(program.root().arguments().getFirst().kind()).isEqualTo(FormulaNode.Kind.OTHERS);
        assertThat(program.root().arguments().getFirst().field()).isEqualTo("primaryPosition");
        assertThat(program.root().arguments().get(2).field()).isEqualTo("primaryPosition");
        assertThat(program.referencedFields()).containsExactly("primaryPosition");
    }

    @Test
    void compilesDateNumericFormatAndSingleTableAggregateProgramsForFormCompute() {
        FormulaProgram dateProgram = engine.compileFormComputeProgram(
                "{remindDate} = DATE_ADD({signedDate}, 7)");
        FormulaProgram timeProgram = engine.compileFormComputeProgram(
                "{remindAt} = DATETIME_ADD({signedAt}, 2, 'HOUR')");
        FormulaProgram formatProgram = engine.compileFormComputeProgram(
                "{displayAmount} = FORMAT_DECIMAL({amount}, 2)");
        FormulaProgram aggregateProgram = engine.compileFormComputeProgram(
                "{totalAmount} = SUM({lines.amount})");

        assertThat(dateProgram.referencedFields()).containsExactlyInAnyOrder("remindDate", "signedDate");
        assertThat(timeProgram.referencedFields()).containsExactlyInAnyOrder("remindAt", "signedAt");
        assertThat(formatProgram.referencedFields()).containsExactlyInAnyOrder("displayAmount", "amount");
        assertThat(aggregateProgram.referencedFields()).containsExactlyInAnyOrder("totalAmount", "lines.amount");
    }

    @Test
    void rejectsUnsafeFormComputeProgramsBeforeTheyReachAClient() {
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{items.amount} = 1"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{amount} = ({other} = 1)"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{amount} = SUM({amount})"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{amount} = SUM({items.amount}, {items.tax})"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{amount} = FORMAT_DECIMAL({amount}, 13)"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("{amount} = NaN"))
                .hasMessageContaining("FORM_COMPUTE profile");
        assertThatThrownBy(() -> engine.compileFormComputeProgram("PRESENT({amount})"))
                .hasMessageContaining("FORM_COMPUTE profile");
    }

    @Test
    void preservesSignedExponentSyntaxInTheSharedFormulaParser() {
        FormulaProgram program = engine.compileFormComputeProgram("{amount} = -1e-3");

        assertThat(program.root().arguments().get(1).arguments().getFirst().value()).isEqualTo(0.001d);
        assertThat(engine.evaluateValue("-1e-3", FormulaRuntimeData.of(Map.of()))).isEqualTo(-0.001d);
    }

    @Test
    void compilesPortableMainRecordPredicateForBrowserPreSaveValidation() {
        FormulaProgram program = engine.compileFormValidationProgram("{contractAmount} > 0 && PRESENT({contractName})");

        assertThat(program.profile()).isEqualTo(FormulaExecutionProfile.FORM_VALIDATION);
        assertThat(program.referencedFields()).containsExactlyInAnyOrder("contractAmount", "contractName");
        assertThatThrownBy(() -> engine.compileFormValidationProgram("{contractAmount} = 1"))
                .hasMessageContaining("FORM_VALIDATION profile");
        assertThatThrownBy(() -> engine.compileFormValidationProgram("SUM({lines.amount}) > 0"))
                .hasMessageContaining("FORM_VALIDATION profile");
    }

    @Test
    void compilesPresentationOnlyPageTextWithoutReusingBooleanOrMutationProfiles() {
        FormulaProgram program = engine.compilePageTextProgram("{selection.label} + ' · ' + {selection.secondaryLabel}");

        assertThat(program.profile()).isEqualTo(FormulaExecutionProfile.PAGE_TEXT);
        assertThat(program.referencedFields()).containsExactly("selection.label", "selection.secondaryLabel");
        assertThatThrownBy(() -> engine.compilePageTextProgram("PRESENT({selection.label})"))
                .hasMessageContaining("PAGE_TEXT profile");
        assertThatThrownBy(() -> engine.compilePageTextProgram("{selection.label} = '平台角色'"))
                .hasMessageContaining("PAGE_TEXT profile");
    }
}
