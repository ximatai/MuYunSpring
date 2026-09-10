package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaExecutionResult;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.FormulaReferenceContext;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamicFormulaRuntime {
    private final FormulaEngine engine = new FormulaEngine();
    private final String moduleAlias;
    private final EntityDefinition entity;
    private final ModuleDefinition module;
    private final ReferenceTargetResolver referenceResolver;

    DynamicFormulaRuntime(String moduleAlias, EntityDefinition entity, ModuleDefinition module) {
        this(moduleAlias, entity, module, PlatformAbilityRuntime.referenceTargetResolver());
    }

    DynamicFormulaRuntime(String moduleAlias, EntityDefinition entity, ModuleDefinition module,
                          ReferenceTargetResolver referenceResolver) {
        this.moduleAlias = moduleAlias;
        this.entity = entity;
        this.module = module;
        this.referenceResolver = referenceResolver == null ? PlatformAbilityRuntime.referenceTargetResolver() : referenceResolver;
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
        List<FormulaRule> rules = orderedRuntimeRules(runtimeRules(phases, includeChildDependentRules));
        if (rules.isEmpty()) {
            return new FormulaExecutionResult();
        }
        Map<String, Object> main = DynamicFormulaDataSupport.mainValues(record, existing);
        Map<String, List<Map<String, Object>>> tables = DynamicFormulaDataSupport.childValues(record);
        FormulaReferenceContext references = referenceContext(rules);
        List<net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition> fields = new ArrayList<>(
                DynamicFormulaDataSupport.fieldDefinitions(entity, module));
        fields.addAll(references.fields());
        FormulaExecutionResult result = engine.execute(rules, FormulaRuntimeData.typed(main, tables, fields,
                references.paths(), references::resolve));
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
        List<FormulaRule> rules = orderedRuntimeRules(runtimeRulesForUpdate(phases));
        if (rules.isEmpty()) {
            return new FormulaExecutionResult();
        }
        FormulaExecutionResult unsupportedChildWrites = rejectUnpersistableChildWrites(record, existingChildren, rules);
        if (unsupportedChildWrites.report().hasErrors()) {
            if (failOnErrors) {
                throw new DynamicFormulaException(moduleAlias, entity.alias(), unsupportedChildWrites.report());
            }
            return unsupportedChildWrites;
        }
        Map<String, Object> main = DynamicFormulaDataSupport.mainValues(record, existing);
        Map<String, List<Map<String, Object>>> tables = DynamicFormulaDataSupport.childValues(record, existingChildren);
        FormulaReferenceContext references = referenceContext(rules);
        List<net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition> fields = new ArrayList<>(
                DynamicFormulaDataSupport.fieldDefinitions(entity, module));
        fields.addAll(references.fields());
        FormulaExecutionResult result = engine.execute(rules, FormulaRuntimeData.typed(main, tables, fields,
                references.paths(), references::resolve));
        if (failOnErrors && result.report().hasErrors()) {
            throw new DynamicFormulaException(moduleAlias, entity.alias(), result.report());
        }
        if (applyChanges && !result.report().hasErrors()) {
            applyChangedFields(record, main, tables, result.changedFields());
        }
        return result;
    }

    /**
     * Persisted child rows are present in the evaluation baseline so parent aggregates remain authoritative.
     * They are not, however, part of the mutation payload. A formula must therefore not stage writes to a
     * relation unless that relation was submitted as a complete replacement; otherwise the parent result could
     * be calculated from child values that are never persisted.
     */
    private FormulaExecutionResult rejectUnpersistableChildWrites(
            DynamicRecord record,
            Map<String, List<DynamicRecord>> existingChildren,
            List<FormulaRule> rules
    ) {
        FormulaExecutionResult result = new FormulaExecutionResult();
        for (FormulaRule rule : rules) {
            Set<String> writes = new HashSet<>(engine.assignedFields(rule.expression()));
            if (rule.kind() == net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION
                    && rule.targetField() != null) {
                writes.add(rule.targetField());
            }
            for (String write : writes) {
                int dot = write.indexOf('.');
                if (dot < 0) {
                    continue;
                }
                String relationCode = write.substring(0, dot);
                if (!hasUnsubmittedPersistedRows(record, existingChildren, relationCode)) {
                    continue;
                }
                result.report().error(rule, "FORMULA_PARTIAL_CHILD_WRITE_UNSUPPORTED", write, null,
                        rule.expression(), "formula child field write requires a complete relation payload: " + write);
            }
        }
        return result;
    }

    private boolean hasUnsubmittedPersistedRows(DynamicRecord record,
                                                 Map<String, List<DynamicRecord>> existingChildren,
                                                 String relationCode) {
        List<DynamicRecord> persisted = existingChildren == null
                ? List.of()
                : existingChildren.getOrDefault(relationCode, List.of());
        if (persisted.isEmpty()) {
            return false;
        }
        List<DynamicRecord> submitted = record == null ? null : record.getChildren(relationCode);
        boolean completeRelation = record != null
                && record.getChildren().containsKey(relationCode)
                && submitted != null
                && !record.isPartialChildren(relationCode);
        if (completeRelation) {
            return false;
        }
        Set<String> submittedIds = submitted == null ? Set.of() : submitted.stream()
                .map(DynamicRecord::getId)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return persisted.stream().map(DynamicRecord::getId)
                .anyMatch(id -> id == null || id.isBlank() || !submittedIds.contains(id));
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

    /**
     * Keep default-value initialization and child-table rules on their established execution path.
     * Only BEFORE_SAVE main-record calculations and validations enter the shared dependency plan.
     */
    private List<FormulaRule> orderedRuntimeRules(List<FormulaRule> rules) {
        if (rules.isEmpty() || rules.stream().noneMatch(rule -> rule.phase() == FormulaRulePhase.BEFORE_SAVE)) {
            return rules;
        }
        List<FormulaRule> preBeforeSave = rules.stream()
                .filter(rule -> rule.phase() != FormulaRulePhase.BEFORE_SAVE)
                .toList();
        List<FormulaRule> beforeSave = rules.stream()
                .filter(rule -> rule.phase() == FormulaRulePhase.BEFORE_SAVE)
                .toList();
        List<FormulaRule> planCandidates = beforeSave.stream()
                .filter(this::isMainRecordPlanCandidate)
                .toList();
        if (planCandidates.isEmpty()) {
            return rules;
        }
        FormulaReferenceContext references = referenceContext(planCandidates);
        List<net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition> fields = new ArrayList<>(
                DynamicFormulaDataSupport.fieldDefinitions(entity, module));
        fields.addAll(references.fields());
        FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(planCandidates, fields);
        Set<FormulaRule> planned = new HashSet<>(planCandidates);
        rejectChildCalculationDependingOnMainPlan(beforeSave, planned, plan);
        List<FormulaRule> ordered = new ArrayList<>(preBeforeSave);
        // Existing child writes settle before main-record aggregate calculations consume them.
        beforeSave.stream()
                .filter(rule -> rule.kind() == net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION)
                .filter(rule -> !planned.contains(rule))
                .forEach(ordered::add);
        ordered.addAll(plan.orderedRules());
        beforeSave.stream()
                .filter(rule -> rule.kind() != net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION)
                .filter(rule -> !planned.contains(rule))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private void rejectChildCalculationDependingOnMainPlan(List<FormulaRule> beforeSave,
                                                            Set<FormulaRule> planned,
                                                            FormulaRuleExecutionPlan plan) {
        Set<String> mainCalculationTargets = Set.copyOf(plan.calculationTargetFieldsByRule().values());
        for (FormulaRule rule : beforeSave) {
            if (rule.kind() != net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION
                    || planned.contains(rule)) {
                continue;
            }
            String dependency = engine.valueSideReferencedFields(rule.expression()).stream()
                    .filter(mainCalculationTargets::contains)
                    .findFirst()
                    .orElse(null);
            if (dependency != null) {
                throw new net.ximatai.muyun.spring.common.formula.FormulaEvaluationException(
                        "FORMULA_PLAN_CHILD_DEPENDS_ON_MAIN_CALCULATION", dependency,
                        "child calculation depends on planned main-record calculation field " + dependency
                                + ": " + rule.id());
            }
        }
    }

    private boolean isMainRecordPlanCandidate(FormulaRule rule) {
        if (rule.kind() == net.ximatai.muyun.spring.common.formula.FormulaRuleKind.VALIDATION) {
            return rule.targetField() == null || !rule.targetField().contains(".");
        }
        if (rule.kind() != net.ximatai.muyun.spring.common.formula.FormulaRuleKind.CALCULATION) {
            return false;
        }
        if (rule.targetField() != null) {
            return !rule.targetField().contains(".");
        }
        return engine.assignedFields(rule.expression()).stream().noneMatch(field -> field.contains("."));
    }

    private FormulaReferenceContext referenceContext(List<FormulaRule> rules) {
        Set<String> childRelations = module == null ? Set.of() : module.relations().stream()
                .filter(relation -> entity.alias().equals(relation.parentEntityAlias()))
                .map(EntityRelationDefinition::code).collect(java.util.stream.Collectors.toSet());
        try {
            return FormulaReferenceContext.compile(ReferenceTarget.of(moduleAlias, entity.alias()), rules,
                    referenceResolver, childRelations);
        } catch (IllegalArgumentException exception) {
            throw new DynamicFormulaException(moduleAlias, entity.alias(), referenceFailure(exception));
        }
    }

    private net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport referenceFailure(IllegalArgumentException exception) {
        net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport report =
                new net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport();
        report.error(new FormulaRule("reference", "", net.ximatai.muyun.spring.common.formula.FormulaRuleKind.VALIDATION,
                        FormulaRulePhase.BEFORE_SAVE, null), "FORMULA_REFERENCE_PATH_INVALID", null, null, null,
                exception.getMessage());
        return report;
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
