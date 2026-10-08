package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.Collection;

@Service
public class WorkflowConditionService {
    private final ModuleRecordFacts facts;
    private final FormulaEngine formulas = new FormulaEngine();

    public WorkflowConditionService(ModuleRecordFacts facts) { this.facts = Objects.requireNonNull(facts); }

    public void validate(String expression) {
        if (expression == null || expression.isBlank()) return;
        try { formulas.parse("workflow", expression); }
        catch (FormulaEvaluationException invalid) { throw new PlatformException("流程条件公式不合法: " + invalid.getMessage(), invalid); }
        if (!formulas.assignedFields(expression).isEmpty())
            throw new PlatformException("workflow conditions cannot assign business fields");
    }

    public boolean matches(String expression, String moduleAlias, String recordId) {
        if (expression == null || expression.isBlank()) return true;
        validate(expression);
        return requireBoolean(formulas.evaluateValue(expression, FormulaRuntimeData.of(facts.read(moduleAlias, recordId))));
    }

    public boolean matches(String expression, Map<String, Object> values) {
        if (expression == null || expression.isBlank()) return true;
        validate(expression);
        return requireBoolean(formulas.evaluateValue(expression, FormulaRuntimeData.of(values)));
    }

    static boolean requireBoolean(Object value) {
        if (value instanceof Boolean result) return result;
        throw new PlatformException("流程条件公式必须返回布尔值，请使用比较公式，例如 {amount} > 100");
    }

    /** Advisory manual conditions share one current fact snapshot. Null denotes an invalid formula, not a mismatch.
     * Authorization belongs to the caller; fact-access failures are never converted into suggestions. */
    public Map<String, Boolean> manualMatches(Map<String, String> expressions, String moduleAlias, String recordId) {
        return manualMatches(expressions, businessFacts(expressions.values(), moduleAlias, recordId));
    }

    public Map<String, Object> businessFacts(Collection<String> expressions, String moduleAlias, String recordId) {
        return expressions.stream().anyMatch(value -> value != null && !value.isBlank())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(facts.read(moduleAlias, recordId))) : Map.of();
    }

    public Map<String, Boolean> manualMatches(Map<String, String> expressions, Map<String, Object> values) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        expressions.forEach((key, expression) -> {
            try {
                results.put(key, matches(expression, values));
            } catch (FormulaEvaluationException | PlatformException invalidCondition) {
                results.put(key, null);
            }
        });
        return Collections.unmodifiableMap(results);
    }
}
