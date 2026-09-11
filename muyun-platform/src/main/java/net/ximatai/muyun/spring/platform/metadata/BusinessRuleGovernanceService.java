package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaEvaluationException;
import net.ximatai.muyun.spring.common.formula.FormulaExecutionResult;
import net.ximatai.muyun.spring.common.formula.FormulaFieldDefinition;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleExecutionPlan;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFormulaFieldDefinitions;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.FormulaReferenceContext;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Governs portable main-record formulas while preserving and validating every other module rule. */
@Service
public class BusinessRuleGovernanceService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final String RULE_CODE_PATTERN = "[a-z][A-Za-z0-9]{0,63}";

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldConfigService fieldConfigService;
    private final ModuleMetadataFormulaRuleService formulaRuleService;
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler;
    private final PlatformModuleDefinitionCompiler moduleDefinitionCompiler;
    private final ModuleDefinitionValidator moduleDefinitionValidator;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;

    public BusinessRuleGovernanceService(PlatformModuleService moduleService,
                                         ModuleMetadataRelationService relationService,
                                         MetadataFieldService fieldService,
                                         MetadataFieldConfigService fieldConfigService,
                                         ModuleMetadataFormulaRuleService formulaRuleService,
                                         MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                         PlatformModuleDefinitionCompiler moduleDefinitionCompiler,
                                         ModuleDefinitionValidator moduleDefinitionValidator,
                                         PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.fieldService = fieldService;
        this.fieldConfigService = fieldConfigService;
        this.formulaRuleService = formulaRuleService;
        this.fieldDefinitionCompiler = fieldDefinitionCompiler;
        this.moduleDefinitionCompiler = moduleDefinitionCompiler;
        this.moduleDefinitionValidator = moduleDefinitionValidator;
        this.refreshCoordinator = refreshCoordinator;
    }

    public BusinessRuleGovernanceSnapshot snapshot(String moduleAlias) {
        String alias = requireDynamicModule(moduleAlias);
        return snapshotContext(alias).snapshot();
    }

    private SnapshotContext snapshotContext(String alias) {
        ModuleMetadataRelation main = mainRelation(alias);
        List<MetadataField> fields = fields(main);
        List<BusinessRuleField> editableFields = fields.stream().filter(field -> editable(field, main)).map(field ->
                new BusinessRuleField(field.getFieldName(), field.getTitle(), field.getFieldSpecAlias(),
                        fieldDefinitionCompiler.compile(field, main.getId()).type().name())).toList();
        List<ModuleMetadataFormulaRule> stored = storedRules(main);
        List<BusinessRuleReferenceField> referenceFields = referenceFields(alias, stored);
        List<BusinessRuleField> aggregateFields = aggregateFields(alias);
        List<String> readableFields = new ArrayList<>(editableFields.stream().map(BusinessRuleField::fieldName).toList());
        readableFields.addAll(referenceFields.stream().map(BusinessRuleReferenceField::path).toList());
        readableFields.addAll(aggregateFields.stream().map(BusinessRuleField::fieldName).toList());
        List<BusinessRuleSnapshotRule> rules = stored.stream()
                .map(rule -> snapshotRule(rule, editableFields.stream().map(BusinessRuleField::fieldName).toList(),
                        readableFields, aggregateFields)).toList();
        return new SnapshotContext(main, new BusinessRuleGovernanceSnapshot(alias,
                fingerprint(main, fields, relevantConfigs(fields, main), rules, aggregateFields), editableFields, rules,
                referenceFields, aggregateFields, functionCatalog()));
    }

    public BusinessRulePreview preview(String moduleAlias, BusinessRulePreviewCommand command) {
        String alias = requireDynamicModule(moduleAlias);
        BusinessRuleGovernanceSnapshot snapshot = snapshot(alias);
        List<BusinessRuleIssue> errors = new ArrayList<>();
        List<FormulaRule> rules = proposals(command == null ? null : command.rules(), snapshot, errors);
        List<String> order = List.of();
        if (errors.isEmpty()) {
            try {
                FormulaReferenceContext references = trialReferences(alias, declaredReferenceRules(rules));
                List<FormulaFieldDefinition> fields = new ArrayList<>(trialFields(alias));
                fields.addAll(references.fields());
                FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forMainRecord(rules, fields);
                validateCandidate(alias, snapshot, storedRules(mainRelation(alias)), rules);
                order = plan.orderedRules().stream().map(FormulaRule::id).toList();
            } catch (FormulaEvaluationException exception) {
                errors.add(issue(exception, null));
            } catch (RuntimeException exception) {
                errors.add(new BusinessRuleIssue("MODULE_DEFINITION_INVALID", null, null, exception.getMessage()));
            }
        }
        return new BusinessRulePreview(snapshot, fingerprint(rules), order, List.copyOf(errors));
    }

    /** Executes only first-phase, main-record rules using the same typed conversion boundary as save. */
    public BusinessRuleTrialResult trial(String moduleAlias, BusinessRuleTrialCommand command) {
        return trial(moduleAlias, command, TenantContext.currentTenantId().orElse(null));
    }

    /**
     * Metadata remains system-scoped for governance, while declared reference reads explicitly
     * restore the request tenant and therefore retain ordinary REFERENCE/data-scope semantics.
     */
    public BusinessRuleTrialResult trial(String moduleAlias, BusinessRuleTrialCommand command, String referenceTenantId) {
        BusinessRulePreview preview = preview(moduleAlias,
                new BusinessRulePreviewCommand(command == null ? List.of() : command.rules()));
        if (!preview.valid()) return new BusinessRuleTrialResult(preview, Map.of(), List.of(), preview.errors());
        List<BusinessRuleIssue> errors = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        if (command != null && command.sampleValues() != null) {
            command.sampleValues().forEach((field, value) -> {
                if (field != null && field.contains(".")) {
                    errors.add(new BusinessRuleIssue("FORMULA_REFERENCE_INPUT_FORBIDDEN", null, field,
                            "引用路径由服务端按根引用实时读取，不能提交 dotted sample 值"));
                } else {
                    values.put(field, value);
                }
            });
        }
        List<FormulaRule> rules = proposals(command == null ? null : command.rules(), preview.snapshot(), errors);
        if (!errors.isEmpty()) return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        try {
            FormulaReferenceContext references = trialReferences(moduleAlias, rules);
            List<FormulaFieldDefinition> fields = new ArrayList<>(trialFields(moduleAlias));
            fields.addAll(references.fields());
            if (!references.isEmpty() && (referenceTenantId == null || referenceTenantId.isBlank())) {
                errors.add(new BusinessRuleIssue("FORMULA_REFERENCE_TENANT_REQUIRED", null, null,
                        "引用试算需要请求租户上下文"));
                return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
            }
            FormulaExecutionResult result = FormulaRuleExecutionPlan.forMainRecord(rules, fields)
                    .execute(new FormulaEngine(), FormulaRuntimeData.typed(values, Map.of(), fields,
                            references.paths(), current -> resolveReferences(references, current, referenceTenantId)));
            result.report().errors().forEach(item -> errors.add(new BusinessRuleIssue(item.code(), item.ruleId(),
                    item.fieldPath(), item.message())));
            Map<String, Object> responseValues = new LinkedHashMap<>(values);
            responseValues.putAll(resolveReferences(references, values, referenceTenantId));
            return new BusinessRuleTrialResult(preview, immutableValues(responseValues), result.changedFields(), List.copyOf(errors));
        } catch (FormulaEvaluationException exception) {
            errors.add(issue(exception, null));
            return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        } catch (IllegalArgumentException exception) {
            errors.add(new BusinessRuleIssue("FORMULA_REFERENCE_PATH_INVALID", null, null, exception.getMessage()));
            return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        }
    }

    @Transactional
    public BusinessRuleApplyResult apply(String moduleAlias, BusinessRuleApplyCommand command) {
        if (command == null) throw new IllegalArgumentException("business-rule apply command is required");
        String alias = requireDynamicModule(moduleAlias);
        BusinessRulePreview preview = preview(alias, new BusinessRulePreviewCommand(command.rules()));
        if (!preview.valid()) throw new PlatformException("business-rule proposal is invalid: "
                + preview.errors().stream().map(BusinessRuleIssue::code).toList());
        if (!Objects.equals(command.proposalFingerprint(), preview.proposalFingerprint())) {
            throw new PlatformException("business-rule proposal is stale; preview again before apply");
        }
        SnapshotContext currentContext = snapshotContext(alias);
        BusinessRuleGovernanceSnapshot current = currentContext.snapshot();
        if (!Objects.equals(command.baselineFingerprint(), current.baselineFingerprint())) {
            throw new PlatformException("business-rule baseline is stale; reload and preview again");
        }
        // Keep the exact relation version represented by the accepted baseline for the CAS below.
        ModuleMetadataRelation main = currentContext.main();
        MetadataCapabilityGovernanceMutationContext.run(() -> {
            // Claim the relation version before reading rules. Otherwise a concurrent winner can
            // make this stale snapshot combine its proposal with newly persisted rules.
            // The relation is the module-owned optimistic-version gate. Its CAS rejects concurrent applies.
            relationService.update(main);
            List<ModuleMetadataFormulaRule> existing = storedRules(main);
            List<BusinessRuleIssue> proposalErrors = new ArrayList<>();
            List<FormulaRule> proposalRules = proposals(command.rules(), current, proposalErrors);
            if (!proposalErrors.isEmpty()) throw new PlatformException("business-rule proposal is invalid: "
                    + proposalErrors.stream().map(BusinessRuleIssue::code).toList());
            validateCandidate(alias, current, existing, proposalRules);
            persistProposal(main, existing, current, command.rules());
            return null;
        });
        TransactionScopeSupport.afterCommitOrNow(() -> refreshCoordinator.activateModulesNow(List.of(alias)));
        return new BusinessRuleApplyResult(snapshot(alias), preview, List.of(alias));
    }

    private void persistProposal(ModuleMetadataRelation main, List<ModuleMetadataFormulaRule> existing,
                                 BusinessRuleGovernanceSnapshot snapshot, List<BusinessRuleProposal> proposals) {
        Map<String, ModuleMetadataFormulaRule> existingByCode = new LinkedHashMap<>();
        existing.forEach(rule -> existingByCode.put(rule.getAlias(), rule));
        Set<String> retainedEditableCodes = new LinkedHashSet<>();
        int sort = 100;
        for (BusinessRuleProposal proposal : proposals == null ? List.<BusinessRuleProposal>of() : proposals) {
            ModuleMetadataFormulaRule stored = existingByCode.get(proposal.code());
            if (stored != null && !editableSnapshotRule(stored, snapshot)) {
                throw new PlatformException("business-rule code is read-only in this editor: " + proposal.code());
            }
            retainedEditableCodes.add(proposal.code());
            if (stored == null) {
                stored = new ModuleMetadataFormulaRule();
                stored.setRelationId(main.getId());
                stored.setAlias(proposal.code());
                stored.setTitle(proposal.code());
            }
            stored.setRuleKind(proposal.kind());
            stored.setRulePhase(FormulaRulePhase.BEFORE_SAVE);
            stored.setTargetField(proposal.targetField());
            stored.setExpression(storedExpression(proposal));
            stored.setEnabled(proposal.enabled());
            stored.setMessageTemplate(blankToNull(proposal.messageTemplate()));
            stored.setSeverity(FormulaIssueLevel.ERROR);
            stored.setStopOnError(proposal.kind() == FormulaRuleKind.VALIDATION);
            stored.setSortOrder(sort);
            sort += 100;
            if (stored.getId() == null) formulaRuleService.insert(stored); else formulaRuleService.update(stored);
        }
        for (ModuleMetadataFormulaRule stored : existing) {
            if (!retainedEditableCodes.contains(stored.getAlias()) && editableSnapshotRule(stored, snapshot)) {
                formulaRuleService.delete(stored.getId());
            }
        }
    }

    private void validateCandidate(String moduleAlias, BusinessRuleGovernanceSnapshot snapshot,
                                   List<ModuleMetadataFormulaRule> existing,
                                   List<FormulaRule> proposalRules) {
        ModuleDefinition compiled = moduleDefinitionCompiler.compile(moduleAlias);
        List<EntityFormulaRuleDefinition> candidateRules = new ArrayList<>();
        for (ModuleMetadataFormulaRule stored : existing) {
            if (!editableSnapshotRule(stored, snapshot)) candidateRules.add(formulaRuleService.compile(stored));
        }
        int sort = 100;
        for (FormulaRule proposal : proposalRules) {
            candidateRules.add(new EntityFormulaRuleDefinition(proposal.id(), proposal.expression(), proposal.kind(),
                    proposal.phase(), proposal.targetField(), proposal.severity(), proposal.messageTemplate(),
                    proposal.stopOnError(), proposal.enabled(), sort));
            sort += 100;
        }
        List<EntityDefinition> entities = compiled.entities().stream().map(entity -> entity.alias().equals(compiled.mainEntityAlias())
                ? new EntityDefinition(entity.alias(), entity.schemaName(), entity.tableName(), entity.name(), entity.fields(),
                entity.capabilities(), candidateRules, entity.tenantUniqueConstraints(), entity.sortPartitionFields(),
                entity.fileReferences()) : entity).toList();
        moduleDefinitionValidator.validate(compiled.toBuilder().entities(entities).build());
    }

    private List<FormulaRule> proposals(List<BusinessRuleProposal> proposals, BusinessRuleGovernanceSnapshot snapshot,
                                        List<BusinessRuleIssue> errors) {
        if (proposals == null) return List.of();
        Set<String> fields = snapshot.editableFields().stream().map(BusinessRuleField::fieldName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> readableFields = new LinkedHashSet<>(fields);
        snapshot.referenceFields().forEach(field -> readableFields.add(field.path()));
        snapshot.aggregateFields().forEach(field -> readableFields.add(field.fieldName()));
        Set<String> existingCodes = snapshot.rules().stream().map(BusinessRuleSnapshotRule::code)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> codes = new LinkedHashSet<>();
        List<FormulaRule> result = new ArrayList<>();
        FormulaEngine engine = new FormulaEngine();
        for (BusinessRuleProposal proposal : proposals) {
            String code = proposal == null ? null : proposal.code();
            if (code == null || !code.matches(RULE_CODE_PATTERN) || !codes.add(code)) {
                errors.add(new BusinessRuleIssue("INVALID_RULE_CODE", code, null, "规则编码必须唯一且符合平台编码规则"));
                continue;
            }
            BusinessRuleSnapshotRule existing = snapshot.rules().stream().filter(rule -> code.equals(rule.code())).findFirst().orElse(null);
            if (existingCodes.contains(code) && (existing == null || !existing.editable())) {
                errors.add(new BusinessRuleIssue("READ_ONLY_RULE_CODE", code, null, "该规则保留为只读，不能被业务规则编辑器覆盖"));
                continue;
            }
            if (proposal.kind() != FormulaRuleKind.CALCULATION && proposal.kind() != FormulaRuleKind.VALIDATION) {
                errors.add(new BusinessRuleIssue("UNSUPPORTED_RULE_KIND", code, null, "首期仅支持计算和校验规则"));
                continue;
            }
            if (proposal.expression() == null || proposal.expression().isBlank()) {
                errors.add(new BusinessRuleIssue("INVALID_RULE_EXPRESSION", code, proposal.targetField(), "表达式不能为空且不能包含赋值"));
                continue;
            }
            if (proposal.kind() == FormulaRuleKind.CALCULATION && !fields.contains(proposal.targetField())) {
                errors.add(new BusinessRuleIssue("INVALID_RULE_TARGET", code, proposal.targetField(), "计算目标必须是可配置主表字段"));
                continue;
            }
            String expression = storedExpression(proposal);
            try {
                if (engine.containsAssignment(proposal.expression())) {
                    errors.add(new BusinessRuleIssue("INVALID_RULE_EXPRESSION", code, proposal.targetField(), "表达式不能为空且不能包含赋值"));
                    continue;
                }
                if (proposal.kind() == FormulaRuleKind.CALCULATION) engine.compileFormComputeProgram(expression);
                else if (engine.parse(code, expression) == null) throw new FormulaEvaluationException("FORMULA_EXPRESSION_REQUIRED", "formula expression is required");
                Set<String> invalidInputs = engine.valueSideReferencedFields(expression).stream()
                        .filter(field -> !readableFields.contains(field)
                                && !fields.contains(field.contains(".") ? field.substring(0, field.indexOf('.')) : field))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                Set<String> scalarChildInputs = engine.nonAggregateChildFieldReferences(expression).stream()
                        .filter(readableFields::contains)
                        .filter(field -> snapshot.aggregateFields().stream()
                                .map(BusinessRuleField::fieldName).anyMatch(field::equals))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (!scalarChildInputs.isEmpty()) {
                    scalarChildInputs.forEach(field -> errors.add(new BusinessRuleIssue("SUBTABLE_AGGREGATE_REQUIRED", code, field,
                            "子表字段只能作为 COUNT、SUM、AVG、MAX 或 MIN 的汇总参数")));
                    continue;
                }
                List<BusinessRuleIssue> aggregateTypeErrors = aggregateTypeErrors(engine, expression, snapshot.aggregateFields(), code);
                if (!aggregateTypeErrors.isEmpty()) {
                    errors.addAll(aggregateTypeErrors);
                    continue;
                }
                if (!invalidInputs.isEmpty()) {
                    invalidInputs.forEach(field -> errors.add(new BusinessRuleIssue("INVALID_RULE_INPUT", code, field,
                            "业务规则输入必须是可配置主表字段: " + field)));
                    continue;
                }
                result.add(new FormulaRule(code, expression, proposal.kind(), FormulaRulePhase.BEFORE_SAVE,
                        proposal.targetField(), FormulaIssueLevel.ERROR, blankToNull(proposal.messageTemplate()),
                        proposal.kind() == FormulaRuleKind.VALIDATION, proposal.enabled()));
            } catch (FormulaEvaluationException exception) {
                errors.add(issue(exception, code));
            }
        }
        return List.copyOf(result);
    }

    private List<FormulaFieldDefinition> trialFields(String moduleAlias) {
        ModuleDefinition definition = moduleDefinitionCompiler.compile(requireDynamicModule(moduleAlias));
        EntityDefinition main = definition.entities().stream().filter(entity -> entity.alias().equals(definition.mainEntityAlias()))
                .findFirst().orElseThrow(() -> new PlatformException("compiled module has no main entity: " + moduleAlias));
        List<FormulaFieldDefinition> fields = new ArrayList<>(DynamicFormulaFieldDefinitions.mainFields(main));
        Map<String, EntityDefinition> entities = definition.entities().stream()
                .collect(java.util.stream.Collectors.toMap(EntityDefinition::alias, entity -> entity));
        for (EntityRelationDefinition relation : definition.relations()) {
            if (!main.alias().equals(relation.parentEntityAlias())) continue;
            EntityDefinition child = entities.get(relation.childEntityAlias());
            if (child != null) fields.addAll(DynamicFormulaFieldDefinitions.childFields(relation.code(), child));
        }
        return List.copyOf(fields);
    }

    /**
     * Direct child business columns are readable only inside an aggregate function and are never
     * targets. COUNT can count any displayed business value; numeric aggregations only accept
     * numeric values. Runtime-owned columns and the parent foreign key are omitted altogether.
     */
    private List<BusinessRuleField> aggregateFields(String moduleAlias) {
        ModuleDefinition definition = moduleDefinitionCompiler.compile(requireDynamicModule(moduleAlias));
        EntityDefinition main = definition.entities().stream().filter(entity -> entity.alias().equals(definition.mainEntityAlias()))
                .findFirst().orElseThrow(() -> new PlatformException("compiled module has no main entity: " + moduleAlias));
        Map<String, EntityDefinition> entities = definition.entities().stream()
                .collect(java.util.stream.Collectors.toMap(EntityDefinition::alias, entity -> entity));
        Map<String, ModuleMetadataRelation> sourceRelations = relationService.list(Criteria.of().eq("moduleAlias", moduleAlias)
                        .eq("relationRole", RelationRole.CHILD), ALL, Sort.asc("sortOrder")).stream()
                .collect(java.util.stream.Collectors.toMap(ModuleMetadataRelation::getRelationAlias, relation -> relation));
        List<BusinessRuleField> result = new ArrayList<>();
        for (EntityRelationDefinition relation : definition.relations()) {
            if (!main.alias().equals(relation.parentEntityAlias())) continue;
            EntityDefinition child = entities.get(relation.childEntityAlias());
            ModuleMetadataRelation sourceRelation = sourceRelations.get(relation.code());
            if (child == null || sourceRelation == null) continue;
            Map<String, MetadataField> sourceFields = fields(sourceRelation).stream()
                    .collect(java.util.stream.Collectors.toMap(MetadataField::getFieldName, field -> field));
            child.fields().stream()
                    .filter(field -> aggregateBusinessField(sourceFields.get(field.fieldName()), sourceRelation))
                    .forEach(field -> result.add(new BusinessRuleField(
                            relation.code() + "." + field.fieldName(),
                            child.name() + " · " + field.name(),
                            field.fieldName(),
                            field.type().name(),
                            aggregateFunctions(field.type())
                    )));
        }
        return List.copyOf(result);
    }

    private static boolean aggregateBusinessField(MetadataField field, ModuleMetadataRelation relation) {
        return field != null
                && field.getFieldOwnership() == MetadataFieldOwnership.BUSINESS
                && field.getFieldForm() == MetadataFieldForm.PHYSICAL
                && !Boolean.TRUE.equals(field.getSystemManaged())
                && Boolean.TRUE.equals(field.getEnabled())
                && !field.getFieldName().equals(relation.getForeignKey());
    }

    private static List<String> aggregateFunctions(FieldType type) {
        return switch (type) {
            case INTEGER, LONG, DECIMAL -> List.of("COUNT", "SUM", "AVG", "MAX", "MIN");
            default -> List.of("COUNT");
        };
    }

    private static List<BusinessRuleIssue> aggregateTypeErrors(FormulaEngine engine, String expression,
                                                                 List<BusinessRuleField> aggregateFields, String code) {
        Map<String, Set<String>> allowedFunctions = aggregateFields.stream().collect(java.util.stream.Collectors.toMap(
                BusinessRuleField::fieldName, field -> Set.copyOf(field.aggregateFunctions()), (left, right) -> left,
                LinkedHashMap::new));
        List<BusinessRuleIssue> errors = new ArrayList<>();
        engine.aggregateValueFieldReferences(expression).forEach((field, functions) -> functions.forEach(function -> {
            if (allowedFunctions.containsKey(field) && !allowedFunctions.get(field).contains(function)) {
                errors.add(new BusinessRuleIssue("AGGREGATE_FIELD_TYPE_UNSUPPORTED", code, field,
                        function + " 仅支持数值型子表字段；COUNT 可用于任意可读业务子表字段"));
            }
        }));
        return List.copyOf(errors);
    }

    private FormulaReferenceContext trialReferences(String moduleAlias, List<FormulaRule> rules) {
        ModuleDefinition definition = moduleDefinitionCompiler.compile(requireDynamicModule(moduleAlias));
        EntityDefinition main = definition.entities().stream().filter(entity -> entity.alias().equals(definition.mainEntityAlias()))
                .findFirst().orElseThrow(() -> new PlatformException("compiled module has no main entity: " + moduleAlias));
        return FormulaReferenceContext.compile(ReferenceTarget.of(definition.moduleAlias(), main.alias()), rules,
                PlatformAbilityRuntime.referenceTargetResolver());
    }

    private List<BusinessRuleReferenceField> referenceFields(String moduleAlias,
                                                              List<ModuleMetadataFormulaRule> storedRules) {
        if (storedRules == null || storedRules.isEmpty()) return List.of();
        Map<String, BusinessRuleReferenceField> fields = new LinkedHashMap<>();
        for (ModuleMetadataFormulaRule storedRule : storedRules) {
            try {
                FormulaRule declaredRule = declaredReferenceRule(formulaRuleService.compile(storedRule).toRuntimeRule());
                FormulaReferenceContext references = trialReferences(moduleAlias, List.of(declaredRule));
                references.fields().forEach(field -> fields.putIfAbsent(field.fieldPath().dataIndex(),
                        new BusinessRuleReferenceField(field.fieldPath().dataIndex(), field.fieldPath().dataIndex(),
                                field.type().name())));
            } catch (RuntimeException ignored) {
                // A legacy/non-editable rule remains visible in its own snapshot entry. Its invalid
                // reference declaration must not hide independent inputs required by other rules.
            }
        }
        return List.copyOf(fields.values());
    }

    /**
     * The governance directory describes persisted rule declarations, including disabled rules
     * that may later be re-enabled. Execution still receives the original enabled value.
     */
    private static FormulaRule declaredReferenceRule(FormulaRule rule) {
        return new FormulaRule(rule.id(), rule.expression(), rule.kind(), rule.phase(), rule.targetField(),
                rule.severity(), rule.messageTemplate(), rule.stopOnError(), true);
    }

    private static List<FormulaRule> declaredReferenceRules(List<FormulaRule> rules) {
        return rules == null ? List.of() : rules.stream().map(BusinessRuleGovernanceService::declaredReferenceRule).toList();
    }

    private Map<String, Object> resolveReferences(FormulaReferenceContext references, Map<String, Object> values,
                                                   String referenceTenantId) {
        if (references.isEmpty()) return Map.of();
        try (TenantContext.Scope ignored = TenantContext.use(referenceTenantId)) {
            return references.resolve(values);
        }
    }

    private String requireDynamicModule(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModule module = moduleService.select(alias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new IllegalArgumentException("business rules require a dynamic module: " + alias);
        }
        return alias;
    }

    private ModuleMetadataRelation mainRelation(String moduleAlias) {
        return relationService.list(Criteria.of().eq("moduleAlias", PlatformNameRules.requireModuleAlias(moduleAlias))
                        .eq("relationRole", RelationRole.MAIN), ALL, Sort.asc("sortOrder"))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("dynamic module has no main metadata: " + moduleAlias));
    }

    private List<MetadataField> fields(ModuleMetadataRelation main) {
        return fieldService.list(Criteria.of().eq("metadataId", main.getMetadataId()), ALL, Sort.asc("sortOrder"));
    }

    private List<ModuleMetadataFormulaRule> storedRules(ModuleMetadataRelation main) {
        return formulaRuleService.listByRelationIds(List.of(main.getId())).stream()
                .sorted(Comparator.comparing(ModuleMetadataFormulaRule::getSortOrder, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(ModuleMetadataFormulaRule::getAlias)).toList();
    }

    private List<MetadataFieldConfig> relevantConfigs(List<MetadataField> fields, ModuleMetadataRelation relation) {
        Map<String, MetadataFieldConfig> configs = new LinkedHashMap<>();
        for (MetadataField field : fields) {
            MetadataFieldConfig base = fieldConfigService.findByMetadataFieldId(field.getId());
            MetadataFieldConfig override = fieldConfigService.findRelationOverride(field.getId(), relation.getId());
            if (base != null) configs.put(base.getId(), base);
            if (override != null) configs.put(override.getId(), override);
        }
        return configs.values().stream().sorted(Comparator.comparing(MetadataFieldConfig::getId)).toList();
    }

    private boolean editable(MetadataField field, ModuleMetadataRelation relation) {
        if (field.getFieldOwnership() != MetadataFieldOwnership.BUSINESS || field.getFieldForm() != MetadataFieldForm.PHYSICAL
                || Boolean.TRUE.equals(field.getSystemManaged()) || !Boolean.TRUE.equals(field.getEnabled())) return false;
        var definition = fieldDefinitionCompiler.compile(field, relation.getId());
        return definition.type() != FieldType.JSON && !definition.behavior().writeProtected();
    }

    private static boolean editableSnapshotRule(ModuleMetadataFormulaRule rule, BusinessRuleGovernanceSnapshot snapshot) {
        return snapshot.rules().stream().filter(item -> item.code().equals(rule.getAlias()))
                .findFirst().map(BusinessRuleSnapshotRule::editable).orElse(false);
    }

    private static BusinessRuleSnapshotRule snapshotRule(ModuleMetadataFormulaRule rule, List<String> editableFields,
                                                         List<String> readableFields, List<BusinessRuleField> aggregateFields) {
        String expression = rule.getExpression();
        boolean editable = (rule.getRuleKind() == FormulaRuleKind.CALCULATION || rule.getRuleKind() == FormulaRuleKind.VALIDATION)
                && rule.getRulePhase() == FormulaRulePhase.BEFORE_SAVE
                && (rule.getTargetField() == null || editableFields.contains(rule.getTargetField()));
        editable &= rule.getRuleKind() == FormulaRuleKind.CALCULATION
                ? rule.getSeverity() == FormulaIssueLevel.ERROR && !Boolean.TRUE.equals(rule.getStopOnError())
                : rule.getSeverity() == FormulaIssueLevel.ERROR && Boolean.TRUE.equals(rule.getStopOnError());
        if (editable && rule.getRuleKind() == FormulaRuleKind.CALCULATION) {
            String rhs = calculationRhs(rule);
            if (rhs == null) editable = false; else expression = rhs;
        }
        if (editable) {
            try {
                FormulaEngine engine = new FormulaEngine();
                editable = !engine.containsAssignment(rule.getRuleKind() == FormulaRuleKind.CALCULATION ? rule.getExpression() : expression)
                        || rule.getRuleKind() == FormulaRuleKind.CALCULATION;
                editable &= engine.valueSideReferencedFields(rule.getExpression()).stream()
                        .allMatch(readableFields::contains);
                editable &= engine.nonAggregateChildFieldReferences(rule.getExpression()).stream()
                        .noneMatch(field -> aggregateFields.stream().map(BusinessRuleField::fieldName).anyMatch(field::equals));
                editable &= aggregateTypeErrors(engine, rule.getExpression(), aggregateFields, rule.getAlias()).isEmpty();
            } catch (FormulaEvaluationException exception) {
                editable = false;
            }
        }
        return new BusinessRuleSnapshotRule(rule.getAlias(), rule.getRuleKind(), rule.getRulePhase(), rule.getTargetField(), expression,
                Boolean.TRUE.equals(rule.getEnabled()), rule.getSeverity(), rule.getMessageTemplate(), Boolean.TRUE.equals(rule.getStopOnError()),
                editable, editable ? null : "首期仅支持可无损编辑的主表 BEFORE_SAVE 业务规则");
    }

    private static String calculationRhs(ModuleMetadataFormulaRule rule) {
        if (rule.getTargetField() == null || rule.getExpression() == null) return null;
        String prefix = "{" + rule.getTargetField() + "}";
        String value = rule.getExpression().trim();
        if (!value.startsWith(prefix)) return null;
        String remaining = value.substring(prefix.length()).trim();
        if (!remaining.startsWith("=")) return null;
        remaining = remaining.substring(1).trim();
        if (remaining.length() < 2 || remaining.charAt(0) != '(' || remaining.charAt(remaining.length() - 1) != ')') return null;
        try {
            new FormulaEngine().compileFormComputeProgram(value);
            return remaining.substring(1, remaining.length() - 1).trim();
        } catch (FormulaEvaluationException ignored) {
            return null;
        }
    }

    private static String storedExpression(BusinessRuleProposal proposal) {
        return proposal.kind() == FormulaRuleKind.CALCULATION
                ? "{" + proposal.targetField() + "} = (" + proposal.expression().trim() + ")" : proposal.expression().trim();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static List<BusinessRuleFunction> functionCatalog() {
        return List.of(
                new BusinessRuleFunction("PRESENT", "条件判断", "判断字段有值", "判断字段是否已经填写，用于必填校验或决定后续计算是否执行", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值")), "BOOLEAN", "PRESENT({supplierId})"),
                new BusinessRuleFunction("ISNULL", "条件判断", "判断字段为空", "判断字段是否为空，用于为空时给出提示或补默认处理", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值")), "BOOLEAN", "ISNULL({supplierId})"),
                new BusinessRuleFunction("IN", "条件判断", "匹配候选项", "判断字段是否匹配给定候选项，用于状态、类型等有限枚举的判断", List.of(
                        new BusinessRuleFunctionParameter("value", "要判断的值"),
                        new BusinessRuleFunctionParameter("candidates", "一个或多个候选值")), "BOOLEAN", "IN({status}, 'draft', 'active')"),
                new BusinessRuleFunction("TODAY", "日期时间", "获取今天", "取得当前日期，适合计算当天生效、截止日期等规则", List.of(),
                        "DATE", "TODAY()"),
                new BusinessRuleFunction("NOW", "日期时间", "获取当前时间", "取得当前 UTC 时间，适合记录当前时刻或比较时效", List.of(),
                        "TIMESTAMP", "NOW()"),
                new BusinessRuleFunction("YEAR", "日期时间", "提取年份", "从日期或时间中提取年份，适合按年度计算或校验", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "YEAR({signedAt})"),
                new BusinessRuleFunction("MONTH", "日期时间", "提取月份", "从日期或时间中提取月份，适合按月判断或分组计算", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "MONTH({signedAt})"),
                new BusinessRuleFunction("DAY", "日期时间", "提取日期", "从日期或时间中提取日，适合按具体日期触发规则", List.of(
                        new BusinessRuleFunctionParameter("value", "日期或时间字段")), "INTEGER", "DAY({signedAt})"),
                new BusinessRuleFunction("DATE_ADD", "日期时间", "增加日期", "在日期基础上增加天数，适合推算到期日、提醒日", List.of(
                        new BusinessRuleFunctionParameter("date", "yyyy-MM-dd 日期"),
                        new BusinessRuleFunctionParameter("days", "整数天数")), "DATE", "DATE_ADD({signedDate}, 7)"),
                new BusinessRuleFunction("DATE_SUB", "日期时间", "减少日期", "在日期基础上减少天数，适合推算提前提醒日、最早日期", List.of(
                        new BusinessRuleFunctionParameter("date", "yyyy-MM-dd 日期"),
                        new BusinessRuleFunctionParameter("days", "整数天数")), "DATE", "DATE_SUB({signedDate}, 7)"),
                new BusinessRuleFunction("DATETIME_ADD", "日期时间", "增加时间", "在时间基础上增加指定间隔，适合计算精确的截止时间", List.of(
                        new BusinessRuleFunctionParameter("dateTime", "UTC 时间"),
                        new BusinessRuleFunctionParameter("amount", "整数间隔"),
                        new BusinessRuleFunctionParameter("unit", "DAY/HOUR/MINUTE/SECOND")), "TIMESTAMP",
                        "DATETIME_ADD({signedAt}, 2, 'HOUR')"),
                new BusinessRuleFunction("DATETIME_SUB", "日期时间", "减少时间", "在时间基础上减少指定间隔，适合计算提前触发时间", List.of(
                        new BusinessRuleFunctionParameter("dateTime", "UTC 时间"),
                        new BusinessRuleFunctionParameter("amount", "整数间隔"),
                        new BusinessRuleFunctionParameter("unit", "DAY/HOUR/MINUTE/SECOND")), "TIMESTAMP",
                        "DATETIME_SUB({signedAt}, 2, 'HOUR')"),
                new BusinessRuleFunction("DATE_DIFF_DAYS", "日期时间", "计算相差天数", "计算两个日期或时间相差的天数，适合期限与逾期判断", List.of(
                        new BusinessRuleFunctionParameter("start", "开始日期或时间"),
                        new BusinessRuleFunctionParameter("end", "结束日期或时间")), "DECIMAL",
                        "DATE_DIFF_DAYS({startDate}, {endDate})"),
                new BusinessRuleFunction("DATE_DIFF_HOURS", "日期时间", "计算相差小时", "计算两个时间相差的小时数，适合时效与工时判断", List.of(
                        new BusinessRuleFunctionParameter("start", "开始时间"),
                        new BusinessRuleFunctionParameter("end", "结束时间")), "DECIMAL",
                        "DATE_DIFF_HOURS({startAt}, {endAt})"),
                new BusinessRuleFunction("COUNT", "子表汇总", "统计非空值", "统计子表字段中已填写的记录数，适合检查明细是否达到最低数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表字段，例如 {lines.amount}")), "LONG",
                        "COUNT({lines.amount})"),
                new BusinessRuleFunction("SUM", "子表汇总", "汇总求和", "汇总子表中的数值，适合计算明细总金额、总数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "SUM({lines.amount})"),
                new BusinessRuleFunction("AVG", "子表汇总", "计算平均值", "计算子表数值的平均值，适合得到平均单价、平均分等指标", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "AVG({lines.amount})"),
                new BusinessRuleFunction("MAX", "子表汇总", "取得最大值", "取得子表数值中的最大值，适合识别最高金额、最大数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "MAX({lines.amount})"),
                new BusinessRuleFunction("MIN", "子表汇总", "取得最小值", "取得子表数值中的最小值，适合识别最低金额、最小数量", List.of(
                        new BusinessRuleFunctionParameter("field", "子表数值字段，例如 {lines.amount}")), "DECIMAL",
                        "MIN({lines.amount})"),
                new BusinessRuleFunction("ROUND", "数值处理", "数值四舍五入", "对数值四舍五入，适合把计算结果收敛到指定的小数位", List.of(
                        new BusinessRuleFunctionParameter("value", "数值或表达式"),
                        new BusinessRuleFunctionParameter("scale", "保留 0–12 位小数")), "DECIMAL",
                        "ROUND({amount}, 2)"),
                new BusinessRuleFunction("FORMAT_DECIMAL", "数值处理", "格式化小数位", "把数值格式化为固定小数位文本，适合展示或拼接时保留末尾零", List.of(
                        new BusinessRuleFunctionParameter("value", "数值或表达式"),
                        new BusinessRuleFunctionParameter("scale", "保留 0–12 位小数")), "STRING",
                        "FORMAT_DECIMAL({amount}, 2)")
        );
    }
    private static Map<String, Object> immutableValues(Map<String, Object> values) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    private static BusinessRuleIssue issue(FormulaEvaluationException exception, String ruleCode) {
        return new BusinessRuleIssue(exception.code(), ruleCode, exception.fieldPath(), exception.getMessage());
    }

    private static String fingerprint(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String fingerprint(ModuleMetadataRelation relation, List<MetadataField> fields,
                                      List<MetadataFieldConfig> configs, List<BusinessRuleSnapshotRule> rules,
                                      List<BusinessRuleField> aggregateFields) {
        String value = relation.getId() + ":" + relation.getVersion() + ":" + fields.stream()
                .map(field -> field.getId() + ":" + field.getVersion()).sorted().toList() + ":" + configs.stream()
                .map(config -> config.getId() + ":" + config.getVersion()).toList() + ":" + rules + ":"
                + (aggregateFields == null ? List.of() : aggregateFields);
        return fingerprint(value);
    }

    private record SnapshotContext(ModuleMetadataRelation main, BusinessRuleGovernanceSnapshot snapshot) {
    }
}
