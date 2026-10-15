package net.ximatai.muyun.spring.platform.workflow;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckType;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

@Service
public class WorkflowBusinessTaskResolver {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final WorkflowTaskDefinitionDao definitions;
    private final WorkflowTaskCheckDao checks;
    private final WorkflowTaskGuideDao guides;
    private final WorkflowConditionService conditions;
    private final WorkflowBusinessTaskReferenceValidator references;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public WorkflowBusinessTaskResolver(WorkflowTaskDefinitionDao definitions, WorkflowTaskCheckDao checks,
                                        WorkflowTaskGuideDao guides, WorkflowConditionService conditions,
                                        WorkflowBusinessTaskReferenceValidator references) {
        this.definitions = definitions; this.checks = checks; this.guides = guides; this.conditions = conditions;
        this.references = java.util.Objects.requireNonNull(references, "references");
    }

    public WorkflowBusinessTaskSpec resolve(WorkflowNodeInstance node) {
        return resolve(node.getTaskDefinitionId(), node.getNodeSnapshotText());
    }

    public void freeze(WorkflowNodeDefinition node, String moduleAlias) {
        if (node.getNodeType() != WorkflowNodeType.TASK) return;
        var specification = resolve(node.getTaskDefinitionId(), node.getNodeConfigText());
        if (specification.definition() == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.configure-a-completion-policy-for-the-business-task",
                "业务任务完成策略不能为空"));
        validateSpecification(specification, moduleAlias);
        if (node.getTaskDefinitionId() != null && !node.getTaskDefinitionId().equals(specification.definition().getId()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.the-task-completion-reference-differs-from-its-frozen-configuration",
                    "业务任务完成项引用与冻结配置不一致"));
        if (!Boolean.TRUE.equals(specification.definition().getManualConfirm()) && specification.checks().stream().noneMatch(check -> check.getCheckKind() != WorkflowTaskCheckKind.MANUAL_CONFIRM))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.automatic-task-completion-requires-check-items",
                    "自动完成任务必须配置检查项"));
        try { node.setNodeConfigText(mapper.writeValueAsString(java.util.Map.of("task", specification))); }
        catch (Exception failure) { throw new PlatformException("cannot freeze business task", failure); }
    }

    private void validateSpecification(WorkflowBusinessTaskSpec specification, String moduleAlias) {
        try {
            requireAuthoredOwnership(specification.definition());
            PlatformNameRules.requireModuleAlias(moduleAlias);
            if (!moduleAlias.equals(specification.definition().getModuleAlias()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.the-completion-item-must-belong-to-the-workflow-s-business-module",
                        "业务完成项必须属于当前流程业务模块"));
            var checkKeys = new java.util.HashSet<String>();
            for (var check : specification.checks()) {
                if (check == null) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.provide-a-task-check-item",
                        "任务检查项不能为空"));
                requireAuthoredOwnership(check);
                PlatformNameRules.requireCode(check.getCheckKey(), "checkKey");
                if (!checkKeys.add(check.getCheckKey())) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.duplicate-task-check-key",
                        "任务检查项编码重复: {value1}"), Map.of("value1", String.valueOf(check.getCheckKey())));
                requireChildOwner(check.getTaskDefinitionId(), specification.definition().getId());
                validateCheck(check);
                references.check(moduleAlias, check);
            }
            var guideKeys = new java.util.HashSet<String>();
            for (var guide : specification.guides()) {
                if (guide == null || guide.getGuideKind() == null) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.select-a-task-guide-type",
                        "任务指引类型不能为空"));
                requireAuthoredOwnership(guide);
                PlatformNameRules.requireCode(guide.getGuideKey(), "guideKey");
                if (!guideKeys.add(guide.getGuideKey())) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.duplicate-task-guide-key",
                        "任务指引编码重复: {value1}"), Map.of("value1", String.valueOf(guide.getGuideKey())));
                requireChildOwner(guide.getTaskDefinitionId(), specification.definition().getId());
                if (guide.getTargetModuleAlias() != null) PlatformNameRules.requireModuleAlias(guide.getTargetModuleAlias());
                if ((guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM || guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION)
                        && guide.getTargetModuleAlias() != null && !moduleAlias.equals(guide.getTargetModuleAlias()))
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.the-write-guide-must-target-the-current-task-s-business-module",
                            "写入指引必须绑定当前任务业务模块: {value1}"), Map.of("value1", String.valueOf(guide.getGuideKey())));
                if (hasText(guide.getGuideConfigText())) {
                    var config = object(guide.getGuideConfigText(), "任务指引配置", "guide");
                    if (config.has("payload") && !config.path("payload").isObject()) throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.guide-action-parameters-must-be-a-json-object",
                            "指引业务动作参数必须为 JSON 对象"));
                }
                if (guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION) {
                    PlatformNameRules.requireActionCode(guide.getTargetActionCode(), "targetActionCode");
                } else if (hasText(guide.getTargetActionCode()) && (guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM
                        || !"update".equals(guide.getTargetActionCode()))) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.the-guide-type-does-not-match-its-action-configuration",
                            "任务指引类型与业务动作配置不匹配: {value1}"), Map.of("value1", String.valueOf(guide.getGuideKey())));
                }
                if (guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM
                        || guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION && "update".equals(guide.getTargetActionCode()))
                    WorkflowTaskFormPolicy.editableFields(guide);
                references.guide(moduleAlias, guide);
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.invalid-business-task-configuration",
                    "业务任务配置无效: {value1}"), Map.of("value1", String.valueOf(invalid.getMessage())), invalid);
        }
    }

    private void validateCheck(WorkflowTaskCheck check) {
        if (check.getCheckKind() == null) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.select-a-task-check-type",
                "任务检查类型不能为空"));
        if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA || check.getCheckKind() == WorkflowTaskCheckKind.MANUAL_CONFIRM) {
            if (hasText(check.getCheckConfigText()) && !object(check.getCheckConfigText(), "任务检查配置", "check").isEmpty())
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.the-task-check-type-does-not-match-its-configuration",
                        "任务检查类型与配置不匹配: {value1}"), Map.of("value1", String.valueOf(check.getCheckKey())));
            if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA) {
                if (!hasText(check.getExpression())) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.enter-a-task-check-formula",
                        "任务检查公式不能为空"));
                conditions.validate(check.getExpression());
            } else if (hasText(check.getExpression())) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.manual-confirmation-checks-cannot-contain-formulas",
                    "人工确认检查不能包含公式"));
            return;
        }
        if (hasText(check.getExpression())) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.query-checks-cannot-contain-formulas",
                "查询检查不能包含公式"));
        var raw = object(check.getCheckConfigText(), "任务检查配置", "check");
        if (raw.has("expectedCount") && (!raw.path("expectedCount").isIntegralNumber()
                || !raw.path("expectedCount").canConvertToInt() || raw.path("expectedCount").asInt() <= 0))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.the-task-check-count-must-be-a-positive-integer",
                    "任务检查数量必须为正整数"));
        PlatformTaskCheckBlock block;
        try { block = mapper.treeToValue(raw, PlatformTaskCheckBlock.class); }
        catch (Exception invalid) { throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.invalid-task-check-configuration",
                "任务检查配置无效: {value1}"), Map.of("value1", String.valueOf(check.getCheckKey())), invalid); }
        var expectedType = switch (check.getCheckKind()) {
            case QUERY_EXISTS -> PlatformTaskCheckType.QUERY_TEMPLATE;
            case RELATED_QUERY_EXISTS -> PlatformTaskCheckType.ASSOCIATION_VIEW;
            case GENERATED_QUERY_EXISTS -> PlatformTaskCheckType.GENERATED_RELATION;
            default -> throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.unsupported-task-check-type",
                    "不支持的任务检查类型"));
        };
        if (block.checkType() != expectedType) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.the-task-check-type-does-not-match-its-configuration",
                "任务检查类型与配置不匹配: {value1}"), Map.of("value1", String.valueOf(check.getCheckKey())));
        switch (expectedType) {
            case QUERY_TEMPLATE -> {
                if (!hasText(block.queryTemplateId())) throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-business-task-resolver.select-a-query-template-for-the-query-check",
                        "查询检查必须指定查询模板"));
                if (hasText(block.externalRecordIdKey())) PlatformNameRules.requireCode(block.externalRecordIdKey(), "externalRecordIdKey");
                if (block.associationViewCode() != null || block.targetModuleAlias() != null || block.generationRuleId() != null)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.the-query-template-check-contains-settings-for-another-check-type",
                            "查询模板检查包含其他类型配置"));
            }
            case ASSOCIATION_VIEW -> {
                PlatformNameRules.requireCode(block.associationViewCode(), "associationViewCode");
                if (block.queryTemplateId() != null || block.externalRecordIdKey() != null || block.targetModuleAlias() != null || block.generationRuleId() != null)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.the-association-check-contains-settings-for-another-check-type",
                            "关联检查包含其他类型配置"));
            }
            case GENERATED_RELATION -> {
                PlatformNameRules.requireModuleAlias(block.targetModuleAlias());
                if (block.queryTemplateId() != null || block.externalRecordIdKey() != null || block.associationViewCode() != null)
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.workflow-business-task-resolver.the-generation-relationship-check-contains-settings-for-another-check-type",
                            "生成关系检查包含其他类型配置"));
            }
            default -> throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.unsupported-task-check-configuration",
                    "不支持的任务检查配置"));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode object(String text, String name, String sourceId) {
        try {
            var value = mapper.readTree(text == null ? "" : text);
            if (value == null || !value.isObject()) throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow." + sourceId + ".object-required",
                    name + "必须为 JSON 对象"));
            return value;
        } catch (PlatformException invalid) { throw invalid; }
        catch (Exception invalid) { throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow." + sourceId + ".invalid-json",
                name + "不是有效 JSON"), Map.of(), invalid); }
    }

    private void requireChildOwner(String ownerId, String definitionId) {
        if (ownerId != null && !ownerId.equals(definitionId)) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.the-check-or-guide-does-not-belong-to-the-current-completion-item",
                "检查或指引不属于当前业务完成项"));
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    /** Inline authoring may omit ownership; explicit ownership cannot import another tenant's configuration. */
    private void requireAuthoredOwnership(EntityContract entity) {
        if (Boolean.TRUE.equals(entity.getDeleted())) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.the-business-task-configuration-has-been-deleted",
                "业务任务配置已删除"));
        if (entity.getTenantId() != null && !TenantContext.tenantFilterBypassed()
                && !java.util.Objects.equals(entity.getTenantId(), TenantContext.currentTenantId().orElse(null)))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.the-business-task-configuration-does-not-belong-to-the-current-tenant",
                    "业务任务配置不属于当前租户"));
    }

    private WorkflowBusinessTaskSpec resolve(String id, String text) {
        if (text != null && !text.isBlank()) {
            try {
                var root = mapper.readTree(text);
                if (root.has("task")) {
                    return mapper.treeToValue(root.path("task"), WorkflowBusinessTaskSpec.class);
                }
            } catch (Exception failure) { throw new PlatformException("invalid business task specification", failure); }
        }
        if (id == null || id.isBlank()) throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.workflow-business-task-resolver.no-completion-policy-is-configured-for-the-business-task",
                "业务任务未配置完成策略"));
        var definition = WorkflowTenantScope.visible(definitions.findById(id));
        if (definition == null || Boolean.TRUE.equals(definition.getDeleted()) || !Boolean.TRUE.equals(definition.getEnabled()))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-business-task-resolver.completion-item-not-found-or-disabled",
                    "业务完成项不存在或已停用: {value1}"), Map.of("value1", String.valueOf(id)));
        return new WorkflowBusinessTaskSpec(definition,
                checks.query(WorkflowTenantScope.criteria().eq("taskDefinitionId", id).eq("enabled", true), ALL, Sort.asc("sortOrder"))
                        .stream().filter(item -> !Boolean.TRUE.equals(item.getDeleted())).toList(),
                guides.query(WorkflowTenantScope.criteria().eq("taskDefinitionId", id).eq("enabled", true), ALL, Sort.asc("sortOrder"))
                        .stream().filter(item -> !Boolean.TRUE.equals(item.getDeleted())).toList());
    }
}
