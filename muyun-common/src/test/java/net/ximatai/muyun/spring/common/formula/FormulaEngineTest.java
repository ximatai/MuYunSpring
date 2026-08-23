package net.ximatai.muyun.spring.common.formula;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaEngineTest {
    private final FormulaEngine engine = new FormulaEngine(
            Clock.fixed(Instant.parse("2026-06-01T02:03:04Z"), ZoneOffset.UTC)
    );

    @Test
    void shouldEvaluateArithmeticComparisonAndFunctions() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of(
                "amount", 120,
                "discount", 20,
                "name", "Contract"
        )));

        assertThat(engine.evaluateValue("({amount} - {discount}) * 2", data)).isEqualTo(200d);
        assertThat(engine.evaluateBoolean("{amount} >= 100 && {discount} == 20", data)).isTrue();
        assertThat(engine.evaluateValue("CONCAT(UPPER({name}), '-', ROUND(12.345, 2))", data))
                .isEqualTo("CONTRACT-12.35");
        assertThat(engine.evaluateValue("IF(ISNULL({missing}), 'empty', 'filled')", data)).isEqualTo("empty");
    }

    @Test
    void shouldApplyAssignmentsToMainRecordAndAggregateChildren() {
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("amount", 100);
        main.put("discount", 15);
        FormulaRuntimeData data = FormulaRuntimeData.of(main, Map.of(
                "items", List.of(
                        new LinkedHashMap<>(Map.of("qty", 2, "price", 10)),
                        new LinkedHashMap<>(Map.of("qty", 3, "price", 20))
                )
        ));

        FormulaExecutionResult result = engine.execute(List.of(
                new FormulaRule("net", "{netAmount} = {amount} - {discount}"),
                new FormulaRule("totalQty", "SUM({items.qty})", FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, "totalQty")
        ), data);

        assertThat(result.report().errors()).isEmpty();
        assertThat(result.changedFields()).containsExactly("netAmount", "totalQty");
        assertThat(main).containsEntry("netAmount", 85d);
        assertThat(main).containsEntry("totalQty", 5d);
    }

    @Test
    void shouldExposeReferencedFormulaFieldsFromParsedExpression() {
        assertThat(engine.referencedFields("{ total } = SUM({ items.lineAmount } = { items.qty } * {items.price})"))
                .containsExactlyInAnyOrder("total", "items.lineAmount", "items.qty", "items.price");
    }

    @Test
    void shouldExposeWhetherExpressionContainsAssignment() {
        assertThat(engine.containsAssignment("{amount} > 0")).isFalse();
        assertThat(engine.containsAssignment("{status} = 'submitted'")).isTrue();
    }

    @Test
    void shouldSupportDateAndDateTimeFunctions() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of(
                "signedDate", "2026-06-01",
                "signedAt", "2026-06-01T02:03:04Z"
        )));

        assertThat(engine.evaluateValue("TODAY()", data)).isEqualTo("2026-06-01");
        assertThat(engine.evaluateValue("NOW()", data)).isEqualTo("2026-06-01T02:03:04Z");
        assertThat(engine.evaluateValue("DATE_ADD({signedDate}, 3)", data)).isEqualTo("2026-06-04");
        assertThat(engine.evaluateValue("DATETIME_ADD({signedAt}, 2, 'HOUR')", data)).isEqualTo("2026-06-01T04:03:04Z");
        assertThat(engine.evaluateValue("DATE_DIFF_HOURS('2026-06-01T05:03:04Z', {signedAt})", data)).isEqualTo(3d);
    }

    @Test
    void shouldReportFormulaErrorsAndWarnings() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of("amount", 100));
        FormulaRuntimeData data = FormulaRuntimeData.of(main);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("missingTarget", "{amount} + 1"),
                new FormulaRule("badDate", "{date} = DATE_ADD('bad', 1)")
        ), data);

        assertThat(report.warnings()).isEmpty();
        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::ruleId)
                .containsExactly("missingTarget", "badDate");
        assertThat(report.errors()).first()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_ASSIGNMENT_REQUIRED");
    }

    @Test
    void shouldSupportValidationAndWarningRules() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of("amount", 80)));

        FormulaExecutionResult result = engine.execute(List.of(
                new FormulaRule("amountRequired", "{amount} >= 100", FormulaRuleKind.VALIDATION,
                        FormulaRulePhase.BEFORE_SAVE, "amount", FormulaIssueLevel.WARNING, "amount is low", false, true)
        ), data);

        FormulaRuntimeReport report = result.report();
        assertThat(report.errors()).isEmpty();
        assertThat(result.decisions()).singleElement()
                .extracting(FormulaRuleDecision::matched)
                .isEqualTo(false);
        assertThat(report.warnings()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::ruleId)
                .isEqualTo("amountRequired");
        assertThat(report.warnings()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::fieldPath)
                .isEqualTo("amount");
    }

    @Test
    void shouldShortCircuitLazyBranches() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of("enabled", false)));

        assertThat(engine.evaluateValue("IF({enabled}, DATE_ADD('bad', 1), 'skip')", data)).isEqualTo("skip");
        assertThat(engine.evaluateBoolean("false && DATE_ADD('bad', 1)", data)).isFalse();
        assertThat(engine.evaluateBoolean("true || DATE_ADD('bad', 1)", data)).isTrue();
    }

    @Test
    void shouldReportUnknownFunctionAndStrictUnknownField() {
        FormulaRuntimeData strictData = FormulaRuntimeData.strict(
                new LinkedHashMap<>(Map.of("amount", 100)),
                Map.of(),
                Set.of("amount", "date")
        );

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("unknownFunction", "{date} = UNKNOWN_FN({amount})"),
                new FormulaRule("unknownField", "{date} = {missing} + 1")
        ), strictData);

        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::ruleId)
                .containsExactly("unknownFunction", "unknownField");
        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::code)
                .containsExactly("FORMULA_UNKNOWN_FUNCTION", "FORMULA_UNKNOWN_FIELD");
        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::fieldPath)
                .containsExactly(null, "missing");
        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::message)
                .anyMatch(message -> message.contains("unknown formula function"))
                .anyMatch(message -> message.contains("unknown formula field"));
    }

    @Test
    void shouldRejectAggregateAcrossMultipleChildTables() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(), Map.of(
                "items", List.of(new LinkedHashMap<>(Map.of("amount", 10))),
                "payments", List.of(new LinkedHashMap<>(Map.of("amount", 5)))
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("mixedAggregate", "{total} = SUM({items.amount}, {payments.amount})")
        ), data);

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_AGGREGATE_SCOPE_ERROR");
    }

    @Test
    void shouldRollbackNestedAssignmentsWhenRuleFails() {
        Map<String, Object> main = new LinkedHashMap<>();
        FormulaRuntimeData data = FormulaRuntimeData.of(main);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("nested", "({a} = 1) + UNKNOWN_FN()", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, null, FormulaIssueLevel.ERROR, null, true, true),
                new FormulaRule("next", "{b} = 2")
        ), data);

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_UNKNOWN_FUNCTION");
        assertThat(main).doesNotContainKeys("a", "b");
    }

    @Test
    void shouldCommitNestedAssignmentsAfterRuleSucceeds() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of("flag", true));
        FormulaRuntimeData data = FormulaRuntimeData.of(main);

        FormulaExecutionResult result = engine.execute(List.of(
                new FormulaRule("nested", "IF({flag}, {a} = 1, {b} = 2) + ({c} = {a} + 3)")
        ), data);

        assertThat(result.report().errors()).isEmpty();
        assertThat(result.changedFields()).containsExactly("a", "c");
        assertThat(main).containsEntry("a", 1d)
                .containsEntry("c", 4d)
                .doesNotContainKey("b");
    }

    @Test
    void shouldLetNestedAssignmentReadStagedValuesFromLeftToRight() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of("a", 10));
        FormulaRuntimeData data = FormulaRuntimeData.of(main);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("leftToRight", "({a} = 1) + ({b} = {a} + 2) + ({a} = 5)")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(main).containsEntry("a", 5d)
                .containsEntry("b", 3d);
    }

    @Test
    void shouldNormalizeTypedFieldWrites() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of(
                "amount", 12,
                "signedDate", "2026-06-01"
        ));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of(), List.of(
                FormulaFieldDefinition.of("amount", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("approved", FormulaValueType.BOOLEAN),
                FormulaFieldDefinition.of("signedDate", FormulaValueType.DATE),
                FormulaFieldDefinition.of("signedAt", FormulaValueType.TIMESTAMP)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("total", "{total} = {amount} * 2"),
                new FormulaRule("approved", "{approved} = 1"),
                new FormulaRule("signedDate", "{signedDate} = DATE_ADD({signedDate}, 1)"),
                new FormulaRule("signedAt", "{signedAt} = DATETIME_ADD('2026-06-01T02:03:04Z', 2, 'HOUR')")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(main)
                .containsEntry("total", new BigDecimal("24.0"))
                .containsEntry("approved", true)
                .containsEntry("signedDate", LocalDate.parse("2026-06-02"))
                .containsEntry("signedAt", Instant.parse("2026-06-01T04:03:04Z"));
    }

    @Test
    void shouldRejectTypedWriteMismatchReadonlyAndRequiredEmpty() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of("amount", 12));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of(), List.of(
                FormulaFieldDefinition.of("amount", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("count", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("name", FormulaValueType.STRING).readonly(),
                FormulaFieldDefinition.of("requiredName", FormulaValueType.STRING).asRequired()
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("count", "{count} = 12.5"),
                new FormulaRule("name", "{name} = 'readonly'"),
                new FormulaRule("requiredName", "{requiredName} = null")
        ), data);

        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::code)
                .containsExactly("FORMULA_TYPE_MISMATCH", "FORMULA_FIELD_NOT_WRITABLE", "FORMULA_REQUIRED_FIELD_EMPTY");
        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::fieldPath)
                .containsExactly("count", "name", "requiredName");
        assertThat(main).doesNotContainKeys("count", "name", "requiredName");
    }

    @Test
    void shouldKeepEmptyStringForTextFieldsAndRejectUnknownTypedFields() {
        Map<String, Object> main = new LinkedHashMap<>(Map.of("name", "old"));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of(), List.of(
                FormulaFieldDefinition.of("name", FormulaValueType.STRING)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("name", "{name} = ''"),
                new FormulaRule("missing", "{missing} = 1")
        ), data);

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_UNKNOWN_FIELD");
        assertThat(main).containsEntry("name", "");
    }

    @Test
    void shouldTreatDeclaredButEmptyChildTableAsEmptyRows() {
        Map<String, Object> main = new LinkedHashMap<>();
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of(), List.of(
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("total", "{total} = SUM({items.qty})")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(main).containsEntry("total", new BigDecimal("0.0"));
    }

    @Test
    void shouldCommitChildRowAssignmentsAfterAggregateRuleSucceeds() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> first = new LinkedHashMap<>(Map.of("qty", 2, "price", 10));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("qty", 3, "price", 20));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(first, second)), List.of(
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("items.price", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.lineAmount", FormulaValueType.DECIMAL)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("lineAndTotal", "{total} = SUM({items.lineAmount} = {items.qty} * {items.price})")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(first).containsEntry("lineAmount", new BigDecimal("20.0"));
        assertThat(second).containsEntry("lineAmount", new BigDecimal("60.0"));
        assertThat(main).containsEntry("total", new BigDecimal("80.0"));
    }

    @Test
    void shouldApplyTargetFieldCalculationToEachChildRow() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> first = new LinkedHashMap<>(Map.of("qty", 2, "price", 10));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("qty", 3, "price", 20));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(first, second)), List.of(
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("items.price", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.lineAmount", FormulaValueType.DECIMAL)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("lineAmount", "{items.qty} * {items.price}",
                        FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, "items.lineAmount")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(first).containsEntry("lineAmount", new BigDecimal("20.0"));
        assertThat(second).containsEntry("lineAmount", new BigDecimal("60.0"));
    }

    @Test
    void shouldSupportFilteredAggregateAndFirstDefaultValue() {
        Map<String, Object> main = new LinkedHashMap<>();
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(
                new LinkedHashMap<>(Map.of("amount", 10, "enabled", true, "name", "A")),
                new LinkedHashMap<>(Map.of("amount", 20, "enabled", false, "name", "B")),
                new LinkedHashMap<>(Map.of("amount", 30, "enabled", true, "name", "C"))
        )), List.of(
                FormulaFieldDefinition.of("enabledTotal", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("firstEnabledName", FormulaValueType.STRING),
                FormulaFieldDefinition.of("items.amount", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.enabled", FormulaValueType.BOOLEAN),
                FormulaFieldDefinition.of("items.name", FormulaValueType.STRING)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("enabledTotal", "{enabledTotal} = SUM({items.amount}, WHERE({items.enabled} == true))"),
                new FormulaRule("firstName", "{firstEnabledName} = GETFIRSTORDEFAULTVALUE({items.name}, FILTER({items.enabled} == true))")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(main)
                .containsEntry("enabledTotal", new BigDecimal("40.0"))
                .containsEntry("firstEnabledName", "A");
    }

    @Test
    void shouldRejectWhereOutsideAggregate() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of("amount", 10)));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("badWhere", "{matched} = WHERE({amount} > 0)")
        ), data);

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_AGGREGATE_CONDITION_ONLY");
    }

    @Test
    void shouldRejectInvalidAggregateConditionShape() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(), Map.of(
                "items", List.of(new LinkedHashMap<>(Map.of("amount", 10, "enabled", true)))
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("blankWhere", "{total} = SUM({items.amount}, WHERE())"),
                new FormulaRule("multiWhere", "{count} = COUNT({items.amount}, WHERE({items.enabled}, true))"),
                new FormulaRule("duplicateWhere", "{max} = MAX({items.amount}, WHERE({items.enabled}), FILTER(true))")
        ), data);

        assertThat(report.errors()).extracting(FormulaRuntimeReport.Issue::code)
                .containsExactly(
                        "FORMULA_AGGREGATE_CONDITION_INVALID",
                        "FORMULA_AGGREGATE_CONDITION_INVALID",
                        "FORMULA_AGGREGATE_CONDITION_INVALID"
                );
    }

    @Test
    void shouldRejectChildTargetFormulaDirectlyReadingOtherChildTable() {
        assertThatThrownBy(() -> engine.validateTargetFieldExpressionScope(
                "items.lineAmount",
                "{discounts.rate} * {items.price}"
        ))
                .isInstanceOf(FormulaEvaluationException.class)
                .hasMessageContaining("cannot directly read another child table");

        engine.validateTargetFieldExpressionScope(
                "items.lineAmount",
                "{items.qty} * {items.price} + SUM({discounts.amount})"
        );
    }

    @Test
    void shouldNotRewriteChildRowsForReadOnlyAggregate() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> immutableChild = Map.of("qty", 2);
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(immutableChild)), List.of(
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("total", "SUM({items.qty})", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.BEFORE_SAVE, "total")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(main).containsEntry("total", new BigDecimal("2.0"));
    }

    @Test
    void shouldNotCommitMainWriteWhenChildWriteCommitFails() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> immutableChild = Map.of("qty", 2, "price", 10);
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(immutableChild)), List.of(
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("items.price", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.lineAmount", FormulaValueType.DECIMAL)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("lineAndTotal", "{total} = SUM({items.lineAmount} = {items.qty} * {items.price})")
        ), data);

        assertThat(report.errors()).isNotEmpty();
        assertThat(main).doesNotContainKey("total");
    }

    @Test
    void shouldRollbackCommittedChildRowsWhenLaterChildWriteFails() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> first = new LinkedHashMap<>(Map.of("qty", 2, "price", 10));
        Map<String, Object> immutableSecond = Map.of("qty", 3, "price", 20);
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("items", List.of(first, immutableSecond)), List.of(
                FormulaFieldDefinition.of("total", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.qty", FormulaValueType.INTEGER),
                FormulaFieldDefinition.of("items.price", FormulaValueType.DECIMAL),
                FormulaFieldDefinition.of("items.lineAmount", FormulaValueType.DECIMAL)
        ));

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("lineAndTotal", "{total} = SUM({items.lineAmount} = {items.qty} * {items.price})")
        ), data);

        assertThat(report.errors()).isNotEmpty();
        assertThat(first).doesNotContainKey("lineAmount");
        assertThat(immutableSecond).doesNotContainKey("lineAmount");
        assertThat(main).doesNotContainKey("total");
    }

    @Test
    void shouldRejectAssignmentInValidationRule() {
        Map<String, Object> main = new LinkedHashMap<>();
        FormulaRuntimeData data = FormulaRuntimeData.of(main);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("badValidation", "{a} = 1", FormulaRuleKind.VALIDATION,
                        FormulaRulePhase.BEFORE_SAVE, "a", FormulaIssueLevel.ERROR, null, false, true)
        ), data);

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_ASSIGNMENT_NOT_ALLOWED");
        assertThat(main).doesNotContainKey("a");
    }

    @Test
    void shouldReadExpressionFromJsonLikePayload() {
        FormulaRuntimeData data = FormulaRuntimeData.of(new LinkedHashMap<>(Map.of("amount", 12)));

        assertThat(engine.evaluateValue("{\"expression\":\"{amount} * 2\"}", data)).isEqualTo(24d);
    }

    @Test
    void shouldClearSiblingRowsWhenCurrentChildRowEnablesExclusiveFlag() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> previous = new LinkedHashMap<>(Map.of("primaryPosition", true));
        Map<String, Object> current = new LinkedHashMap<>(Map.of("primaryPosition", true));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("positions", List.of(previous, current)), List.of(
                FormulaFieldDefinition.of("positions.primaryPosition", FormulaValueType.BOOLEAN)
        )).withChangeScope("positions", current);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("primaryPositionExclusive",
                        "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(previous).containsEntry("primaryPosition", false);
        assertThat(current).containsEntry("primaryPosition", true);
    }

    @Test
    void shouldLeaveSiblingRowsUntouchedWhenConditionalAssignmentIsNotTriggered() {
        Map<String, Object> main = new LinkedHashMap<>();
        Map<String, Object> previous = new LinkedHashMap<>(Map.of("primaryPosition", true));
        Map<String, Object> current = new LinkedHashMap<>(Map.of("primaryPosition", false));
        FormulaRuntimeData data = FormulaRuntimeData.typed(main, Map.of("positions", List.of(previous, current)), List.of(
                FormulaFieldDefinition.of("positions.primaryPosition", FormulaValueType.BOOLEAN)
        )).withChangeScope("positions", current);

        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("primaryPositionExclusive",
                        "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}")
        ), data);

        assertThat(report.errors()).isEmpty();
        assertThat(previous).containsEntry("primaryPosition", true);
        assertThat(current).containsEntry("primaryPosition", false);
    }

    @Test
    void shouldRequireAnExplicitChangedChildRowForSiblingAssignment() {
        Map<String, Object> sibling = new LinkedHashMap<>(Map.of("primaryPosition", true));
        FormulaRuntimeReport report = engine.apply(List.of(
                new FormulaRule("primaryPositionExclusive",
                        "others({positions.primaryPosition}) = false WHEN {positions.primaryPosition}")
        ), FormulaRuntimeData.typed(new LinkedHashMap<>(), Map.of("positions", List.of(sibling)), List.of(
                FormulaFieldDefinition.of("positions.primaryPosition", FormulaValueType.BOOLEAN)
        )));

        assertThat(report.errors()).singleElement()
                .extracting(FormulaRuntimeReport.Issue::code)
                .isEqualTo("FORMULA_CHANGE_SCOPE_REQUIRED");
        assertThat(sibling).containsEntry("primaryPosition", true);
    }
}
