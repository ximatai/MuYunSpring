package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
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

/** Governs main and direct-child save formulas while preserving and validating other module rules. */
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
        List<BusinessRuleField> childFields = childFields(alias, aggregateFields);
        List<String> writable = new ArrayList<>(editableFields.stream().map(BusinessRuleField::fieldName).toList());
        writable.addAll(childFields.stream().map(BusinessRuleField::fieldName).toList());
        List<String> readableFields = new ArrayList<>(editableFields.stream().map(BusinessRuleField::fieldName).toList());
        readableFields.addAll(referenceFields.stream().map(BusinessRuleReferenceField::path).toList());
        readableFields.addAll(aggregateFields.stream().map(BusinessRuleField::fieldName).toList());
        List<BusinessRuleSnapshotRule> rules = stored.stream()
                .map(rule -> snapshotRule(rule, writable,
                        readableFields, aggregateFields)).toList();
        return new SnapshotContext(main, new BusinessRuleGovernanceSnapshot(alias,
                fingerprint(List.of(fingerprint(main, fields, relevantConfigs(fields, main), rules, aggregateFields), childFields)), editableFields, rules,
                referenceFields, aggregateFields, FormulaFunctionCatalog.functions(), childFields));
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
                FormulaRuleExecutionPlan plan = FormulaRuleExecutionPlan.forAggregateRecord(rules, fields, childRelations(snapshot));
                validateCandidate(alias, snapshot, storedRules(mainRelation(alias)), rules);
                order = plan.orderedRules().stream().map(FormulaRule::id).toList();
            } catch (FormulaEvaluationException exception) {
                errors.add(issue(exception, null));
            } catch (RuntimeException exception) {
                errors.add(BusinessRuleIssue.failure("MODULE_DEFINITION_INVALID", null, null, exception));
            }
        }
        return new BusinessRulePreview(snapshot, fingerprint(rules), order, List.copyOf(errors));
    }

    /** Trials the governed save rules using the same dependency and typed-value boundaries as save. */
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
                    errors.add(BusinessRuleIssue.declared("FORMULA_REFERENCE_INPUT_FORBIDDEN", null, field, TextReference.code(
                        "platform.rules.diagnostic.formula-reference-input-forbidden", "引用路径由服务端按根引用实时读取，不能提交 dotted sample 值"), Map.of()));
                } else {
                    values.put(field, value);
                }
            });
        }
        List<FormulaRule> rules = proposals(command == null ? null : command.rules(), preview.snapshot(), errors);
        Map<String, List<Map<String, Object>>> children = trialChildren(command, preview.snapshot(), rules, errors);
        if (!errors.isEmpty()) return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        try {
            List<FormulaFieldDefinition> fields = new ArrayList<>(trialFields(moduleAlias));
            normalizeTrialInputs(fields, values, children, preview.snapshot(), errors);
            if (!errors.isEmpty()) return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
            FormulaReferenceContext references = trialReferences(moduleAlias, rules);
            fields.addAll(references.fields());
            if (!references.isEmpty() && (referenceTenantId == null || referenceTenantId.isBlank())) {
                errors.add(BusinessRuleIssue.declared("FORMULA_REFERENCE_TENANT_REQUIRED", null, null, TextReference.code(
                        "platform.rules.diagnostic.formula-reference-tenant-required", "引用试算需要请求租户上下文"), Map.of()));
                return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
            }
            FormulaExecutionResult result = FormulaRuleExecutionPlan.forAggregateRecord(rules, fields, childRelations(preview.snapshot()))
                    .execute(new FormulaEngine(), FormulaRuntimeData.typed(values, children, fields,
                            references.paths(), current -> resolveReferences(references, current, referenceTenantId)));
            result.report().errors().forEach(item -> errors.add(new BusinessRuleIssue(item.code(), item.ruleId(),
                    item.fieldPath(), item.message())));
            Map<String, Object> responseValues = new LinkedHashMap<>(values);
            responseValues.putAll(resolveReferences(references, values, referenceTenantId));
            return new BusinessRuleTrialResult(preview, immutableValues(responseValues), result.changedFields(), List.copyOf(errors), children);
        } catch (FormulaEvaluationException exception) {
            errors.add(issue(exception, null));
            return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        } catch (IllegalArgumentException exception) {
            errors.add(BusinessRuleIssue.failure("FORMULA_REFERENCE_PATH_INVALID", null, null, exception));
            return new BusinessRuleTrialResult(preview, immutableValues(values), List.of(), List.copyOf(errors));
        }
    }

    private void normalizeTrialInputs(List<FormulaFieldDefinition> fields, Map<String, Object> values,
            Map<String, List<Map<String, Object>>> children, BusinessRuleGovernanceSnapshot snapshot,
            List<BusinessRuleIssue> errors) {
        Map<String, String> titles = java.util.stream.Stream.concat(snapshot.editableFields().stream(),
                        snapshot.aggregateFields().stream())
                .collect(java.util.stream.Collectors.toMap(BusinessRuleField::fieldName,
                        field -> field.title() == null || field.title().isBlank() ? field.fieldName() : field.title(),
                        (first, ignored) -> first));
        for (FormulaFieldDefinition field : fields) {
            String relation = field.fieldPath().tableKey();
            if (relation == null) normalizeTrialValue(values, field, titles, null, errors);
            else {
                List<Map<String, Object>> rows = children.getOrDefault(relation, List.of());
                for (int index = 0; index < rows.size(); index++)
                    normalizeTrialValue(rows.get(index), field, titles, index + 1, errors);
            }
        }
    }

    private void normalizeTrialValue(Map<String, Object> values, FormulaFieldDefinition field,
            Map<String, String> titles, Integer rowNumber, List<BusinessRuleIssue> errors) {
        String name = field.fieldPath().fieldName();
        Object value = values.get(name);
        // A rule trial can omit required business inputs and calculated outputs; it is not a record save.
        if (value == null) return;
        try {
            values.put(name, field.normalizeValue(value));
        } catch (FormulaEvaluationException exception) {
            String path = field.fieldPath().dataIndex();
            String type = switch (field.type()) {
                case INTEGER, LONG -> "integer";
                case DECIMAL -> "decimal";
                case BOOLEAN -> "boolean";
                case DATE -> "date";
                case TIMESTAMP -> "timestamp";
                default -> "declared";
            };
            String original = switch (type) {
                case "integer" -> "试算输入「{title}」必须是整数";
                case "decimal" -> "试算输入「{title}」必须是数值";
                case "boolean" -> "试算输入「{title}」必须是布尔值";
                case "date" -> "试算输入「{title}」必须是日期";
                case "timestamp" -> "试算输入「{title}」必须是时间";
                default -> "试算输入「{title}」必须是字段声明的类型";
            };
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("title", titles.getOrDefault(path, path));
            if (rowNumber != null) {
                original += "（第{row}行）";
                arguments.put("row", rowNumber);
            }
            errors.add(BusinessRuleIssue.declared(exception.code(), null, path, TextReference.code(
                    "platform.rules.diagnostic.trial-type-" + type + (rowNumber == null ? "" : "-row"), original), arguments));
        }
    }

    /** Samples are explicit input; their copies are computed without reading or writing business records. */
    private Map<String, List<Map<String, Object>>> trialChildren(BusinessRuleTrialCommand command,
            BusinessRuleGovernanceSnapshot snapshot, List<FormulaRule> rules, List<BusinessRuleIssue> errors) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        snapshot.aggregateFields().forEach(field -> {
            String[] path = field.fieldName().split("\\.", 2);
            allowed.computeIfAbsent(path[0], key -> new LinkedHashSet<>()).add(path[1]);
        });
        Map<String, List<Map<String, Object>>> supplied = command == null || command.sampleChildren() == null
                ? Map.of() : command.sampleChildren();
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (supplied.size() > 20) {
            errors.add(BusinessRuleIssue.declared("TRIAL_CHILD_LIMIT", null, null, TextReference.code(
                        "platform.rules.diagnostic.trial-child-limit", "试算最多提供20个子表"), Map.of()));
            return result;
        }
        supplied.forEach((relation, rows) -> {
            if (!allowed.containsKey(relation) || rows == null || rows.size() > 100) {
                errors.add(BusinessRuleIssue.declared("INVALID_TRIAL_CHILD", null, relation, TextReference.code(
                        "platform.rules.diagnostic.invalid-trial-child", "子表必须来自当前汇总字段目录，每个子表最多100行"), Map.of()));
                return;
            }
            List<Map<String, Object>> copies = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (row == null || !allowed.get(relation).containsAll(row.keySet())) {
                    errors.add(BusinessRuleIssue.declared("INVALID_TRIAL_CHILD_FIELD", null, relation, TextReference.code(
                        "platform.rules.diagnostic.invalid-trial-child-field", "明细样例只接受目录中声明的直接子表业务字段"), Map.of()));
                    continue;
                }
                copies.add(new LinkedHashMap<>(row));
            }
            result.put(relation, copies);
        });
        FormulaEngine engine = new FormulaEngine();
        rules.stream().filter(FormulaRule::enabled).forEach(rule ->
                java.util.stream.Stream.concat(engine.valueSideReferencedFields(rule.expression()).stream(),
                                rule.targetField() == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(rule.targetField()))
                        .filter(path -> snapshot.aggregateFields().stream().anyMatch(field -> field.fieldName().equals(path)))
                        .map(path -> path.substring(0, path.indexOf('.'))).distinct()
                        .filter(relation -> !supplied.containsKey(relation))
                        .forEach(relation -> errors.add(BusinessRuleIssue.declared("TRIAL_CHILD_REQUIRED", rule.id(), relation, TextReference.code(
                        "platform.rules.diagnostic.trial-child-required", "请提供子表样例；空明细请显式传入空列表，不能把未提供数据当作零条记录"), Map.of()))));
        return result;
    }

    @Transactional
    public BusinessRuleApplyResult apply(String moduleAlias, BusinessRuleApplyCommand command) {
        if (command == null) throw new IllegalArgumentException("business-rule apply command is required");
        String alias = requireDynamicModule(moduleAlias);
        BusinessRulePreview preview = preview(alias, new BusinessRulePreviewCommand(command.rules()));
        if (!preview.valid()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.business-rule-governance.business-rule-proposal-is-invalid",
                "business-rule proposal is invalid: {value1}"), Map.of("value1", String.valueOf(preview.errors().stream().map(BusinessRuleIssue::code).toList())));
        if (!Objects.equals(command.proposalFingerprint(), preview.proposalFingerprint())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.business-rule-governance.business-rule-proposal-is-stale-preview-again-before-apply",
                    "business-rule proposal is stale; preview again before apply"));
        }
        SnapshotContext currentContext = snapshotContext(alias);
        BusinessRuleGovernanceSnapshot current = currentContext.snapshot();
        if (!Objects.equals(command.baselineFingerprint(), current.baselineFingerprint())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.business-rule-governance.business-rule-baseline-is-stale-reload-and-preview-again",
                    "business-rule baseline is stale; reload and preview again"));
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
            if (!proposalErrors.isEmpty()) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.business-rule-governance.business-rule-proposal-is-invalid",
                    "business-rule proposal is invalid: {value1}"), Map.of("value1", String.valueOf(proposalErrors.stream().map(BusinessRuleIssue::code).toList())));
            validateCandidate(alias, current, existing, proposalRules);
            persistProposal(main, existing, current, command.rules());
            return null;
        });
        refreshCoordinator.scheduleModules(List.of(alias));
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
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.business-rule-governance.business-rule-code-is-read-only-in-this-editor",
                        "business-rule code is read-only in this editor: {value1}"), Map.of("value1", String.valueOf(proposal.code())));
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
        snapshot.childFields().forEach(field -> fields.add(field.fieldName()));
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
                errors.add(BusinessRuleIssue.declared("INVALID_RULE_CODE", code, null, TextReference.code(
                        "platform.rules.diagnostic.invalid-rule-code", "规则编码必须唯一且符合平台编码规则"), Map.of()));
                continue;
            }
            BusinessRuleSnapshotRule existing = snapshot.rules().stream().filter(rule -> code.equals(rule.code())).findFirst().orElse(null);
            if (existingCodes.contains(code) && (existing == null || !existing.editable())) {
                errors.add(BusinessRuleIssue.declared("READ_ONLY_RULE_CODE", code, null, TextReference.code(
                        "platform.rules.diagnostic.read-only-rule-code", "该规则保留为只读，不能被业务规则编辑器覆盖"), Map.of()));
                continue;
            }
            if (proposal.kind() != FormulaRuleKind.CALCULATION && proposal.kind() != FormulaRuleKind.VALIDATION) {
                errors.add(BusinessRuleIssue.declared("UNSUPPORTED_RULE_KIND", code, null, TextReference.code(
                        "platform.rules.diagnostic.unsupported-rule-kind", "首期仅支持计算和校验规则"), Map.of()));
                continue;
            }
            if (proposal.expression() == null || proposal.expression().isBlank() || proposal.expression().length() > 4000) {
                errors.add(BusinessRuleIssue.declared("INVALID_RULE_EXPRESSION", code, proposal.targetField(), TextReference.code(
                        "platform.rules.diagnostic.invalid-rule-expression", "表达式不能为空或超过4000字符"), Map.of()));
                continue;
            }
            if (proposal.kind() == FormulaRuleKind.CALCULATION && !fields.contains(proposal.targetField())) {
                errors.add(BusinessRuleIssue.declared("INVALID_RULE_TARGET", code, proposal.targetField(), TextReference.code(
                        "platform.rules.diagnostic.invalid-rule-target", "计算目标必须来自主表或直接子表的可写字段目录"), Map.of()));
                continue;
            }
            String expression = storedExpression(proposal);
            try {
                if (engine.containsAssignment(proposal.expression())) {
                    errors.add(BusinessRuleIssue.declared("INVALID_RULE_EXPRESSION", code, proposal.targetField(), TextReference.code(
                        "platform.rules.diagnostic.invalid-rule-expression-assignment", "表达式不能为空且不能包含赋值"), Map.of()));
                    continue;
                }
                if (proposal.kind() == FormulaRuleKind.CALCULATION) {
                    if (snapshot.childFields().stream().anyMatch(field -> field.fieldName().equals(proposal.targetField())))
                        engine.validateTargetFieldExpressionScope(proposal.targetField(), expression);
                    else engine.compileFormComputeProgram(expression);
                }
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
                if (!scalarChildInputs.isEmpty() && (proposal.targetField() == null || !snapshot.childFields().stream()
                        .anyMatch(field -> field.fieldName().equals(proposal.targetField())))) {
                    scalarChildInputs.forEach(field -> errors.add(BusinessRuleIssue.declared("SUBTABLE_AGGREGATE_REQUIRED", code, field, TextReference.code(
                        "platform.rules.diagnostic.subtable-aggregate-required", "子表字段只能作为 COUNT、SUM、AVG、MAX 或 MIN 的汇总参数"), Map.of())));
                    continue;
                }
                List<BusinessRuleIssue> aggregateTypeErrors = aggregateTypeErrors(engine, expression, snapshot.aggregateFields(), code);
                if (!aggregateTypeErrors.isEmpty()) {
                    errors.addAll(aggregateTypeErrors);
                    continue;
                }
                if (!invalidInputs.isEmpty()) {
                    invalidInputs.forEach(field -> errors.add(BusinessRuleIssue.declared("INVALID_RULE_INPUT", code, field, TextReference.code(
                            "platform.rules.diagnostic.invalid-rule-input", "业务规则输入必须来自字段目录: {field}"), Map.of("field", field))));
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

    private List<BusinessRuleField> childFields(String moduleAlias, List<BusinessRuleField> aggregateFields) {
        var writable = trialFields(moduleAlias).stream().filter(FormulaFieldDefinition::writable)
                .map(field -> field.fieldPath().dataIndex()).collect(java.util.stream.Collectors.toSet());
        return aggregateFields.stream().filter(field -> writable.contains(field.fieldName()))
                .filter(field -> !field.valueType().equals("JSON")).toList();
    }

    private static Set<String> childRelations(BusinessRuleGovernanceSnapshot snapshot) {
        return snapshot.aggregateFields().stream().map(field -> field.fieldName().split("\\.", 2)[0])
                .collect(java.util.stream.Collectors.toSet());
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
                errors.add(BusinessRuleIssue.declared("AGGREGATE_FIELD_TYPE_UNSUPPORTED", code, field, TextReference.code(
                        "platform.rules.diagnostic.aggregate-field-type-unsupported", "{function} 仅支持数值型子表字段；COUNT 可用于任意可读业务子表字段"), Map.of("function", function)));
            }
        }));
        return List.copyOf(errors);
    }

    private FormulaReferenceContext trialReferences(String moduleAlias, List<FormulaRule> rules) {
        ModuleDefinition definition = moduleDefinitionCompiler.compile(requireDynamicModule(moduleAlias));
        EntityDefinition main = definition.entities().stream().filter(entity -> entity.alias().equals(definition.mainEntityAlias()))
                .findFirst().orElseThrow(() -> new PlatformException("compiled module has no main entity: " + moduleAlias));
        return FormulaReferenceContext.compile(ReferenceTarget.of(definition.moduleAlias(), main.alias()), rules,
                PlatformAbilityRuntime.referenceTargetResolver(), definition.relations().stream()
                        .filter(relation -> main.alias().equals(relation.parentEntityAlias()))
                        .map(EntityRelationDefinition::code).collect(java.util.stream.Collectors.toSet()));
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
                if (rule.getTargetField() == null || !rule.getTargetField().contains("."))
                    editable &= engine.nonAggregateChildFieldReferences(rule.getExpression()).stream()
                            .noneMatch(field -> aggregateFields.stream().map(BusinessRuleField::fieldName).anyMatch(field::equals));
                editable &= aggregateTypeErrors(engine, rule.getExpression(), aggregateFields, rule.getAlias()).isEmpty();
            } catch (FormulaEvaluationException exception) {
                editable = false;
            }
        }
        return new BusinessRuleSnapshotRule(rule.getAlias(), rule.getRuleKind(), rule.getRulePhase(), rule.getTargetField(), expression,
                Boolean.TRUE.equals(rule.getEnabled()), rule.getSeverity(), rule.getMessageTemplate(), Boolean.TRUE.equals(rule.getStopOnError()),
                editable, editable ? null : "仅支持可无损编辑的保存前计算与校验规则");
    }

    private static String calculationRhs(ModuleMetadataFormulaRule rule) {
        if (rule.getTargetField() == null || rule.getExpression() == null) return null;
        if (rule.getTargetField().contains(".")) {
            try {
                var engine = new FormulaEngine();
                if (engine.containsAssignment(rule.getExpression())) return null;
                engine.validateTargetFieldExpressionScope(rule.getTargetField(), rule.getExpression());
                return rule.getExpression().trim();
            } catch (FormulaEvaluationException invalid) { return null; }
        }
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
        return proposal.kind() == FormulaRuleKind.CALCULATION && !proposal.targetField().contains(".")
                ? "{" + proposal.targetField() + "} = (" + proposal.expression().trim() + ")" : proposal.expression().trim();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

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
