package net.ximatai.muyun.spring.common.formula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An executable plan for deterministic main-record save rules.
 *
 * <p>The plan does not introduce a second expression language. It orders existing
 * {@link FormulaRule FormulaRules} and delegates execution to {@link FormulaEngine}. Calculation
 * targets must be main-record fields; inputs can include any declared path, including existing
 * child-table inputs used by aggregate calculations. Validation rules are always ordered after
 * every calculation.</p>
 */
public final class FormulaRuleExecutionPlan {
    private final List<FormulaRule> calculationRules;
    private final List<FormulaRule> validationRules;
    private final List<FormulaRule> orderedRules;
    private final Map<String, Set<String>> inputFieldsByRule;
    private final Map<String, String> calculationTargetFieldsByRule;

    private FormulaRuleExecutionPlan(List<FormulaRule> calculationRules,
                                     List<FormulaRule> validationRules,
                                     Map<String, Set<String>> inputFieldsByRule,
                                     Map<String, String> calculationTargetFieldsByRule) {
        this.calculationRules = List.copyOf(calculationRules);
        this.validationRules = List.copyOf(validationRules);
        List<FormulaRule> ordered = new ArrayList<>(calculationRules);
        ordered.addAll(validationRules);
        this.orderedRules = List.copyOf(ordered);
        this.inputFieldsByRule = Map.copyOf(inputFieldsByRule);
        this.calculationTargetFieldsByRule = Map.copyOf(calculationTargetFieldsByRule);
    }

    /**
     * Builds a plan for main-record calculations and validations. The supplied field paths are
     * the declaration boundary: every target and value-side input must belong to this set.
     */
    public static FormulaRuleExecutionPlan forMainRecord(List<FormulaRule> rules,
                                                           Collection<String> declaredFieldPaths) {
        return build(rules, writableFields(declaredFieldPaths));
    }

    /**
     * Builds a plan from field declarations, retaining writable policy for calculation targets.
     * This is the preferred entry point for metadata and descriptor compilers.
     */
    public static FormulaRuleExecutionPlan forMainRecord(List<FormulaRule> rules,
                                                           List<FormulaFieldDefinition> declaredFields) {
        LinkedHashMap<String, Boolean> fields = new LinkedHashMap<>();
        if (declaredFields != null) {
            declaredFields.stream().filter(Objects::nonNull).forEach(field ->
                    fields.put(field.fieldPath().dataIndex(), field.writable()));
        }
        return build(rules, fields);
    }

    private static FormulaRuleExecutionPlan build(List<FormulaRule> rules, Map<String, Boolean> declared) {
        FormulaEngine engine = new FormulaEngine();
        List<RuleDetails> calculations = new ArrayList<>();
        List<RuleDetails> validations = new ArrayList<>();
        Map<String, Set<String>> inputs = new LinkedHashMap<>();
        Map<String, String> targets = new LinkedHashMap<>();
        if (rules == null) {
            return new FormulaRuleExecutionPlan(List.of(), List.of(), inputs, targets);
        }
        for (int index = 0; index < rules.size(); index++) {
            FormulaRule rule = rules.get(index);
            if (rule == null || !rule.enabled()) {
                continue;
            }
            if (inputs.containsKey(rule.id())) {
                throw failure("FORMULA_PLAN_DUPLICATE_RULE_ID", rule, null,
                        "formula plan requires unique rule identifiers");
            }
            FormulaExpressionSupport.ParsedExpression parsed = engine.parse(rule.id(), rule.expression());
            if (parsed == null) {
                throw failure("FORMULA_PLAN_EXPRESSION_REQUIRED", rule, null,
                        "formula plan requires an expression");
            }
            RuleDetails details = inspect(rule, parsed, index, declared);
            inputs.put(rule.id(), details.inputs());
            if (rule.kind() == FormulaRuleKind.CALCULATION) {
                calculations.add(details);
                targets.put(rule.id(), details.target());
            } else if (rule.kind() == FormulaRuleKind.VALIDATION) {
                validations.add(details);
            } else {
                throw failure("FORMULA_PLAN_RULE_KIND_UNSUPPORTED", rule, null,
                        "main-record save plan only supports calculation and validation rules");
            }
        }
        return new FormulaRuleExecutionPlan(orderCalculations(calculations), orderValidations(validations), inputs, targets);
    }

    public List<FormulaRule> calculationRules() {
        return calculationRules;
    }

    public List<FormulaRule> validationRules() {
        return validationRules;
    }

    /** Ordered calculations followed by validations, suitable for a shared server or preview execution path. */
    public List<FormulaRule> orderedRules() {
        return orderedRules;
    }

    /** Value-side inputs for each declared rule, excluding calculation assignment targets. */
    public Map<String, Set<String>> inputFieldsByRule() {
        return inputFieldsByRule;
    }

    /** Declared main-record target for each calculation rule. */
    public Map<String, String> calculationTargetFieldsByRule() {
        return calculationTargetFieldsByRule;
    }

    public FormulaExecutionResult execute(FormulaEngine engine, FormulaEvaluationContext context) {
        return Objects.requireNonNull(engine, "engine must not be null").execute(orderedRules, context);
    }

    private static RuleDetails inspect(FormulaRule rule,
                                       FormulaExpressionSupport.ParsedExpression parsed,
                                       int index,
                                       Map<String, Boolean> declared) {
        Set<String> inputs = FormulaExpressionSupport.valueSideReferencedFields(parsed.ast());
        for (String input : inputs) {
            requireDeclared(rule, input, declared, "formula input field is not declared");
        }
        if (rule.kind() == FormulaRuleKind.VALIDATION) {
            if (FormulaExpressionSupport.containsAssignment(parsed.ast())) {
                throw failure("FORMULA_PLAN_VALIDATION_ASSIGNMENT", rule, rule.targetField(),
                        "validation formula must be read-only");
            }
            if (rule.targetField() != null) {
                requireDeclared(rule, rule.targetField(), declared, "formula validation target is not declared");
                requireMainField(rule, rule.targetField(), "formula validation target must be a main-record field");
            }
            return new RuleDetails(rule, null, inputs, index);
        }
        if (rule.kind() != FormulaRuleKind.CALCULATION) {
            return new RuleDetails(rule, null, inputs, index);
        }
        if (FormulaExpressionSupport.hasNestedAssignment(parsed.ast())) {
            throw failure("FORMULA_PLAN_NESTED_ASSIGNMENT", rule, rule.targetField(),
                    "calculation formula must use one root assignment");
        }
        String assignedTarget = FormulaExpressionSupport.rootAssignedField(parsed.ast());
        String target = rule.targetField() == null ? assignedTarget : rule.targetField();
        if (target == null) {
            throw failure("FORMULA_PLAN_CALCULATION_TARGET_REQUIRED", rule, null,
                    "calculation formula requires a target field or root assignment");
        }
        if (assignedTarget != null && !target.equals(assignedTarget)) {
            throw failure("FORMULA_PLAN_TARGET_MISMATCH", rule, target,
                    "calculation target must match its root assignment");
        }
        requireDeclared(rule, target, declared, "formula calculation target is not declared");
        requireMainField(rule, target, "formula calculation target must be a main-record field");
        if (!Boolean.TRUE.equals(declared.get(target))) {
            throw failure("FORMULA_PLAN_TARGET_READ_ONLY", rule, target,
                    "formula calculation target must be writable: " + target);
        }
        return new RuleDetails(rule, target, inputs, index);
    }

    private static List<FormulaRule> orderCalculations(List<RuleDetails> calculations) {
        Map<String, RuleDetails> writerByTarget = new LinkedHashMap<>();
        for (RuleDetails calculation : calculations) {
            RuleDetails previous = writerByTarget.putIfAbsent(calculation.target(), calculation);
            if (previous != null) {
                throw failure("FORMULA_PLAN_DUPLICATE_WRITER", calculation.rule(), calculation.target(),
                        "multiple calculation rules write field " + calculation.target() + ": "
                                + previous.rule().id() + ", " + calculation.rule().id());
            }
        }
        Map<RuleDetails, Set<RuleDetails>> dependents = new LinkedHashMap<>();
        Map<RuleDetails, Integer> incoming = new LinkedHashMap<>();
        calculations.forEach(rule -> {
            dependents.put(rule, new LinkedHashSet<>());
            incoming.put(rule, 0);
        });
        for (RuleDetails calculation : calculations) {
            for (String input : calculation.inputs()) {
                RuleDetails writer = writerByTarget.get(input);
                // A declared scalar reference is represented as a dotted input while its root
                // reference id remains the persisted main-record field.  When a preceding
                // calculation changes that root, the reference is re-read by the runtime and
                // this dependency makes the consuming calculation run afterwards.  Existing
                // child-table calculations have no main-record writer for their table key.
                if (writer == null) {
                    int dot = input.indexOf('.');
                    if (dot > 0) {
                        writer = writerByTarget.get(input.substring(0, dot));
                    }
                }
                if (writer != null && dependents.get(writer).add(calculation)) {
                    incoming.compute(calculation, (ignored, count) -> count + 1);
                }
            }
        }
        Deque<RuleDetails> ready = new ArrayDeque<>();
        calculations.stream().filter(rule -> incoming.get(rule) == 0)
                .sorted(Comparator.comparingInt(RuleDetails::index)).forEach(ready::addLast);
        List<FormulaRule> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            RuleDetails current = ready.removeFirst();
            ordered.add(current.rule());
            dependents.get(current).stream().sorted(Comparator.comparingInt(RuleDetails::index)).forEach(next -> {
                int remaining = incoming.compute(next, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(next);
                }
            });
        }
        if (ordered.size() != calculations.size()) {
            List<String> cycle = calculations.stream().filter(rule -> incoming.get(rule) > 0)
                    .sorted(Comparator.comparingInt(RuleDetails::index)).map(rule -> rule.rule().id()).toList();
            throw new FormulaEvaluationException("FORMULA_PLAN_DEPENDENCY_CYCLE",
                    "formula calculation dependency cycle: " + String.join(", ", cycle));
        }
        return ordered;
    }

    private static List<FormulaRule> orderValidations(List<RuleDetails> validations) {
        return validations.stream().sorted(Comparator.comparingInt(RuleDetails::index)).map(RuleDetails::rule).toList();
    }

    private static Map<String, Boolean> writableFields(Collection<String> declaredFieldPaths) {
        LinkedHashMap<String, Boolean> declared = new LinkedHashMap<>();
        if (declaredFieldPaths != null) {
            declaredFieldPaths.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(field -> !field.isEmpty()).forEach(field -> declared.put(field, true));
        }
        return Map.copyOf(declared);
    }

    private static void requireDeclared(FormulaRule rule, String field, Map<String, Boolean> declared, String message) {
        if (!declared.containsKey(field)) {
            throw failure("FORMULA_PLAN_UNKNOWN_FIELD", rule, field, message + ": " + field);
        }
    }

    private static void requireMainField(FormulaRule rule, String field, String message) {
        if (FormulaFieldPath.parse(field).tableKey() != null) {
            throw failure("FORMULA_PLAN_MAIN_RECORD_TARGET_REQUIRED", rule, field, message + ": " + field);
        }
    }

    private static FormulaEvaluationException failure(String code, FormulaRule rule, String field, String message) {
        return new FormulaEvaluationException(code, field, message + " [rule=" + rule.id() + "]");
    }

    private record RuleDetails(FormulaRule rule, String target, Set<String> inputs, int index) {
    }
}
