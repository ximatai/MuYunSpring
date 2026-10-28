package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.schema.PlatformFieldPolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.FormulaReferenceContext;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadata;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.PlatformDataScopeSchema;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.web.PlatformWebPathRules;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;

import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ModuleDefinitionValidator {
    private static final String IDENTIFIER_PATTERN = "[a-z][a-z0-9_]{0,62}";
    private static final Set<String> STANDARD_COLUMNS = Set.copyOf(StandardEntitySchema.columnNames());
    private static final Set<String> STANDARD_FIELDS = Set.copyOf(StandardEntitySchema.fieldNames());
    private static final Set<String> DISCRIMINATED_VALUE_SOURCE_STANDARD_FIELDS = Set.of(StandardEntitySchema.TENANT_ID_FIELD);
    private static final Set<String> DATA_SCOPE_COLUMNS = Set.copyOf(PlatformDataScopeSchema.columnNames());
    private static final Set<String> DATA_SCOPE_FIELDS = Set.copyOf(PlatformDataScopeSchema.fieldNames());
    private final FormulaEngine formulaEngine = new FormulaEngine();

    public void validate(ModuleDefinition module) {
        if (module == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.module-must-not-be-null",
                    "module must not be null"));
        }
        requireModuleAlias(module.moduleAlias(), "module alias");
        requireText(module.name(), "module name");
        Set<String> entityAliases = new HashSet<>();
        Set<String> tableNames = new HashSet<>();
        for (EntityDefinition entity : module.entities()) {
            validateEntity(entity);
            requireUnique(entityAliases, entity.alias(), "entity alias");
            requireUnique(tableNames, physicalTableKey(entity), "table name");
        }
        Map<String, EntityDefinition> entities = module.entities().stream()
                .collect(Collectors.toMap(EntityDefinition::alias, Function.identity()));
        if (module.mainEntityAlias() != null) {
            requireEntity(entities, module.mainEntityAlias(), "module main entity");
        }
        Set<String> relationCodes = new HashSet<>();
        for (EntityRelationDefinition relation : module.relations()) {
            validateRelation(relation, entities);
            requireUnique(relationCodes, relation.parentEntityAlias() + "." + relation.code(), "relation code");
        }
        var contentOwners = net.ximatai.muyun.spring.dynamic.capability.BusinessContentCapabilityModule.aggregateOwners(module.entities(), module.relations());
        for (var entity : module.entities()) if (entity.supports(EntityCapability.BUSINESS_CONTENT) && !contentOwners.contains(entity.alias()))
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.business-content-requires-declared-business-text-in-the-entity-or-its-owned-children",
                    "BUSINESS_CONTENT requires declared business text in the entity or its owned children: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        validateFormulaRuleTargets(module, entities);
        validateBeforeSaveFormulaPlans(module, entities);
        validateAggregateFormulaOwnership(module, entities);
        for (EntityReferenceDefinition reference : module.references()) {
            validateReference(reference, entities, module.moduleAlias(), module.references());
            FieldDefinition sourceField = requireField(entities.get(reference.sourceEntityAlias()), reference.sourceField(), "reference source");
            if (sourceField.content().enabled())
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-identity-cannot-declare-business-content",
                    "Reference identity cannot declare business content: {sourceField}", Map.of("sourceField", String.valueOf(reference.sourceField()))));
        }
        for (EntityDiscriminatedValueDefinition value : module.discriminatedValues()) {
            validateDiscriminatedValue(value, entities);
        }
        for (EntityReferenceLoadDefinition load : module.referenceLoads()) {
            validateReferenceLoad(load, entities, module.moduleAlias(), module.references());
        }
        for (EntityReferencedByDefinition referencedBy : module.referencedBys()) {
            validateReferencedBy(referencedBy, entities, module.moduleAlias(), module.references());
        }
        Set<String> associationViewKeys = new HashSet<>();
        for (EntityAssociationViewDefinition view : module.associationViews()) {
            validateAssociationView(view, entities, module.moduleAlias(), module.relations(), module.references());
            requireUnique(associationViewKeys, view.sourceEntityAlias() + "." + view.code(), "association view");
        }
        Set<String> actionKeys = new HashSet<>();
        for (EntityActionDefinition action : module.actions()) {
            validateAction(action, entities, module.relations());
            requireUnique(actionKeys, action.entityAlias() + "." + action.actionCode(), "action");
        }
        validateActionAuthInherits(module.actions(), entities);
        Set<String> viewKeys = new HashSet<>();
        for (EntityViewDefinition view : module.views()) {
            validateView(view, entities);
            requireUnique(viewKeys, view.entityAlias() + "." + view.viewType(), "view");
        }
    }

    public void validateEntity(EntityDefinition entity) {
        if (entity == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.entity-must-not-be-null",
                    "entity must not be null"));
        }
        requireIdentifier(entity.alias(), "entity alias");
        if (entity.schemaName() != null && !entity.schemaName().isBlank()) {
            requireIdentifier(entity.schemaName(), "schema name");
        }
        requireIdentifier(entity.tableName(), "table name");
        requireText(entity.name(), "entity name");
        Set<String> fieldCodes = new HashSet<>();
        Set<String> columnNames = new HashSet<>();
        Set<String> contentKeys = new HashSet<>();
        int sortableFields = 0;
        int titleFields = 0;
        FieldDefinition sortableField = null;
        FieldDefinition titleField = null;
        FieldDefinition enabledField = null;
        List<FieldDefinition> fields = entity.fields();
        for (FieldDefinition field : entity.fields()) {
            validateField(field);
            if (field.content().enabled()) {
                requireUnique(contentKeys, field.content().key(), "business content field identity");
                if (entity.fileReferences().containsKey(field.fieldName()))
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-identity-cannot-declare-business-content",
                    "File identity cannot declare business content: {fieldName}", Map.of("fieldName", String.valueOf(field.fieldName()))));
            }
            requireUnique(fieldCodes, field.code(), "field code");
            requireUnique(columnNames, field.columnName(), "column name");
            if (PlatformAbilityFields.ENABLED_FIELD.equals(field.fieldName())
                    || PlatformAbilityFields.ENABLED_COLUMN.equals(field.columnName())) {
                enabledField = field;
            }
            if (field.isSortable()) {
                sortableFields++;
                sortableField = field;
            }
            if (field.isTitle()) {
                titleFields++;
                titleField = field;
            }
        }
        for (FieldDefinition field : fields) {
            validateMeasureUnit(entity, field, fields);
            validateMoney(entity, field, fields);
            validateOptionLoad(entity, field);
        }
        validateTenantUniqueConstraints(entity, fields);
        entity.conditionalIndexes().forEach(index -> net.ximatai.muyun.spring.common.schema.PlatformConditionalIndexes
                .validate(index, field -> conditionalIndexFieldType(entity, field)));
        validateFileReferences(entity, fields);
        if (!entity.supports(EntityCapability.CRUD)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.dynamic-entity-requires-crud-capability",
                    "dynamic entity requires CRUD capability: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (sortableFields > 1) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.entity-can-only-have-one-sortable-field",
                    "entity can only have one sortable field: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (titleFields > 1) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.entity-can-only-have-one-title-field",
                    "entity can only have one title field: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (titleFields > 0 && !entity.supports(EntityCapability.REFERENCE)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.title-field-requires-reference-capability",
                    "title field requires REFERENCE capability: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (enabledField != null && !entity.supports(EntityCapability.ENABLE)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.enabled-field-requires-enable-capability",
                    "enabled field requires ENABLE capability: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (entity.supports(EntityCapability.REFERENCE)) {
            requireTitleField(entity, titleField);
        }
        CapabilityModuleRegistry.defaultRegistry().validate(entity);
        FieldCompanionRules.validateEntity(entity);
        validateFormulaRules(entity);
    }

    private void validateFileReferences(EntityDefinition entity, List<FieldDefinition> fields) {
        Set<String> metadataTargets = new HashSet<>();
        Set<String> fileReferenceFields = Set.copyOf(entity.fileReferences().keySet());
        for (Map.Entry<String, FileReferenceDefinition> entry : entity.fileReferences().entrySet()) {
            String fieldName = entry.getKey();
            requireFieldName(fieldName, "file reference field");
            if (entry.getValue() == null) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-definition-must-not-be-null",
                    "file reference definition must not be null: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
            }
            FieldDefinition field = fields.stream()
                    .filter(candidate -> fieldName.equals(candidate.fieldName()))
                    .findFirst()
                    .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-requires-declared-field",
                    "file reference requires declared field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName)))));
            boolean valid = entry.getValue().maxFiles() == 1
                    ? field.isPhysical() && field.type() == FieldType.STRING
                    : field.isPhysical() && field.type() == FieldType.JSON
                    && field.valueShape() == FieldValueShape.JSON_SET;
            if (!valid) {
                String requiredType = entry.getValue().maxFiles() == 1 ? "STRING" : "JSON_SET";
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-requires",
                    "file reference requires physical {requiredType} field: {alias}.{fieldName}", Map.of("requiredType", requiredType, "alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
            }
            if (entry.getValue().maxFiles() != 1 && !entry.getValue().metadataFields().isEmpty()) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-metadata-fields-require-a-single-file-reference",
                    "file reference metadata fields require a single-file reference: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
            }
            entry.getValue().metadataFields().forEach((metadata, targetFieldName) -> {
                if (fileReferenceFields.contains(targetFieldName)) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-metadata-field-must-not-be-a-fileid-field",
                    "file reference metadata field must not be a fileId field: {alias}.{targetFieldName}", Map.of("alias", String.valueOf(entity.alias()), "targetFieldName", String.valueOf(targetFieldName))));
                }
                if (!metadataTargets.add(targetFieldName)) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-metadata-field-is-bound-more-than-once",
                    "file reference metadata field is bound more than once: {alias}.{targetFieldName}", Map.of("alias", String.valueOf(entity.alias()), "targetFieldName", String.valueOf(targetFieldName))));
                }
                FieldDefinition target = fields.stream()
                        .filter(candidate -> targetFieldName.equals(candidate.fieldName()))
                        .findFirst()
                        .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-metadata-requires-declared-field",
                    "file reference metadata requires declared field: {alias}.{targetFieldName}", Map.of("alias", String.valueOf(entity.alias()), "targetFieldName", String.valueOf(targetFieldName)))));
                FieldType expectedType = metadata == FileReferenceMetadata.SIZE_BYTES
                        ? FieldType.LONG : FieldType.STRING;
                if (!target.isPhysical() || target.type() != expectedType) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.file-reference-metadata-requires-physical-field",
                    "file reference metadata requires physical {expectedType} field: {alias}.{targetFieldName}", Map.of("expectedType", String.valueOf(expectedType), "alias", String.valueOf(entity.alias()), "targetFieldName", String.valueOf(targetFieldName))));
                }
            });
        }
    }

    private void validateTenantUniqueConstraints(EntityDefinition entity, List<FieldDefinition> fields) {
        Set<net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition.Identity> declared = new HashSet<>();
        for (net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition constraint
                : entity.resolvedTenantUniqueConstraints()) {
            if (!declared.add(constraint.identity())) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.duplicate-tenant-unique-constraint",
                    "duplicate tenant unique constraint: {alias}.{fieldNames}", Map.of("alias", String.valueOf(entity.alias()), "fieldNames", String.valueOf(String.join(",", constraint.fieldNames())))));
            }
            for (String fieldName : constraint.fieldNames()) {
                boolean physical = DynamicAbilityFields.recordFields(entity).stream().anyMatch(field -> field.fieldName().equals(fieldName)
                        && field.isPhysical()) || !constraint.conditions().isEmpty() && StandardEntitySchema.fieldNames().contains(fieldName);
                if (!physical) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.tenant-unique-constraint-requires-physical-field",
                    "tenant unique constraint requires physical field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
                }
            }
            if (!constraint.conditions().isEmpty()) {
                net.ximatai.muyun.spring.common.schema.PlatformConditionalIndexes.validate(
                        new net.ximatai.muyun.spring.common.model.constraint.ConditionalIndexDefinition(constraint.indexName(),
                                constraint.fieldNames(), true, constraint.conditions()), field -> conditionalIndexFieldType(entity, field));
            }
        }
    }

    private net.ximatai.muyun.database.core.builder.ColumnType conditionalIndexFieldType(EntityDefinition entity, String name) {
        if (StandardEntitySchema.ID_FIELD.equals(name)) return StandardEntitySchema.idColumn().getType();
        if (StandardEntitySchema.fieldNames().contains(name)) {
            return StandardEntitySchema.auditColumns().stream().filter(column -> column.getName().equals(StandardEntitySchema.columnName(name)))
                    .findFirst().orElseThrow().getType();
        }
        FieldDefinition field = DynamicAbilityFields.recordFields(entity).stream()
                .filter(item -> item.fieldName().equals(name) && item.isPhysical()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("conditional index requires a physical field: " + name));
        if (field.protection().hasStorageProtection()) throw new IllegalArgumentException("conditional index cannot use a protected storage field: " + name);
        return DynamicFieldColumnMetadata.columnType(field);
    }

    public void validateFormulaRules(EntityDefinition entity) {
        Set<String> ruleCodes = new HashSet<>();
        for (EntityFormulaRuleDefinition rule : entity.formulaRules()) {
            validateFormulaRule(entity, rule);
            requireUnique(ruleCodes, rule.code(), "formula rule code");
        }
    }

    public void validateFormulaRule(EntityDefinition entity, EntityFormulaRuleDefinition rule) {
        if (rule == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.formula-rule-must-not-be-null",
                    "formula rule must not be null: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        requireActionCode(rule.code(), "formula rule code");
        requireText(rule.expression(), "formula expression");
        requireFormulaExpression(rule);
        if (rule.kind() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.formula-rule-kind-must-not-be-null",
                    "formula rule kind must not be null: {code}", Map.of("code", String.valueOf(rule.code()))));
        }
        if (rule.phase() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.formula-rule-phase-must-not-be-null",
                    "formula rule phase must not be null: {code}", Map.of("code", String.valueOf(rule.code()))));
        }
        if (rule.phase() == FormulaRulePhase.IMPORT_VALIDATE && rule.kind() == FormulaRuleKind.CALCULATION) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.import-validation-formula-must-not-calculate-fields",
                    "import validation formula must not calculate fields: {code}", Map.of("code", String.valueOf(rule.code()))));
        }
        if (rule.severity() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.formula-rule-severity-must-not-be-null",
                    "formula rule severity must not be null: {code}", Map.of("code", String.valueOf(rule.code()))));
        }
        if (rule.targetField() != null && !rule.targetField().contains(".")) {
            requireFieldName(rule.targetField(), "formula target field");
            requireField(entity, rule.targetField(), "formula target field");
        }
        if (rule.targetField() != null && rule.targetField().contains(".")) {
            String[] parts = rule.targetField().split("\\.");
            if (parts.length != 2) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-formula-target-field",
                    "invalid formula target field: {targetField}", Map.of("targetField", String.valueOf(rule.targetField()))));
            }
            requireIdentifier(parts[0], "formula target relation");
            requireFieldName(parts[1], "formula target field");
        }
    }

    private void validateFormulaRuleTargets(ModuleDefinition module, Map<String, EntityDefinition> entities) {
        for (EntityDefinition entity : module.entities()) {
            for (EntityFormulaRuleDefinition rule : entity.formulaRules()) {
                if (rule == null || rule.targetField() == null || !rule.targetField().contains(".")) {
                    continue;
                }
                String[] parts = rule.targetField().split("\\.");
                if (parts.length != 2) {
                    continue;
                }
                EntityRelationDefinition relation = module.relations().stream()
                        .filter(candidate -> entity.alias().equals(candidate.parentEntityAlias())
                                && parts[0].equals(candidate.code()))
                        .findFirst()
                        .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-formula-target-relation",
                    "unknown formula target relation: {alias}.{relationCode}", Map.of("alias", String.valueOf(entity.alias()), "relationCode", String.valueOf(parts[0])))));
                EntityDefinition childEntity = requireEntity(entities, relation.childEntityAlias(), "formula target child entity");
                requireField(childEntity, parts[1], "formula target field");
                try {
                    formulaEngine.validateTargetFieldExpressionScope(rule.targetField(), rule.expression());
                } catch (FormulaEvaluationException e) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-formula-expression",
                    "invalid formula expression: {code}, {diagnostic}", Map.of("code", String.valueOf(rule.code()), "diagnostic", String.valueOf(e.getMessage()))));
                }
            }
        }
    }

    private void validateBeforeSaveFormulaPlans(ModuleDefinition module,
                                                    Map<String, EntityDefinition> entities) {
        for (EntityDefinition entity : module.entities()) {
            List<FormulaRule> candidates = entity.orderedFormulaRules().stream()
                    .filter(EntityFormulaRuleDefinition::enabled)
                    .filter(rule -> rule.phase() == FormulaRulePhase.BEFORE_SAVE)
                    .map(EntityFormulaRuleDefinition::toRuntimeRule)
                    .filter(FormulaRuleExecutionPlan::supportsDependencyPlanning)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            try {
                List<FormulaFieldDefinition> fields = new java.util.ArrayList<>(declaredFormulaFields(module, entity, entities));
                Set<String> childRelations = module.relations().stream()
                        .filter(relation -> entity.alias().equals(relation.parentEntityAlias()))
                        .map(EntityRelationDefinition::code).collect(Collectors.toSet());
                FormulaReferenceContext references = FormulaReferenceContext.compile(
                        ReferenceTarget.of(module.moduleAlias(), entity.alias()), candidates,
                        PlatformAbilityRuntime.referenceTargetResolver(), childRelations);
                fields.addAll(references.fields());
                var plan = FormulaRuleExecutionPlan.forAggregateRecord(candidates, fields, childRelations);
                plan.validatePrecedingCalculations(entity.orderedFormulaRules().stream()
                        .map(EntityFormulaRuleDefinition::toRuntimeRule).toList());
            } catch (FormulaEvaluationException | IllegalArgumentException exception) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-save-formula-rule-plan",
                    "invalid save formula rule plan: {alias}, {diagnostic}", Map.of("alias", String.valueOf(entity.alias()), "diagnostic", String.valueOf(exception.getMessage()))));
            }
        }
    }


    /** Parent formulas run before child lifecycle formulas; do not publish contradictory results. */
    private void validateAggregateFormulaOwnership(ModuleDefinition module, Map<String, EntityDefinition> entities) {
        for (EntityRelationDefinition relation : module.relations()) {
            EntityDefinition parent = entities.get(relation.parentEntityAlias());
            EntityDefinition child = entities.get(relation.childEntityAlias());
            Set<String> childWrites = new HashSet<>();
            for (EntityFormulaRuleDefinition rule : child.orderedFormulaRules()) {
                if (!rule.enabled() || rule.kind() != FormulaRuleKind.CALCULATION
                        || rule.phase() != FormulaRulePhase.BEFORE_SAVE && rule.phase() != FormulaRulePhase.DEFAULT_VALUE) continue;
                formulaEngine.assignedFields(rule.expression()).stream().filter(field -> !field.contains("."))
                        .map(field -> relation.code() + "." + field).forEach(childWrites::add);
                if (rule.targetField() != null && !rule.targetField().contains("."))
                    childWrites.add(relation.code() + "." + rule.targetField());
            }
            if (childWrites.isEmpty()) continue;
            for (EntityFormulaRuleDefinition rule : parent.orderedFormulaRules()) {
                if (!rule.enabled() || rule.phase() != FormulaRulePhase.BEFORE_SAVE) continue;
                Set<String> accessed = new HashSet<>(formulaEngine.referencedFields(rule.expression()));
                if (rule.targetField() != null) accessed.add(rule.targetField());
                accessed.retainAll(childWrites);
                if (!accessed.isEmpty()) throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.parent-formula-depends-on-child-lifecycle-calculation-declare-dependent-row-calculations-on-the-parent-aggregate",
                    "parent formula depends on child lifecycle calculation; declare dependent row calculations on the parent aggregate: {accessed}", Map.of("accessed", String.valueOf(accessed))));
            }
        }
    }

    private List<FormulaFieldDefinition> declaredFormulaFields(ModuleDefinition module,
                                                               EntityDefinition entity,
                                                               Map<String, EntityDefinition> entities) {
        List<FormulaFieldDefinition> fields = new java.util.ArrayList<>(DynamicFormulaFieldDefinitions.mainFields(entity));
        for (EntityRelationDefinition relation : module.relations()) {
            if (!entity.alias().equals(relation.parentEntityAlias())) {
                continue;
            }
            EntityDefinition child = entities.get(relation.childEntityAlias());
            if (child != null) {
                DynamicFormulaFieldDefinitions.childFields(relation.code(), child).stream()
                        .forEach(fields::add);
            }
        }
        return List.copyOf(fields);
    }

    private void requireFormulaExpression(EntityFormulaRuleDefinition rule) {
        try {
            Object parsed = formulaEngine.parse(rule.code(), rule.expression());
            if (parsed == null) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.formula-expression-is-empty",
                    "invalid formula expression: {code}", Map.of("code", String.valueOf(rule.code()))));
            }
        } catch (FormulaEvaluationException e) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-formula-expression",
                    "invalid formula expression: {code}, {diagnostic}", Map.of("code", String.valueOf(rule.code()), "diagnostic", String.valueOf(e.getMessage()))));
        }
    }

    public void validateField(FieldDefinition field) {
        if (field == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-must-not-be-null",
                    "field must not be null"));
        }
        requireFieldName(field.fieldName(), "field name");
        if (STANDARD_FIELDS.contains(field.fieldName())
                || PlatformFieldPolicy.isAuditLabel(field.fieldName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-name-conflicts-with-standard-field",
                    "field name conflicts with standard field: {fieldName}", Map.of("fieldName", String.valueOf(field.fieldName()))));
        }
        if (DATA_SCOPE_FIELDS.contains(field.fieldName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-name-conflicts-with-data-scope-ability-field",
                    "field name conflicts with data scope ability field: {fieldName}", Map.of("fieldName", String.valueOf(field.fieldName()))));
        }
        requireIdentifier(field.columnName(), "column name");
        requireText(field.name(), "field title");
        if (field.type() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-type-must-not-be-null",
                    "field type must not be null: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        validateStorageForm(field);
        validateValueShape(field);
        validateCollectionQueryShape(field);
        if (field.defaultUiControlAlias() != null && !field.defaultUiControlAlias().isBlank()) {
            requireIdentifier(field.defaultUiControlAlias(), "field default UI type alias");
        }
        if (field.dictionaryBinding() != null) {
            if (field.dictionaryBinding().selectionMode() == OptionSelectionMode.MULTIPLE) {
                if (field.type() != FieldType.JSON) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.multiple-dictionary-binding-requires-json-field",
                    "multiple dictionary binding requires JSON field: {code}", Map.of("code", String.valueOf(field.code()))));
                }
            } else if (field.type() != FieldType.STRING && field.type() != FieldType.TEXT) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.dictionary-binding-requires-string-field",
                    "dictionary binding requires string field: {code}", Map.of("code", String.valueOf(field.code()))));
            }
        }
        if (STANDARD_COLUMNS.contains(field.columnName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-column-conflicts-with-standard-column",
                    "field column conflicts with standard column: {columnName}", Map.of("columnName", String.valueOf(field.columnName()))));
        }
        if (DATA_SCOPE_COLUMNS.contains(field.columnName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-column-conflicts-with-data-scope-ability-column",
                    "field column conflicts with data scope ability column: {columnName}", Map.of("columnName", String.valueOf(field.columnName()))));
        }
        if (field.length() != null && field.length() <= 0) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-length-must-be-positive",
                    "field length must be positive: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.length() != null && field.type() != FieldType.STRING && field.type() != FieldType.TEXT) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-length-only-applies-to-string-fields",
                    "field length only applies to string fields: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.precision() != null && field.precision() <= 0) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-precision-must-be-positive",
                    "field precision must be positive: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.scale() != null && field.scale() < 0) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-scale-must-not-be-negative",
                    "field scale must not be negative: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if ((field.precision() != null || field.scale() != null) && field.type() != FieldType.DECIMAL) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-precision-and-scale-only-apply-to-decimal-fields",
                    "field precision and scale only apply to decimal fields: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.scale() != null && field.precision() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-scale-requires-precision",
                    "field scale requires precision: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.scale() != null && field.scale() > field.precision()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.field-scale-must-not-exceed-precision",
                    "field scale must not exceed precision: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.isSortable() && field.type() != FieldType.INTEGER && field.type() != FieldType.LONG) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sortable-field-must-be-an-integer-type",
                    "sortable field must be an integer type: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.isTitle() && field.type() != FieldType.STRING && field.type() != FieldType.TEXT) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.title-field-must-be-a-text-type",
                    "title field must be a text type: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.protection().hasStorageProtection()) {
            if (field.isUnique() || field.isIndexed() || field.isSortable() || field.isTitle()) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.protected-storage-field-cannot-be-unique-indexed-sortable-or-title-field",
                    "protected storage field cannot be unique, indexed, sortable or title field: {code}", Map.of("code", String.valueOf(field.code()))));
            }
            if (field.queryDefinition().queryable()) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.protected-storage-field-cannot-be-queryable",
                    "protected storage field cannot be queryable: {code}", Map.of("code", String.valueOf(field.code()))));
            }
        }
        try {
            FieldBehaviorSupport.validateBehavior(field);
        } catch (RuntimeException e) {
            throw new ModuleDefinitionException(e.getMessage(), e);
        }
    }

    private void validateStorageForm(FieldDefinition field) {
        if (field.storageForm() == null) {
            return;
        }
        if (field.storageForm() != FieldStorageForm.PHYSICAL && field.storageForm() != FieldStorageForm.VIRTUAL) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unsupported-field-storage-form",
                    "unsupported field storage form: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.isPhysical()) {
            return;
        }
        if (field.isRequired() || field.isUnique() || field.isIndexed() || field.isSortable() || field.isTitle()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-be-required-unique-indexed-sortable-or-title-field",
                    "virtual field cannot be required, unique, indexed, sortable or title field: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.queryDefinition().queryable()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-be-queryable",
                    "virtual field cannot be queryable: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (!field.behavior().writeRules().equals(FieldWriteRules.NONE)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-define-write-rules",
                    "virtual field cannot define write rules: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.behavior().defaultValue() != null || field.behavior().validationRegex() != null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-define-default-value-or-validation-regex",
                    "virtual field cannot define default value or validation regex: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.protection().hasStorageProtection()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-use-storage-protection",
                    "virtual field cannot use storage protection: {code}", Map.of("code", String.valueOf(field.code()))));
        }
        if (field.measureUnit().enabled() || field.money().enabled()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.virtual-field-cannot-define-measure-unit-or-money-storage-behavior",
                    "virtual field cannot define measure unit or money storage behavior: {code}", Map.of("code", String.valueOf(field.code()))));
        }
    }

    private void validateValueShape(FieldDefinition field) {
        if (field.valueShape() == FieldValueShape.DEFAULT) {
            return;
        }
        if (field.valueShape() == FieldValueShape.JSON_SET && field.type() != FieldType.JSON) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.json-set-value-shape-requires-json-field",
                    "JSON_SET value shape requires JSON field: {code}", Map.of("code", String.valueOf(field.code()))));
        }
    }

    private void validateCollectionQueryShape(FieldDefinition field) {
        if (!usesCollectionQueryOperator(field)) {
            return;
        }
        if (field.valueShape() != FieldValueShape.JSON_SET) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.collection-query-operators-require-json-set-value-shape",
                    "collection query operators require JSON_SET value shape: {code}", Map.of("code", String.valueOf(field.code()))));
        }
    }

    private void validateOptionLoad(EntityDefinition entity, FieldDefinition output) {
        FieldOptionLoadDefinition load = output.optionLoad();
        if (load == null) {
            return;
        }
        if (output.isPhysical()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.option-load-requires-virtual-output-field",
                    "option load requires virtual output field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(output.fieldName()))));
        }
        FieldDefinition source = requireField(entity, load.sourceField(), "option load source field");
        if (source.dictionaryBinding() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.option-load-source-requires-dictionary-binding",
                    "option load source requires dictionary binding: {alias}.{sourceField}", Map.of("alias", String.valueOf(entity.alias()), "sourceField", String.valueOf(load.sourceField()))));
        }
        if (!Set.of("code", "title", "enabled", "sortOrder", "parentCode").contains(load.optionItemField())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-option-item-field",
                    "unknown option item field: {optionItemField}", Map.of("optionItemField", String.valueOf(load.optionItemField()))));
        }
        if (source.dictionaryBinding().selectionMode() == OptionSelectionMode.MULTIPLE) {
            if (output.type() != FieldType.JSON || output.valueShape() != FieldValueShape.JSON_SET) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.multiple-option-load-requires-json-set-output-field",
                    "multiple option load requires JSON_SET output field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(output.fieldName()))));
            }
            return;
        }
        FieldType expected = switch (load.optionItemField()) {
            case "enabled" -> FieldType.BOOLEAN;
            case "sortOrder" -> FieldType.INTEGER;
            default -> FieldType.STRING;
        };
        if (output.type() != expected && !(expected == FieldType.STRING && output.type() == FieldType.TEXT)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.option-load-output-type-does-not-match-option-item-field",
                    "option load output type does not match option item field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(output.fieldName()))));
        }
    }

    private boolean usesCollectionQueryOperator(FieldDefinition field) {
        Set<DynamicQueryOperator> operators = field.queryDefinition().operators();
        return operators.contains(DynamicQueryOperator.CONTAINS)
                || operators.contains(DynamicQueryOperator.CONTAINS_ANY)
                || operators.contains(DynamicQueryOperator.CONTAINS_ALL)
                || operators.contains(DynamicQueryOperator.EMPTY)
                || operators.contains(DynamicQueryOperator.NOT_EMPTY);
    }

    private void validateMeasureUnit(EntityDefinition entity, FieldDefinition field, List<FieldDefinition> fields) {
        FieldMeasureUnitDefinition measureUnit = field.measureUnit();
        if (measureUnit == null || !measureUnit.enabled()) {
            return;
        }
        if (field.type() != FieldType.DECIMAL && field.type() != FieldType.INTEGER && field.type() != FieldType.LONG) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-unit-field-requires-numeric-owner",
                    "measure unit field requires numeric owner: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        requireIdentifier(measureUnit.categoryAlias(), "measure unit category alias");
        if (measureUnit.mode() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-unit-mode-must-not-be-null",
                    "measure unit mode must not be null: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (measureUnit.mode() == FieldMeasureUnitMode.FIXED) {
            requireIdentifier(measureUnit.fixedUnitCode(), "measure fixed unit code");
        }
        if (measureUnit.mode() == FieldMeasureUnitMode.SELECTABLE) {
            FieldDefinition unitField = requireMeasureField(entity, fields, measureUnit.unitFieldName(),
                    "measure unit companion field");
            if (unitField.type() != FieldType.STRING && unitField.type() != FieldType.TEXT) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-unit-companion-field-must-be-text",
                    "measure unit companion field must be text: {alias}.{unitFieldName}", Map.of("alias", String.valueOf(entity.alias()), "unitFieldName", String.valueOf(measureUnit.unitFieldName()))));
            }
        } else if (measureUnit.unitFieldName() != null && !measureUnit.unitFieldName().isBlank()) {
            requireMeasureField(entity, fields, measureUnit.unitFieldName(), "measure unit companion field");
        }
        FieldDefinition baseValueField = requireMeasureField(entity, fields, measureUnit.baseValueFieldName(),
                "measure base value field");
        if (field.fieldName().equals(baseValueField.fieldName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-base-value-field-must-be-different-from-owner",
                    "measure base value field must be different from owner: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (baseValueField.type() != FieldType.DECIMAL && baseValueField.type() != FieldType.INTEGER
                && baseValueField.type() != FieldType.LONG) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-base-value-field-must-be-numeric",
                    "measure base value field must be numeric: {alias}.{baseValueFieldName}", Map.of("alias", String.valueOf(entity.alias()), "baseValueFieldName", String.valueOf(measureUnit.baseValueFieldName()))));
        }
        if (measureUnit.baseUnitCategoryAlias() != null && !measureUnit.baseUnitCategoryAlias().isBlank()) {
            requireIdentifier(measureUnit.baseUnitCategoryAlias(), "measure base unit category alias");
        }
        requireIdentifier(measureUnit.baseUnitCode(), "measure base unit code");
        if (measureUnit.defaultUnitCode() != null && !measureUnit.defaultUnitCode().isBlank()) {
            requireIdentifier(measureUnit.defaultUnitCode(), "measure default unit code");
        }
        if (measureUnit.conversionMode() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.measure-unit-conversion-mode-must-not-be-null",
                    "measure unit conversion mode must not be null: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (measureUnit.conversionScopeFieldName() != null && !measureUnit.conversionScopeFieldName().isBlank()) {
            requireMeasureField(entity, fields, measureUnit.conversionScopeFieldName(), "measure conversion scope field");
        }
    }

    private FieldDefinition requireMeasureField(EntityDefinition entity,
                                                List<FieldDefinition> fields,
                                                String fieldName,
                                                String name) {
        requireFieldName(fieldName, name);
        return fields.stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-field",
                    "unknown {name}: {alias}.{fieldName}", Map.of("name", String.valueOf(name), "alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName)))));
    }

    private void validateMoney(EntityDefinition entity, FieldDefinition field, List<FieldDefinition> fields) {
        FieldMoneyDefinition money = field.money();
        if (money == null || !money.enabled()) {
            return;
        }
        if (!isNumeric(field)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-field-requires-numeric-owner",
                    "money field requires numeric owner: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (money.currencyMode() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-currency-mode-must-not-be-null",
                    "money currency mode must not be null: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (money.currencyMode() == FieldMoneyMode.FIXED) {
            requireCurrencyCode(money.fixedCurrencyCode(), "money fixed currency code");
        }
        if (money.currencyMode() == FieldMoneyMode.SELECTABLE) {
            FieldDefinition currencyField = requireMoneyField(entity, fields, money.currencyFieldName(),
                    "money currency companion field");
            requireTextMoneyField(entity, currencyField, money.currencyFieldName(), "money currency companion field");
        } else if (money.currencyFieldName() != null && !money.currencyFieldName().isBlank()) {
            FieldDefinition currencyField = requireMoneyField(entity, fields, money.currencyFieldName(),
                    "money currency companion field");
            requireTextMoneyField(entity, currencyField, money.currencyFieldName(), "money currency companion field");
        }
        FieldDefinition baseAmountField = requireMoneyField(entity, fields, money.baseAmountFieldName(),
                "money base amount field");
        if (field.fieldName().equals(baseAmountField.fieldName())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-base-amount-field-must-be-different-from-owner",
                    "money base amount field must be different from owner: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(field.fieldName()))));
        }
        if (!isNumeric(baseAmountField)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-base-amount-field-must-be-numeric",
                    "money base amount field must be numeric: {alias}.{baseAmountFieldName}", Map.of("alias", String.valueOf(entity.alias()), "baseAmountFieldName", String.valueOf(money.baseAmountFieldName()))));
        }
        if (money.defaultCurrencyCode() != null && !money.defaultCurrencyCode().isBlank()) {
            requireCurrencyCode(money.defaultCurrencyCode(), "money default currency code");
        }
        if (money.baseCurrencyCode() != null && !money.baseCurrencyCode().isBlank()) {
            requireCurrencyCode(money.baseCurrencyCode(), "money base currency code");
        }
        requireRateTypeCode(money.rateTypeCode(), "money rate type code");
        if (money.rateDateFieldName() != null && !money.rateDateFieldName().isBlank()) {
            FieldDefinition rateDateField = requireMoneyField(entity, fields, money.rateDateFieldName(),
                    "money rate date field");
            if (rateDateField.type() != FieldType.DATE
                    && rateDateField.type() != FieldType.TIMESTAMP
                    && rateDateField.type() != FieldType.ZONED_TIMESTAMP) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-rate-date-field-must-be-date-or-timestamp",
                    "money rate date field must be date or timestamp: {alias}.{rateDateFieldName}", Map.of("alias", String.valueOf(entity.alias()), "rateDateFieldName", String.valueOf(money.rateDateFieldName()))));
            }
        }
        if (money.exchangeRateFieldName() != null && !money.exchangeRateFieldName().isBlank()) {
            FieldDefinition exchangeRateField = requireMoneyField(entity, fields, money.exchangeRateFieldName(),
                    "money exchange rate field");
            if (!isNumeric(exchangeRateField)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.money-exchange-rate-field-must-be-numeric",
                    "money exchange rate field must be numeric: {alias}.{exchangeRateFieldName}", Map.of("alias", String.valueOf(entity.alias()), "exchangeRateFieldName", String.valueOf(money.exchangeRateFieldName()))));
            }
        }
    }

    private FieldDefinition requireMoneyField(EntityDefinition entity,
                                              List<FieldDefinition> fields,
                                              String fieldName,
                                              String name) {
        requireFieldName(fieldName, name);
        return fields.stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-field",
                    "unknown {name}: {alias}.{fieldName}", Map.of("name", String.valueOf(name), "alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName)))));
    }

    private void requireTextMoneyField(EntityDefinition entity,
                                       FieldDefinition field,
                                       String fieldName,
                                       String name) {
        if (field.type() != FieldType.STRING && field.type() != FieldType.TEXT) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-be-text",
                    "{name} must be text: {alias}.{fieldName}", Map.of("name", String.valueOf(name), "alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
        }
    }

    private boolean isNumeric(FieldDefinition field) {
        return field.type() == FieldType.DECIMAL || field.type() == FieldType.INTEGER || field.type() == FieldType.LONG;
    }

    private void requireCurrencyCode(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-not-be-blank",
                    "{name} must not be blank", Map.of("name", String.valueOf(name))));
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z]{3}")) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-be-iso-4217-alpha-3-code",
                    "{name} must be ISO 4217 alpha-3 code: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private void requireRateTypeCode(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-not-be-blank",
                    "{name} must not be blank", Map.of("name", String.valueOf(name))));
        }
        String code = value.trim().toUpperCase();
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-use-upper-snake-code",
                    "{name} must use upper snake code: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    public void validateRelation(EntityRelationDefinition relation, Map<String, EntityDefinition> entities) {
        if (relation == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.relation-must-not-be-null",
                    "relation must not be null"));
        }
        requireIdentifier(relation.code(), "relation code");
        EntityDefinition parent = requireEntity(entities, relation.parentEntityAlias(), "relation parent entity");
        EntityDefinition child = requireEntity(entities, relation.childEntityAlias(), "relation child entity");
        requireFieldName(relation.childForeignKeyField(), "relation child foreign key field");
        if (parent.alias().equals(child.alias())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.child-relation-must-use-different-parent-and-child-entities",
                    "child relation must use different parent and child entities: {code}", Map.of("code", String.valueOf(relation.code()))));
        }
        FieldDefinition childForeignKeyField = requireField(child, relation.childForeignKeyField(), "relation child foreign key field");
        if (childForeignKeyField.type() != FieldType.STRING) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.relation-child-foreign-key-field-must-be-string",
                    "relation child foreign key field must be STRING: {alias}.{childForeignKeyField}", Map.of("alias", String.valueOf(child.alias()), "childForeignKeyField", String.valueOf(relation.childForeignKeyField()))));
        }
    }

    public void validateReference(EntityReferenceDefinition reference, Map<String, EntityDefinition> entities) {
        validateReference(reference, entities, null);
    }

    /** Validates a dynamic declaration before it becomes a writable runtime contract. */
    public void validateDiscriminatedValue(EntityDiscriminatedValueDefinition value,
                                           Map<String, EntityDefinition> entities) {
        if (value == null) throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.discriminated-value-must-not-be-null",
                    "discriminated value must not be null"));
        EntityDefinition entity = requireEntity(entities, value.sourceEntityAlias(), "discriminated value entity");
        requireField(entity, value.valueField(), "discriminated value field");
        requireValueSourceField(entity, value.discriminatorField(), "discriminator field");
        try {
            net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValuePlan plan = value.plan();
            for (net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan branch : plan.cases()) {
                if (branch.source() == net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueSource.FIELD) {
                    requireValueSourceField(entity, branch.sourceField(), "discriminator source field");
                }
                if (branch.reference() != null) {
                    if (branch.reference().cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
                        throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.discriminator-reference-must-be-one",
                    "discriminator reference must be ONE: {valueField}", Map.of("valueField", String.valueOf(value.valueField()))));
                    }
                    for (net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency dependency
                            : branch.reference().candidateDependencies()) {
                        requireValueSourceField(entity, dependency.sourceField(), "discriminator reference dependency");
                    }
                }
            }
        } catch (IllegalArgumentException error) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-discriminated-value",
                    "invalid discriminated value: {valueField}: {diagnostic}", Map.of("valueField", String.valueOf(value.valueField()), "diagnostic", String.valueOf(error.getMessage()))));
        }
    }

    public void validateView(EntityViewDefinition view, Map<String, EntityDefinition> entities) {
        if (view == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.view-must-not-be-null",
                    "view must not be null"));
        }
        EntityDefinition entity = requireEntity(entities, view.entityAlias(), "view entity");
        if (view.viewType() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.view-type-must-not-be-null",
                    "view type must not be null: {entityAlias}", Map.of("entityAlias", String.valueOf(view.entityAlias()))));
        }
        requireText(view.title(), "view title");
        Set<String> fieldNames = new HashSet<>();
        for (EntityViewFieldDefinition field : view.fields()) {
            if (field == null) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.view-field-must-not-be-null",
                    "view field must not be null: {entityAlias}", Map.of("entityAlias", String.valueOf(view.entityAlias()))));
            }
            requireFieldName(field.fieldName(), "view field name");
            requireField(entity, field.fieldName(), "view field");
            requireUnique(fieldNames, field.fieldName(), "view field");
            if (field.title() != null && field.title().isBlank()) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.view-field-title-must-not-be-blank",
                    "view field title must not be blank: {entityAlias}.{fieldName}", Map.of("entityAlias", String.valueOf(view.entityAlias()), "fieldName", String.valueOf(field.fieldName()))));
            }
            if (field.fieldUiControlAlias() != null && !field.fieldUiControlAlias().isBlank()) {
                requireIdentifier(field.fieldUiControlAlias(), "view field UI type alias");
            }
        }
    }

    public void validateAction(EntityActionDefinition action, Map<String, EntityDefinition> entities) {
        validateAction(action, entities, List.of());
    }

    public void validateAction(EntityActionDefinition action,
                               Map<String, EntityDefinition> entities,
                               List<EntityRelationDefinition> relations) {
        if (action == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-must-not-be-null",
                    "action must not be null"));
        }
        if (!action.enabled()) {
            return;
        }
        EntityDefinition entity = requireEntity(entities, action.entityAlias(), "action entity");
        requireActionCode(action.actionCode(), "action code");
        requireText(action.title(), "action title");
        if (action.level() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-level-must-not-be-null",
                    "action level must not be null: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.category() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-category-must-not-be-null",
                    "action category must not be null: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.accessMode() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-access-mode-must-not-be-null",
                    "action access mode must not be null: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.executorType() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-executor-type-must-not-be-null",
                    "action executor type must not be null: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.executorKey() != null && action.executorKey().isBlank()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-executor-key-must-not-be-blank",
                    "action executor key must not be blank: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.executorType() == EntityActionExecutorType.DIALOG && action.executorKey() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.dialog-action-requires-executor-key",
                    "dialog action requires executor key: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.category() != EntityActionCategory.STANDARD
                && PlatformWebPathRules.isReservedWebActionCode(action.actionCode())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.custom-action-conflicts-with-reserved-web-action-path",
                    "custom action conflicts with reserved web action path: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.category() != EntityActionCategory.STANDARD
                && PlatformAction.fromCode(action.actionCode()).isPresent()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.custom-action-conflicts-with-platform-standard-action",
                    "custom action conflicts with platform standard action: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        validateActionAccessPolicy(action);
        if (action.dataAuth() && !entity.supports(EntityCapability.DATA_SCOPE)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.data-auth-action-requires-data-scope-capability",
                    "data auth action requires DATA_SCOPE capability: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        validateActionAvailability(action, entity, entities, relations);
        boolean standardAction = EntityStandardActionCatalog.supportsStandardAction(entity, action.actionCode());
        if (!standardAction && action.category() == EntityActionCategory.STANDARD) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.standard-action-is-not-supported-by-entity",
                    "standard action is not supported by entity: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (standardAction && action.category() != EntityActionCategory.STANDARD) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.custom-action-conflicts-with-standard-action",
                    "custom action conflicts with standard action: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (standardAction && action.level() != EntityActionDefinition.defaultLevel(action.actionCode(), EntityActionCategory.STANDARD)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.standard-action-level-must-match-platform-action",
                    "standard action level must match platform action: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (standardAction && action.executorType() != EntityActionExecutorType.STANDARD) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.standard-action-executor-must-be-standard",
                    "standard action executor must be STANDARD: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (standardAction && action.executorKey() != null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.standard-action-executor-key-must-be-empty",
                    "standard action executor key must be empty: {alias}.{actionCode}", Map.of("alias", String.valueOf(entity.alias()), "actionCode", String.valueOf(action.actionCode()))));
        }
    }

    private void validateActionAccessPolicy(EntityActionDefinition action) {
        if (action.accessMode() == EntityActionAccessMode.ANONYMOUS_ALLOWED) {
            if (action.actionAuth() || action.dataAuth() || action.authInheritActionCode() != null
                    || action.defaultGrantPolicy() != ActionDefaultGrantPolicy.NONE) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.anonymous-action-must-not-require-auth-policy",
                    "anonymous action must not require auth policy: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
            }
            return;
        }
        if (action.accessMode() == EntityActionAccessMode.LOGIN_REQUIRED) {
            if (action.actionAuth() || action.dataAuth() || action.authInheritActionCode() != null
                    || action.defaultGrantPolicy() != ActionDefaultGrantPolicy.NONE) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.login-only-action-must-not-require-auth-policy",
                    "login-only action must not require auth policy: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
            }
            return;
        }
        if (!action.actionAuth()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.auth-required-action-must-enable-action-auth",
                    "auth-required action must enable action auth: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.authInheritActionCode() != null && !action.actionAuth()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-requires-action-auth",
                    "action auth inherit requires action auth: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
        }
        if (action.defaultGrantPolicy().requiresDataScope() && !action.dataAuth()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.scoped-default-grant-requires-data-auth",
                    "scoped default grant requires data auth: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
        }
    }

    private void validateActionAvailability(EntityActionDefinition action,
                                            EntityDefinition entity,
                                            Map<String, EntityDefinition> entities,
                                            List<EntityRelationDefinition> relations) {
        if (action.availableExpression() == null) {
            return;
        }
        requireText(action.availableExpression(), "action available expression");
        try {
            Object parsed = formulaEngine.parse(action.actionCode(), action.availableExpression());
            if (parsed == null) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-available-expression-is-empty",
                    "invalid action available expression: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
            }
            if (formulaEngine.containsAssignment(action.availableExpression())) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-available-expression-must-not-assign-fields",
                    "action available expression must not assign fields: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
            }
            validateActionAvailabilityFields(action, entity, entities, relations);
        } catch (FormulaEvaluationException e) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-action-available-expression",
                    "invalid action available expression: {actionCode}, {diagnostic}", Map.of("actionCode", String.valueOf(action.actionCode()), "diagnostic", String.valueOf(e.getMessage()))));
        }
        if (action.unavailableMessage() != null && action.unavailableMessage().isBlank()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-unavailable-message-must-not-be-blank",
                    "action unavailable message must not be blank: {actionCode}", Map.of("actionCode", String.valueOf(action.actionCode()))));
        }
    }

    private void validateActionAvailabilityFields(EntityActionDefinition action,
                                                  EntityDefinition entity,
                                                  Map<String, EntityDefinition> entities,
                                                  List<EntityRelationDefinition> relations) {
        for (String fieldPath : formulaEngine.referencedFields(action.availableExpression())) {
            if (!fieldPath.contains(".")) {
                requireField(entity, fieldPath, "action available expression field");
                continue;
            }
            String[] parts = fieldPath.split("\\.");
            if (parts.length != 2) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-action-available-expression-field",
                    "invalid action available expression field: {actionCode}.{fieldPath}", Map.of("actionCode", String.valueOf(action.actionCode()), "fieldPath", String.valueOf(fieldPath))));
            }
            EntityRelationDefinition relation = relations.stream()
                    .filter(candidate -> entity.alias().equals(candidate.parentEntityAlias())
                            && parts[0].equals(candidate.code()))
                    .findFirst()
                    .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-action-available-expression-relation",
                    "unknown action available expression relation: {actionCode}.{relationCode}", Map.of("actionCode", String.valueOf(action.actionCode()), "relationCode", String.valueOf(parts[0])))));
            EntityDefinition childEntity = requireEntity(entities, relation.childEntityAlias(),
                    "action available expression child entity");
            requireField(childEntity, parts[1], "action available expression field");
        }
    }

    private void validateActionAuthInherits(List<EntityActionDefinition> actions,
                                            Map<String, EntityDefinition> entities) {
        Map<String, Set<String>> configuredByEntity = actions.stream()
                .collect(Collectors.groupingBy(
                        EntityActionDefinition::entityAlias,
                        Collectors.mapping(EntityActionDefinition::actionCode, Collectors.toSet())
                ));
        for (EntityActionDefinition action : actions) {
            if (action.authInheritActionCode() == null) {
                continue;
            }
            requireActionCode(action.authInheritActionCode(), "action auth inherit code");
            if (action.actionCode().equals(action.authInheritActionCode())) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-code-must-not-point-to-self",
                    "action auth inherit code must not point to self: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "actionCode", String.valueOf(action.actionCode()))));
            }
            if (!actionExists(action.entityAlias(), action.authInheritActionCode(), entities, configuredByEntity)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-declared-target-is-not-configured",
                    "action auth inherit target is not configured: {entityAlias}.{authInheritActionCode}", Map.of("entityAlias", String.valueOf(action.entityAlias()), "authInheritActionCode", String.valueOf(action.authInheritActionCode()))));
            }
            detectActionAuthInheritCycle(action, actions, entities, configuredByEntity);
        }
    }

    private boolean actionExists(String entityAlias,
                                 String actionCode,
                                 Map<String, EntityDefinition> entities,
                                 Map<String, Set<String>> configuredByEntity) {
        EntityDefinition entity = entities.get(entityAlias);
        return configuredByEntity.getOrDefault(entityAlias, Set.of()).contains(actionCode)
                || EntityStandardActionCatalog.supportsStandardAction(entity, actionCode);
    }

    private void detectActionAuthInheritCycle(EntityActionDefinition start,
                                              List<EntityActionDefinition> actions,
                                              Map<String, EntityDefinition> entities,
                                              Map<String, Set<String>> configuredByEntity) {
        Map<String, EntityActionDefinition> configured = actions.stream()
                .filter(action -> start.entityAlias().equals(action.entityAlias()))
                .collect(Collectors.toMap(EntityActionDefinition::actionCode, Function.identity(), (left, ignored) -> left));
        Set<String> visited = new HashSet<>();
        visited.add(start.actionCode());
        String current = start.authInheritActionCode();
        while (current != null) {
            if (!visited.add(current)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-cycle",
                    "action auth inherit cycle: {entityAlias}.{actionCode}", Map.of("entityAlias", String.valueOf(start.entityAlias()), "actionCode", String.valueOf(start.actionCode()))));
            }
            if (!actionExists(start.entityAlias(), current, entities, configuredByEntity)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-target-is-not-configured",
                    "action auth inherit target is not configured: {entityAlias}.{current}", Map.of("entityAlias", String.valueOf(start.entityAlias()), "current", String.valueOf(current))));
            }
            EntityActionDefinition next = configured.get(current);
            if (next != null && (!next.actionAuth() || next.accessMode() != EntityActionAccessMode.AUTH_REQUIRED)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.action-auth-inherit-target-must-require-action-auth",
                    "action auth inherit target must require action auth: {entityAlias}.{current}", Map.of("entityAlias", String.valueOf(start.entityAlias()), "current", String.valueOf(current))));
            }
            current = next == null ? null : next.authInheritActionCode();
        }
    }

    public void validateAssociationView(EntityAssociationViewDefinition view,
                                        Map<String, EntityDefinition> entities,
                                        String moduleAlias,
                                        java.util.List<EntityRelationDefinition> relations,
                                        java.util.List<EntityReferenceDefinition> references) {
        if (view == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-must-not-be-null",
                    "association view must not be null"));
        }
        requireAssociationViewCode(view.code(), "association view code");
        EntityDefinition source = requireEntity(entities, view.sourceEntityAlias(), "association view source entity");
        requireModuleAlias(view.targetModuleAlias(), "association view target module alias");
        requireIdentifier(view.targetEntityAlias(), "association view target entity");
        if (view.displayMode() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-display-mode-must-not-be-null",
                    "association view display mode must not be null: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        if (view.viewType() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-type-must-not-be-null",
                    "association view type must not be null: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        EntityDefinition target = null;
        if (moduleAlias != null && moduleAlias.equals(view.targetModuleAlias())) {
            target = requireEntity(entities, view.targetEntityAlias(), "association view target entity");
        }
        boolean hasRelation = view.relationCode() != null && !view.relationCode().isBlank();
        boolean hasReference = view.referenceField() != null && !view.referenceField().isBlank();
        if (hasRelation == hasReference) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-requires-exactly-one-relationcode-or-referencefield",
                    "association view requires exactly one relationCode or referenceField: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        if (hasRelation) {
            requireIdentifier(view.relationCode(), "association view relation code");
            if (moduleAlias != null && !moduleAlias.equals(view.targetModuleAlias())) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-relation-target-module-must-be-current-module",
                    "association view relation target module must be current module: {code}", Map.of("code", String.valueOf(view.code()))));
            }
            requireMatchingRelation(view, relations);
        }
        if (hasReference) {
            requireFieldName(view.referenceField(), "association view reference field");
            requireField(source, view.referenceField(), "association view reference field");
            EntityReferenceDefinition reference = requireMatchingReference(view, references);
            requireMatchingReferenceDisplay(view, reference);
        }
        validateAssociationPath(view);
        validateAssociationRootQueryMapping(view.rootQueryMapping(), source, target);
    }

    public void validateReference(EntityReferenceDefinition reference, Map<String, EntityDefinition> entities, String moduleAlias) {
        validateReference(reference, entities, moduleAlias, List.of());
    }

    private void validateReference(EntityReferenceDefinition reference,
                                   Map<String, EntityDefinition> entities,
                                   String moduleAlias,
                                   List<EntityReferenceDefinition> references) {
        if (reference == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-must-not-be-null",
                    "reference must not be null"));
        }
        EntityDefinition source = requireEntity(entities, reference.sourceEntityAlias(), "reference source entity");
        requireFieldName(reference.sourceField(), "reference source field");
        ReferenceTarget target;
        try {
            target = reference.target();
        } catch (RuntimeException e) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid-reference-target-qualified-name",
                    "invalid reference target qualified name: {targetQualifiedName}", Map.of("targetQualifiedName", String.valueOf(reference.targetQualifiedName()))));
        }
        requireReferenceTargetModuleAlias(target.moduleAlias(), "reference target module alias");
        requireIdentifier(target.entityAlias(), "reference target entity alias");
        requireField(source, reference.sourceField(), "reference source field");
        if (moduleAlias != null && moduleAlias.equals(target.moduleAlias())) {
            requireEntity(entities, target.entityAlias(), "reference target entity");
        }
        Set<String> outputFields = new HashSet<>();
        ReferencePlan plan = referencePlan(reference);
        EntityDefinition targetEntity = moduleAlias != null && moduleAlias.equals(target.moduleAlias())
                ? requireEntity(entities, target.entityAlias(), "reference target entity")
                : null;
        validateReferenceTargetFields(reference, target, targetEntity);
        validateSelectionProjections(plan, target, entities, moduleAlias, references);
        if (!reference.projections().isEmpty()) {
            if (targetEntity != null) {
                requireReferenceTargetCapability(targetEntity, target);
            }
            for (ReferenceProjection projection : reference.projections()) {
                requireFieldName(projection.targetField(), "reference projection target field");
                if (targetEntity != null) {
                    requireField(targetEntity, projection.targetField(), "reference projection target field");
                }
                requireFieldName(projection.outputField(), "reference projection output field");
                requireReferenceOutputField(source, projection.outputField(), "reference projection output field");
                requireUnique(outputFields, projection.outputField(), "reference output field");
            }
        }
        validateReferenceInteractionRules(reference, source, target, entities, moduleAlias);
    }

    /**
     * For a local dynamic target, an alternative candidate match field is only safe when metadata
     * can prove it is tenant-unique. Foreign/static targets validate the same fact through their
     * reference facade at runtime because this module does not own their schema.
     */
    private void validateReferenceTargetFields(EntityReferenceDefinition reference,
                                               ReferenceTarget target,
                                               EntityDefinition targetEntity) {
        if (targetEntity == null) return;
        try {
            reference.integrity().validateTarget(target, targetEntity.supports(EntityCapability.ENABLE));
        } catch (PlatformException failure) {
            throw new ModuleDefinitionException(failure.getMessage(), failure);
        }
        String keyField = reference.plan().targetKeyField();
        if (!StandardEntitySchema.ID_FIELD.equals(keyField)) {
            requireField(targetEntity, keyField, "reference target key field");
            boolean unique = targetEntity.resolvedTenantUniqueConstraints().stream()
                    .anyMatch(constraint -> constraint.conditions().isEmpty() && constraint.fieldNames().equals(List.of(keyField)));
            if (!unique) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-target-key-field-must-be-tenant-unique",
                    "reference target key field must be tenant-unique: {qualifiedName}.{keyField}", Map.of("qualifiedName", String.valueOf(target.qualifiedName()), "keyField", String.valueOf(keyField))));
            }
        }
        String labelField = reference.plan().targetLabelField();
        if (labelField != null) {
            requireField(targetEntity, labelField, "reference target label field");
        }
    }

    /**
     * A selection projection is a relative path from the selected reference target.  For
     * in-module dynamic targets every hop is known at publish time, so reject invalid fields
     * and collection hops before the runtime can attempt a read.
     */
    private void validateSelectionProjections(ReferencePlan plan,
                                              ReferenceTarget target,
                                              Map<String, EntityDefinition> entities,
                                              String moduleAlias,
                                              List<EntityReferenceDefinition> references) {
        if (plan.selectionProjections().isEmpty() && plan.candidateSubtitleProjection() == null) {
            return;
        }
        if (!plan.selectionProjections().isEmpty()
                && plan.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-selection-projection-requires-cardinality-one",
                    "reference selection projection requires cardinality ONE: {sourceField}", Map.of("sourceField", String.valueOf(plan.sourceField()))));
        }
        List<ReferenceSelectionProjection> candidateReads = new java.util.ArrayList<>(plan.selectionProjections());
        if (plan.candidateSubtitleProjection() != null) {
            candidateReads.add(plan.candidateSubtitleProjection());
        }
        for (ReferenceSelectionProjection projection : candidateReads) {
            ReferenceTarget current = target;
            List<String> path = projection.path();
            for (String viaField : path.subList(0, path.size() - 1)) {
                if (moduleAlias == null || !moduleAlias.equals(current.moduleAlias())) {
                    // A foreign module owns this metadata. Its own publish validator and the
                    // runtime resolver validate the remaining declared hop.
                    break;
                }
                EntityDefinition currentEntity = requireEntity(entities, current.entityAlias(),
                        "reference selection projection hop source entity");
                requireField(currentEntity, viaField, "reference selection projection hop");
                EntityReferenceDefinition hop = requireReference(references, currentEntity.alias(), viaField);
                if (hop.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-selection-projection-hop-requires-cardinality-one",
                    "reference selection projection hop requires cardinality ONE: {qualifiedName}.{viaField}", Map.of("qualifiedName", String.valueOf(current.qualifiedName()), "viaField", String.valueOf(viaField))));
                }
                current = hop.target();
            }
            if (moduleAlias != null && moduleAlias.equals(current.moduleAlias())) {
                EntityDefinition terminalEntity = requireEntity(entities, current.entityAlias(),
                        "reference selection projection terminal entity");
                requireReferenceTargetCapability(terminalEntity, current);
                requireField(terminalEntity, projection.targetField(), "reference selection projection terminal field");
            }
        }
    }


    private void validateReferenceLoad(EntityReferenceLoadDefinition load,
                                       Map<String, EntityDefinition> entities,
                                       String moduleAlias,
                                       List<EntityReferenceDefinition> references) {
        if (load == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-load-must-not-be-null",
                    "reference load must not be null"));
        }
        EntityDefinition source = requireEntity(entities, load.sourceEntityAlias(), "reference load source entity");
        EntityReferenceDefinition sourceReference = requireReference(references, load.sourceEntityAlias(), load.sourceField());
        if (!load.hops().isEmpty()
                && sourceReference.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-load-source-must-have-cardinality-one",
                    "reference load source must have cardinality ONE: {sourceEntityAlias}.{sourceField}", Map.of("sourceEntityAlias", String.valueOf(load.sourceEntityAlias()), "sourceField", String.valueOf(load.sourceField()))));
        }
        requireFieldName(load.terminalField(), "reference load terminal field");
        requireFieldName(load.outputField(), "reference load output field");
        requireReferenceOutputField(source, load.outputField(), "reference load output field");
        ReferenceTarget current = sourceReference.target();
        for (net.ximatai.muyun.spring.ability.reference.ReferenceLoadPath.Hop hop : load.hops()) {
            if (hop == null || hop.target() == null || hop.viaField() == null) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-load-hop-requires-target-and-via-field",
                    "reference load hop requires target and via field: {sourceEntityAlias}.{sourceField}", Map.of("sourceEntityAlias", String.valueOf(load.sourceEntityAlias()), "sourceField", String.valueOf(load.sourceField()))));
            }
            requireFieldName(hop.viaField(), "reference load hop via field");
            if (moduleAlias.equals(current.moduleAlias())) {
                EntityDefinition currentEntity = requireEntity(entities, current.entityAlias(), "reference load hop source entity");
                requireField(currentEntity, hop.viaField(), "reference load hop via field");
                EntityReferenceDefinition hopReference = requireReference(references, current.entityAlias(), hop.viaField());
                if (hopReference.cardinality() != net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-load-hop-must-have-cardinality-one",
                    "reference load hop must have cardinality ONE: {qualifiedName}.{viaField}", Map.of("qualifiedName", String.valueOf(current.qualifiedName()), "viaField", String.valueOf(hop.viaField()))));
                }
                if (!hop.target().equals(hopReference.target())) {
                    throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-load-hop-target-does-not-match-reference",
                    "reference load hop target does not match reference: {qualifiedName}.{viaField}", Map.of("qualifiedName", String.valueOf(current.qualifiedName()), "viaField", String.valueOf(hop.viaField()))));
                }
            }
            current = hop.target();
        }
        if (moduleAlias.equals(current.moduleAlias())) {
            EntityDefinition terminal = requireEntity(entities, current.entityAlias(), "reference load terminal entity");
            requireReferenceTargetCapability(terminal, current);
            requireField(terminal, load.terminalField(), "reference load terminal field");
        }
    }

    private void validateReferencedBy(EntityReferencedByDefinition referencedBy,
                                      Map<String, EntityDefinition> entities,
                                      String moduleAlias,
                                      List<EntityReferenceDefinition> references) {
        if (referencedBy == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.referenced-by-declaration-must-not-be-null",
                    "referenced-by declaration must not be null"));
        }
        EntityDefinition target = requireEntity(entities, referencedBy.targetEntityAlias(), "referenced-by target entity");
        EntityDefinition source = requireEntity(entities, referencedBy.sourceEntityAlias(), "referenced-by source entity");
        requireFieldName(referencedBy.sourceField(), "referenced-by source field");
        requireFieldName(referencedBy.outputField(), "referenced-by output field");
        requireReferenceOutputField(target, referencedBy.outputField(), "referenced-by output field");
        EntityReferenceDefinition reference = requireReference(references, source.alias(), referencedBy.sourceField());
        if (!ReferenceTarget.of(moduleAlias, target.alias()).equals(reference.target())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.referenced-by-source-does-not-target-entity",
                    "referenced-by source does not target entity: {alias}.{sourceField}", Map.of("alias", String.valueOf(source.alias()), "sourceField", String.valueOf(referencedBy.sourceField()))));
        }
    }

    private EntityReferenceDefinition requireReference(List<EntityReferenceDefinition> references,
                                                        String sourceEntityAlias,
                                                        String sourceField) {
        return references.stream()
                .filter(reference -> sourceEntityAlias.equals(reference.sourceEntityAlias()))
                .filter(reference -> sourceField.equals(reference.sourceField()))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-reference",
                    "unknown reference: {sourceEntityAlias}.{sourceField}", Map.of("sourceEntityAlias", String.valueOf(sourceEntityAlias), "sourceField", String.valueOf(sourceField)))));
    }

    private void validateReferenceInteractionRules(EntityReferenceDefinition reference,
                                                   EntityDefinition source,
                                                   ReferenceTarget target,
                                                   Map<String, EntityDefinition> entities,
                                                   String moduleAlias) {
        EntityDefinition targetEntity = moduleAlias != null && moduleAlias.equals(target.moduleAlias())
                ? requireEntity(entities, target.entityAlias(), "reference target entity")
                : null;
        for (EntityReferenceFilterDefinition filter : reference.filters()) {
            requireFieldName(filter.formField(), "reference filter form field");
            requireField(source, filter.formField(), "reference filter form field");
            requireFieldName(filter.referenceField(), "reference filter target field");
            if (targetEntity != null) {
                requireField(targetEntity, filter.referenceField(), "reference filter target field");
            }
        }
        for (EntityReferenceAffectDefinition affect : reference.affects()) {
            requireFieldName(affect.referenceField(), "reference affect source field");
            if (targetEntity != null) {
                requireField(targetEntity, affect.referenceField(), "reference affect source field");
            }
            requireFieldName(affect.targetField(), "reference affect target field");
            requireField(source, affect.targetField(), "reference affect target field");
        }
    }

    private ReferencePlan referencePlan(EntityReferenceDefinition reference) {
        try {
            return reference.plan();
        } catch (PlatformException e) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-output-field-invalid",
                    "reference output field invalid: {diagnostic}", Map.of("diagnostic", String.valueOf(e.getMessage()))));
        }
    }

    private void requireReferenceTargetCapability(EntityDefinition targetEntity, ReferenceTarget target) {
        if (!targetEntity.supports(EntityCapability.REFERENCE)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-display-target-requires-reference-capability",
                    "reference display target requires REFERENCE capability: {qualifiedName}", Map.of("qualifiedName", String.valueOf(target.qualifiedName()))));
        }
    }

    private void requireReferenceOutputField(EntityDefinition source, String outputField, String name) {
        if (STANDARD_FIELDS.contains(outputField) || source.fields().stream().anyMatch(field -> field.fieldName().equals(outputField))) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.conflicts-with-entity-field",
                    "{name} conflicts with entity field: {alias}.{outputField}", Map.of("name", String.valueOf(name), "alias", String.valueOf(source.alias()), "outputField", String.valueOf(outputField))));
        }
    }

    private void requireMatchingRelation(EntityAssociationViewDefinition view,
                                         java.util.List<EntityRelationDefinition> relations) {
        boolean exists = relations.stream().anyMatch(relation ->
                view.relationCode().equals(relation.code())
                        && view.sourceEntityAlias().equals(relation.parentEntityAlias())
                        && view.targetEntityAlias().equals(relation.childEntityAlias()));
        if (!exists) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-relation-does-not-match-module-relation",
                    "association view relation does not match module relation: {sourceEntityAlias}.{code}", Map.of("sourceEntityAlias", String.valueOf(view.sourceEntityAlias()), "code", String.valueOf(view.code()))));
        }
    }

    private EntityReferenceDefinition requireMatchingReference(EntityAssociationViewDefinition view,
                                                              java.util.List<EntityReferenceDefinition> references) {
        Optional<EntityReferenceDefinition> found = references.stream().filter(reference -> {
            if (!view.sourceEntityAlias().equals(reference.sourceEntityAlias())
                    || !view.referenceField().equals(reference.sourceField())) {
                return false;
            }
            ReferenceTarget target;
            try {
                target = reference.target();
            } catch (RuntimeException e) {
                return false;
            }
            String effectiveModuleAlias = target.moduleAlias();
            return view.targetModuleAlias().equals(effectiveModuleAlias)
                    && view.targetEntityAlias().equals(target.entityAlias());
        }).findFirst();
        if (found.isEmpty()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-reference-does-not-match-module-reference",
                    "association view reference does not match module reference: {sourceEntityAlias}.{code}", Map.of("sourceEntityAlias", String.valueOf(view.sourceEntityAlias()), "code", String.valueOf(view.code()))));
        }
        return found.get();
    }

    private void requireMatchingReferenceDisplay(EntityAssociationViewDefinition view, EntityReferenceDefinition reference) {
        if (reference.cardinality() == net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.MANY) {
            if (view.displayMode() != AssociationViewDisplayMode.LINKED_LIST || view.viewType() != EntityViewType.LIST) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.many-reference-association-view-requires-linked-list-list",
                    "many reference association view requires LINKED_LIST LIST: {sourceEntityAlias}.{code}", Map.of("sourceEntityAlias", String.valueOf(view.sourceEntityAlias()), "code", String.valueOf(view.code()))));
            }
            return;
        }
        if (view.displayMode() != AssociationViewDisplayMode.LINKED_RECORD || view.viewType() != EntityViewType.FORM) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.single-reference-association-view-requires-linked-record-form",
                    "single reference association view requires LINKED_RECORD FORM: {sourceEntityAlias}.{code}", Map.of("sourceEntityAlias", String.valueOf(view.sourceEntityAlias()), "code", String.valueOf(view.code()))));
        }
    }

    private void validateAssociationPath(EntityAssociationViewDefinition view) {
        if (view.path().isEmpty()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-path-must-not-be-empty",
                    "association view path must not be empty: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        if (view.path().size() != 1) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-path-currently-supports-direct-step-only",
                    "association view path currently supports direct step only: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        for (AssociationViewPathStep step : view.path()) {
            if (step.type() == AssociationViewPathStepType.REFERENCE) {
                requireFieldName(step.code(), "association view path reference field");
            } else {
                requireIdentifier(step.code(), "association view path relation code");
            }
            requireIdentifier(step.sourceEntityAlias(), "association view path source entity");
            requireModuleAlias(step.targetModuleAlias(), "association view path target module alias");
            requireIdentifier(step.targetEntityAlias(), "association view path target entity");
        }
        AssociationViewPathStep step = view.path().getFirst();
        if (!view.sourceEntityAlias().equals(step.sourceEntityAlias())
                || !view.targetModuleAlias().equals(step.targetModuleAlias())
                || !view.targetEntityAlias().equals(step.targetEntityAlias())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-path-does-not-match-view-endpoints",
                    "association view path does not match view endpoints: {code}", Map.of("code", String.valueOf(view.code()))));
        }
        if (view.relationCode() != null && !view.relationCode().isBlank()) {
            if (step.type() != AssociationViewPathStepType.RELATION || !view.relationCode().equals(step.code())) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-path-relation-does-not-match-relationcode",
                    "association view path relation does not match relationCode: {code}", Map.of("code", String.valueOf(view.code()))));
            }
            return;
        }
        if (step.type() != AssociationViewPathStepType.REFERENCE || !view.referenceField().equals(step.code())) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-path-reference-does-not-match-referencefield",
                    "association view path reference does not match referenceField: {code}", Map.of("code", String.valueOf(view.code()))));
        }
    }

    private void validateAssociationRootQueryMapping(AssociationViewRootQueryMapping mapping,
                                                     EntityDefinition source,
                                                     EntityDefinition target) {
        if (mapping == null) {
            return;
        }
        if (!mapping.leaf()) {
            for (AssociationViewRootQueryMapping child : mapping.children()) {
                validateAssociationRootQueryMapping(child, source, target);
            }
            return;
        }
        requireFieldName(mapping.targetField(), "association view rootQueryMapping target field");
        if (target != null) {
            requireField(target, mapping.targetField(), "association view rootQueryMapping target field");
        }
        if (mapping.sourceType() == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.association-view-rootquerymapping-source-type-must-not-be-null",
                    "association view rootQueryMapping source type must not be null"));
        }
        switch (mapping.sourceType()) {
            case SOURCE_FIELD -> {
                requireFieldName(mapping.sourceField(), "association view rootQueryMapping source field");
                requireField(source, mapping.sourceField(), "association view rootQueryMapping source field");
            }
            case SYSTEM_VARIABLE -> requireText(mapping.systemVariable(), "association view rootQueryMapping system variable");
            case CONSTANT -> {
            }
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.must-not-be-blank",
                    "{name} must not be blank", Map.of("name", String.valueOf(name))));
        }
    }

    private void requireIdentifier(String value, String name) {
        requireText(value, name);
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid",
                    "invalid {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private void requireFieldName(String value, String name) {
        requireText(value, name);
        if (!value.matches("[a-z][A-Za-z0-9]{0,62}")) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid",
                    "invalid {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private void requireActionCode(String value, String name) {
        requireText(value, name);
        if (!value.matches("[a-z][A-Za-z0-9]{0,63}")) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid",
                    "invalid {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private void requireAssociationViewCode(String value, String name) {
        requireText(value, name);
        if (!value.matches("[a-z][A-Za-z0-9_]{0,63}")) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid",
                    "invalid {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private String physicalTableKey(EntityDefinition entity) {
        String schemaName = entity.schemaName() == null || entity.schemaName().isBlank() ? "" : entity.schemaName();
        return schemaName + "." + entity.tableName();
    }

    private void requireTitleField(EntityDefinition entity, FieldDefinition field) {
        if (field == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-capability-requires-standard-field-title",
                    "REFERENCE capability requires standard field title: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (!PlatformAbilityFields.TITLE_FIELD.equals(field.fieldName())
                || !PlatformAbilityFields.TITLE_COLUMN.equals(field.columnName())
                || field.type() != FieldType.STRING) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.reference-capability-requires-standard-field-title-title",
                    "REFERENCE capability requires standard field title/title: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
    }

    private void requireModuleAlias(String value, String name) {
        try {
            PlatformNameRules.requireModuleAlias(value);
        } catch (RuntimeException e) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.invalid",
                    "invalid {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    /**
     * Dynamic targets retain platform module aliases such as {@code sales.contract}; a static
     * service target uses the complete static platform module alias produced by
     * {@link net.ximatai.muyun.spring.ability.reference.ReferenceTargets}, such as
     * {@code education.student}, which is resolved to {@code ReferenceTarget("education", "student")}.
     * Both are valid source-neutral ReferenceTarget identities.
     */
    private void requireReferenceTargetModuleAlias(String value, String name) {
        if (PlatformNameRules.isIdentifier(value)) {
            return;
        }
        requireModuleAlias(value, name);
    }

    private void requireUnique(Set<String> values, String value, String name) {
        if (!values.add(value)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.duplicate",
                    "duplicate {name}: {value}", Map.of("name", String.valueOf(name), "value", String.valueOf(value))));
        }
    }

    private EntityDefinition requireEntity(Map<String, EntityDefinition> entities, String code, String name) {
        requireIdentifier(code, name);
        EntityDefinition entity = entities.get(code);
        if (entity == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-entity",
                    "unknown {name}: {code}", Map.of("name", String.valueOf(name), "code", String.valueOf(code))));
        }
        return entity;
    }

    private FieldDefinition requireField(EntityDefinition entity, String fieldName, String name) {
        return entity.fields().stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.unknown-field",
                    "unknown {name}: {alias}.{fieldName}", Map.of("name", String.valueOf(name), "alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName)))));
    }

    private void requireValueSourceField(EntityDefinition entity, String fieldName, String name) {
        if (DISCRIMINATED_VALUE_SOURCE_STANDARD_FIELDS.contains(fieldName)) {
            return;
        }
        requireField(entity, fieldName, name);
    }
}
