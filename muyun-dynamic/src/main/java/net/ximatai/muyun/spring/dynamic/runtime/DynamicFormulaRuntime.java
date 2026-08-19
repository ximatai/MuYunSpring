package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaExecutionResult;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicFormulaRuntime {
    private final FormulaEngine engine = new FormulaEngine();
    private final String moduleAlias;
    private final EntityDefinition entity;
    private final ModuleDefinition module;

    DynamicFormulaRuntime(String moduleAlias, EntityDefinition entity, ModuleDefinition module) {
        this.moduleAlias = moduleAlias;
        this.entity = entity;
        this.module = module;
    }

    FormulaRuntimeReport beforeInsert(DynamicRecord record) {
        return execute(record, null, List.of(FormulaRulePhase.DEFAULT_VALUE, FormulaRulePhase.BEFORE_SAVE), true)
                .report();
    }

    FormulaRuntimeReport beforeUpdate(DynamicRecord record,
                                      DynamicRecord existing,
                                      Map<String, List<DynamicRecord>> existingChildren) {
        return execute(record, existing, existingChildren, List.of(FormulaRulePhase.BEFORE_SAVE)).report();
    }

    boolean hasBeforeUpdateRules(DynamicRecord record) {
        return !runtimeRulesForUpdate(List.of(FormulaRulePhase.BEFORE_SAVE)).isEmpty();
    }

    boolean hasBeforeActionExecuteRules() {
        return hasRules(List.of(FormulaRulePhase.ACTION_BEFORE_EXECUTE));
    }

    FormulaRuntimeReport beforeActionExecute(DynamicRecord record, DynamicRecord existing) {
        return execute(record, existing, List.of(FormulaRulePhase.ACTION_BEFORE_EXECUTE), true).report();
    }

    DynamicFormulaPreviewResult preview(DynamicRecord record,
                                        DynamicRecord existing,
                                        Map<String, List<DynamicRecord>> existingChildren) {
        FormulaExecutionResult result = record.getId() == null || record.getId().isBlank()
                ? execute(record, null, List.of(FormulaRulePhase.DEFAULT_VALUE, FormulaRulePhase.BEFORE_SAVE), true, false)
                : execute(record, existing, existingChildren, List.of(FormulaRulePhase.BEFORE_SAVE), false, true);
        List<String> changedFields = result.report().hasErrors() ? List.of() : result.changedFields();
        return new DynamicFormulaPreviewResult(record, result.report(), changedFields);
    }

    DynamicFormulaPreviewResult preview(DynamicRecord record, DynamicRecord existing) {
        return preview(record, existing, Map.of());
    }

    boolean hasImportValidateRules() {
        return hasRules(List.of(FormulaRulePhase.IMPORT_VALIDATE));
    }

    FormulaRuntimeReport importValidate(DynamicRecord record, DynamicRecord existing) {
        return execute(record, existing, List.of(FormulaRulePhase.IMPORT_VALIDATE), false, true, false).report();
    }

    private boolean hasRules(List<FormulaRulePhase> phases) {
        return entity.orderedFormulaRules().stream()
                .anyMatch(rule -> rule.enabled()
                        && phases.contains(rule.phase()));
    }

    private FormulaExecutionResult execute(DynamicRecord record,
                                           DynamicRecord existing,
                                           List<FormulaRulePhase> phases,
                                           boolean includeChildDependentRules) {
        return execute(record, existing, phases, includeChildDependentRules, true);
    }

    private FormulaExecutionResult execute(DynamicRecord record,
                                           DynamicRecord existing,
                                           List<FormulaRulePhase> phases,
                                           boolean includeChildDependentRules,
                                           boolean failOnErrors) {
        return execute(record, existing, phases, includeChildDependentRules, failOnErrors, true);
    }

    private FormulaExecutionResult execute(DynamicRecord record,
                                           DynamicRecord existing,
                                           List<FormulaRulePhase> phases,
                                           boolean includeChildDependentRules,
                                           boolean failOnErrors,
                                           boolean applyChanges) {
        List<FormulaRule> rules = runtimeRules(phases, includeChildDependentRules);
        if (rules.isEmpty()) {
            return new FormulaExecutionResult();
        }
        Map<String, Object> main = DynamicFormulaDataSupport.mainValues(record, existing);
        Map<String, List<Map<String, Object>>> tables = DynamicFormulaDataSupport.childValues(record);
        FormulaExecutionResult result = engine.execute(rules, FormulaRuntimeData.typed(
                main, tables, DynamicFormulaDataSupport.fieldDefinitions(entity, module)));
        if (failOnErrors && result.report().hasErrors()) {
            throw new DynamicFormulaException(moduleAlias, entity.alias(), result.report());
        }
        if (applyChanges && !result.report().hasErrors()) {
            applyChangedFields(record, main, tables, result.changedFields());
        }
        return result;
    }

    private FormulaExecutionResult execute(DynamicRecord record,
                                           DynamicRecord existing,
                                           Map<String, List<DynamicRecord>> existingChildren,
                                           List<FormulaRulePhase> phases) {
        return execute(record, existing, existingChildren, phases, true, true);
    }

    private FormulaExecutionResult execute(DynamicRecord record,
                                           DynamicRecord existing,
                                           Map<String, List<DynamicRecord>> existingChildren,
                                           List<FormulaRulePhase> phases,
                                           boolean failOnErrors,
                                           boolean applyChanges) {
        List<FormulaRule> rules = runtimeRulesForUpdate(phases);
        if (rules.isEmpty()) {
            return new FormulaExecutionResult();
        }
        Map<String, Object> main = DynamicFormulaDataSupport.mainValues(record, existing);
        Map<String, List<Map<String, Object>>> tables = DynamicFormulaDataSupport.childValues(record, existingChildren);
        FormulaExecutionResult result = engine.execute(rules, FormulaRuntimeData.typed(
                main, tables, DynamicFormulaDataSupport.fieldDefinitions(entity, module)));
        if (failOnErrors && result.report().hasErrors()) {
            throw new DynamicFormulaException(moduleAlias, entity.alias(), result.report());
        }
        if (applyChanges && !result.report().hasErrors()) {
            applyChangedFields(record, main, tables, result.changedFields());
        }
        return result;
    }

    private List<FormulaRule> runtimeRules(List<FormulaRulePhase> phases, boolean includeChildDependentRules) {
        return entity.orderedFormulaRules().stream()
                .filter(EntityFormulaRuleDefinition::enabled)
                .filter(rule -> phases.contains(rule.phase()))
                .filter(rule -> includeChildDependentRules || !dependsOnChildRows(rule))
                .map(EntityFormulaRuleDefinition::toRuntimeRule)
                .toList();
    }

    private List<FormulaRule> runtimeRulesForUpdate(List<FormulaRulePhase> phases) {
        return entity.orderedFormulaRules().stream()
                .filter(EntityFormulaRuleDefinition::enabled)
                .filter(rule -> phases.contains(rule.phase()))
                .map(EntityFormulaRuleDefinition::toRuntimeRule)
                .toList();
    }

    private boolean dependsOnChildRows(EntityFormulaRuleDefinition rule) {
        return !relationDependencies(rule).isEmpty();
    }

    private Set<String> relationDependencies(EntityFormulaRuleDefinition rule) {
        Set<String> dependencies = new HashSet<>();
        if (module == null) {
            return dependencies;
        }
        for (EntityRelationDefinition relation : module.relations()) {
            if (!entity.alias().equals(relation.parentEntityAlias())) {
                continue;
            }
            String prefix = relation.code() + ".";
            if (rule.targetField() != null && rule.targetField().startsWith(prefix)) {
                dependencies.add(relation.code());
            }
            if (rule.expression() != null && engine.referencedFields(rule.expression()).stream()
                    .anyMatch(field -> field.startsWith(prefix))) {
                dependencies.add(relation.code());
            }
        }
        return dependencies;
    }

    private void applyChangedFields(DynamicRecord record,
                                    Map<String, Object> main,
                                    Map<String, List<Map<String, Object>>> tables,
                                    List<String> changedFields) {
        for (String dataIndex : changedFields) {
            int dot = dataIndex.indexOf('.');
            if (dot < 0) {
                record.setValue(dataIndex, main.get(dataIndex));
                continue;
            }
            String relationCode = dataIndex.substring(0, dot);
            String fieldName = dataIndex.substring(dot + 1);
            List<DynamicRecord> children = record.getChildren(relationCode);
            List<Map<String, Object>> rows = tables.get(relationCode);
            if (children == null || rows == null) {
                continue;
            }
            for (int i = 0; i < children.size() && i < rows.size(); i++) {
                children.get(i).setValue(fieldName, rows.get(i).get(fieldName));
            }
        }
    }

}
