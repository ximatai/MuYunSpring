package net.ximatai.muyun.spring.common.formula;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaRuleExecutionPlanTest {
    private final FormulaEngine engine = new FormulaEngine();

    @Test
    void shouldOrderChainedAndDiamondCalculationsBeforeValidation() {
        FormulaRule first = calculation("first", "a", "{source} + 1");
        FormulaRule left = calculation("left", "b", "{a} + 1");
        FormulaRule right = calculation("right", "c", "{a} + 2");
        FormulaRule total = calculation("total", "result", "{b} + {c}");
        FormulaRule validation = new FormulaRule("finalValue", "{result} == 7", FormulaRuleKind.VALIDATION,
                FormulaRulePhase.BEFORE_SAVE, "result");

        FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(
                List.of(total, right, validation, left, first), Set.of("source", "a", "b", "c", "result"));

        assertThat(plan.calculationRules()).extracting(FormulaRule::id)
                .containsExactly("first", "right", "left", "total");
        assertThat(plan.validationRules()).extracting(FormulaRule::id).containsExactly("finalValue");
        assertThat(plan.inputFieldsByRule()).containsEntry("total", Set.of("b", "c"));
        Map<String, Object> values = new LinkedHashMap<>(Map.of("source", 1));

        FormulaExecutionResult result = plan.execute(engine, FormulaRuntimeData.of(values));

        assertThat(result.report().errors()).isEmpty();
        assertThat(values).containsEntry("result", 7d);
    }

    @Test
    void shouldRejectDuplicateWritersCyclesAndUndeclaredFields() {
        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(List.of(
                calculation("first", "result", "{source} + 1"),
                calculation("second", "result", "{source} + 2")), Set.of("source", "result")))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_DUPLICATE_WRITER");
        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(List.of(
                calculation("first", "a", "{b} + 1"),
                calculation("second", "b", "{a} + 1")), Set.of("a", "b")))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_DEPENDENCY_CYCLE");
        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(
                List.of(calculation("bad", "result", "{unknown} + 1")), Set.of("result")))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_UNKNOWN_FIELD");
    }

    @Test
    void shouldRejectDuplicateIdentifiersBeforeDependencyMapsCanLoseRules() {
        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(List.of(
                calculation("same", "first", "1"), calculation("same", "second", "{first} + 1")),
                Set.of("first", "second")))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_DUPLICATE_RULE_ID");
    }

    @Test
    void shouldRejectAFormulaWhoseDeclaredTargetDisagreesWithRootAssignment() {
        FormulaRule rule = calculation("bad", "result", "{other} = {source} + 1");

        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(
                List.of(rule), Set.of("source", "other", "result")))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_TARGET_MISMATCH");
    }

    @Test
    void shouldRequireWritableCalculationTargetsFromFieldDefinitions() {
        FormulaRule rule = calculation("resultCalc", "result", "{source} + 1");

        assertThatThrownBy(() -> FormulaRuleExecutionPlan.forMainRecord(List.of(rule), List.of(
                FormulaFieldDefinition.of("source", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("result", FormulaValueType.DECIMAL).readonly()
        )))
                .isInstanceOf(FormulaEvaluationException.class)
                .extracting(FormulaEvaluationException.class::cast)
                .extracting(FormulaEvaluationException::code)
                .isEqualTo("FORMULA_PLAN_TARGET_READ_ONLY");
    }

    private static FormulaRule calculation(String id, String target, String expression) {
        return new FormulaRule(id, expression, FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, target);
    }
}
