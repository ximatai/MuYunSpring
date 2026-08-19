package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import net.ximatai.muyun.spring.common.formula.FormulaNode;

class UiFormulaTest {
    @Test
    void compilesFormulaEngineExpressionsToServerIssuedWebUiAst() {
        UiFormula formula = UiFormula.booleanExpression("PRESENT({directoryId})");
        assertThat(formula.expression()).isEqualTo("PRESENT({directoryId})");
        assertThat(formula.program().root().kind()).isEqualTo(FormulaNode.Kind.FUNCTION);
        assertThat(formula.program().root().operator()).isEqualTo("PRESENT");
        assertThat(formula.program().root().arguments().getFirst().field()).isEqualTo("directoryId");
        assertThat(UiFormula.booleanExpression("!(PRESENT({directoryId}))").expression())
                .isEqualTo("!(PRESENT({directoryId}))");
        assertThat(UiFormula.booleanExpression("PRESENT({directoryId}) && !(PRESENT({id}))").expression())
                .isEqualTo("PRESENT({directoryId}) && !(PRESENT({id}))");
    }

    @Test
    void rejectsServerOnlyFormulaEngineExpressionsFromUiDescriptors() {
        assertThatThrownBy(() -> UiFormula.booleanExpression("NOW() == '2026-01-01'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEB_UI profile");
        assertThatThrownBy(() -> UiFormula.booleanExpression("PRESENT({directory.id})"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEB_UI profile");
    }

    @Test
    void acceptsOnlyWhitelistedLiteralPredicates() {
        assertThat(UiFormula.booleanExpression("{entryType} == 'route' && IN({moduleKind}, 'standard', 'system')")
                .expression()).isEqualTo("{entryType} == 'route' && IN({moduleKind}, 'standard', 'system')");
        assertThat(UiFormula.booleanExpression("{enabled} != false").expression()).isEqualTo("{enabled} != false");
        assertThatThrownBy(() -> UiFormula.booleanExpression("{entryType} === 'route'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEB_UI profile");
        assertThatThrownBy(() -> UiFormula.booleanExpression("IN({entryType}, otherField)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEB_UI profile");
    }

    @Test
    void reportsEveryReferencedFieldFromTheIssuedAst() {
        assertThat(UiFormula.booleanExpression("PRESENT({kind}) && IN({level}, 'gold')").program().root().arguments())
                .hasSize(2);
        assertThat(new net.ximatai.muyun.spring.common.formula.FormulaEngine()
                .compileWebUiProgram("PRESENT({kind}) && IN({level}, 'gold')").referencedFields())
                .containsExactlyInAnyOrder("kind", "level");
    }

    @Test
    void enabledWhenSerializesThePortableNegationConsumedByTheWebClient() {
        ViewFieldDefinition field = ViewFieldDefinition.field("fileId")
                .enabledWhen(UiFormula.booleanExpression("PRESENT({directoryId})"))
                .build();

        assertThat(field.readOnly().formula().expression()).isEqualTo("!(PRESENT({directoryId}))");
    }

    @Test
    void enabledWhenNegatesPortableConjunctions() {
        ViewFieldDefinition field = ViewFieldDefinition.field("fileId")
                .enabledWhen(UiFormula.booleanExpression("PRESENT({directoryId}) && !(PRESENT({id}))"))
                .build();

        assertThat(field.readOnly().formula().expression())
                .isEqualTo("!(PRESENT({directoryId}) && !(PRESENT({id})))");
    }

    @Test
    void enabledWhenDoesNotCancelTheNegationOfACompoundPredicate() {
        ViewFieldDefinition field = ViewFieldDefinition.field("fileId")
                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({directoryId}) && PRESENT({id}))"))
                .build();

        assertThat(field.readOnly().formula().expression())
                .isEqualTo("!(!(PRESENT({directoryId}) && PRESENT({id})))");
    }
}
