package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckBlock;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckType;
import org.springframework.stereotype.Service;

@Service
public class WorkflowBusinessTaskResolver {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final WorkflowTaskDefinitionDao definitions;
    private final WorkflowTaskCheckDao checks;
    private final WorkflowTaskGuideDao guides;
    private final WorkflowConditionService conditions;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public WorkflowBusinessTaskResolver(WorkflowTaskDefinitionDao definitions, WorkflowTaskCheckDao checks,
                                        WorkflowTaskGuideDao guides, WorkflowConditionService conditions) {
        this.definitions = definitions; this.checks = checks; this.guides = guides; this.conditions = conditions;
    }

    public WorkflowBusinessTaskSpec resolve(WorkflowNodeInstance node) {
        return resolve(node.getTaskDefinitionId(), node.getNodeSnapshotText());
    }

    public void freeze(WorkflowNodeDefinition node, String moduleAlias) {
        if (node.getNodeType() != WorkflowNodeType.TASK) return;
        var specification = resolve(node.getTaskDefinitionId(), node.getNodeConfigText());
        if (specification.definition() == null) throw new PlatformException("业务任务完成策略不能为空");
        validateSpecification(specification, moduleAlias);
        if (node.getTaskDefinitionId() != null && !node.getTaskDefinitionId().equals(specification.definition().getId()))
            throw new PlatformException("业务任务完成项引用与冻结配置不一致");
        if (!Boolean.TRUE.equals(specification.definition().getManualConfirm()) && specification.checks().stream().noneMatch(check -> check.getCheckKind() != WorkflowTaskCheckKind.MANUAL_CONFIRM))
            throw new PlatformException("自动完成任务必须配置检查项");
        try { node.setNodeConfigText(mapper.writeValueAsString(java.util.Map.of("task", specification))); }
        catch (Exception failure) { throw new PlatformException("cannot freeze business task", failure); }
    }

    private void validateSpecification(WorkflowBusinessTaskSpec specification, String moduleAlias) {
        try {
            requireAuthoredOwnership(specification.definition());
            PlatformNameRules.requireModuleAlias(moduleAlias);
            if (!moduleAlias.equals(specification.definition().getModuleAlias()))
                throw new PlatformException("业务完成项必须属于当前流程业务模块");
            var checkKeys = new java.util.HashSet<String>();
            for (var check : specification.checks()) {
                if (check == null) throw new PlatformException("任务检查项不能为空");
                requireAuthoredOwnership(check);
                PlatformNameRules.requireCode(check.getCheckKey(), "checkKey");
                if (!checkKeys.add(check.getCheckKey())) throw new PlatformException("任务检查项编码重复: " + check.getCheckKey());
                requireChildOwner(check.getTaskDefinitionId(), specification.definition().getId());
                validateCheck(check);
            }
            var guideKeys = new java.util.HashSet<String>();
            for (var guide : specification.guides()) {
                if (guide == null || guide.getGuideKind() == null) throw new PlatformException("任务指引类型不能为空");
                requireAuthoredOwnership(guide);
                PlatformNameRules.requireCode(guide.getGuideKey(), "guideKey");
                if (!guideKeys.add(guide.getGuideKey())) throw new PlatformException("任务指引编码重复: " + guide.getGuideKey());
                requireChildOwner(guide.getTaskDefinitionId(), specification.definition().getId());
                if (guide.getTargetModuleAlias() != null) PlatformNameRules.requireModuleAlias(guide.getTargetModuleAlias());
                if ((guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM || guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION)
                        && guide.getTargetModuleAlias() != null && !moduleAlias.equals(guide.getTargetModuleAlias()))
                    throw new PlatformException("写入指引必须绑定当前任务业务模块: " + guide.getGuideKey());
                if (hasText(guide.getGuideConfigText())) {
                    var config = object(guide.getGuideConfigText(), "任务指引配置");
                    if (config.has("payload") && !config.path("payload").isObject()) throw new PlatformException("指引业务动作参数必须为 JSON 对象");
                }
                if (guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION) {
                    PlatformNameRules.requireActionCode(guide.getTargetActionCode(), "targetActionCode");
                } else if (hasText(guide.getTargetActionCode()) && (guide.getGuideKind() != WorkflowTaskGuideKind.OPEN_FORM
                        || !"update".equals(guide.getTargetActionCode()))) {
                    throw new PlatformException("任务指引类型与业务动作配置不匹配: " + guide.getGuideKey());
                }
                if (guide.getGuideKind() == WorkflowTaskGuideKind.OPEN_FORM
                        || guide.getGuideKind() == WorkflowTaskGuideKind.EXECUTE_ACTION && "update".equals(guide.getTargetActionCode()))
                    WorkflowTaskFormPolicy.editableFields(guide);
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new PlatformException("业务任务配置无效: " + invalid.getMessage(), invalid);
        }
    }

    private void validateCheck(WorkflowTaskCheck check) {
        if (check.getCheckKind() == null) throw new PlatformException("任务检查类型不能为空");
        if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA || check.getCheckKind() == WorkflowTaskCheckKind.MANUAL_CONFIRM) {
            if (hasText(check.getCheckConfigText()) && !object(check.getCheckConfigText(), "任务检查配置").isEmpty())
                throw new PlatformException("任务检查类型与配置不匹配: " + check.getCheckKey());
            if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA) {
                if (!hasText(check.getExpression())) throw new PlatformException("任务检查公式不能为空");
                conditions.validate(check.getExpression());
            } else if (hasText(check.getExpression())) throw new PlatformException("人工确认检查不能包含公式");
            return;
        }
        if (hasText(check.getExpression())) throw new PlatformException("查询检查不能包含公式");
        var raw = object(check.getCheckConfigText(), "任务检查配置");
        if (raw.has("expectedCount") && (!raw.path("expectedCount").isIntegralNumber()
                || !raw.path("expectedCount").canConvertToInt() || raw.path("expectedCount").asInt() <= 0))
            throw new PlatformException("任务检查数量必须为正整数");
        PlatformTaskCheckBlock block;
        try { block = mapper.treeToValue(raw, PlatformTaskCheckBlock.class); }
        catch (Exception invalid) { throw new PlatformException("任务检查配置无效: " + check.getCheckKey(), invalid); }
        var expectedType = switch (check.getCheckKind()) {
            case QUERY_EXISTS -> PlatformTaskCheckType.QUERY_TEMPLATE;
            case RELATED_QUERY_EXISTS -> PlatformTaskCheckType.ASSOCIATION_VIEW;
            case GENERATED_QUERY_EXISTS -> PlatformTaskCheckType.GENERATED_RELATION;
            default -> throw new PlatformException("不支持的任务检查类型");
        };
        if (block.checkType() != expectedType) throw new PlatformException("任务检查类型与配置不匹配: " + check.getCheckKey());
        switch (expectedType) {
            case QUERY_TEMPLATE -> {
                if (!hasText(block.queryTemplateId())) throw new PlatformException("查询检查必须指定查询模板");
                if (hasText(block.externalRecordIdKey())) PlatformNameRules.requireCode(block.externalRecordIdKey(), "externalRecordIdKey");
                if (block.associationViewCode() != null || block.targetModuleAlias() != null || block.generationRuleId() != null)
                    throw new PlatformException("查询模板检查包含其他类型配置");
            }
            case ASSOCIATION_VIEW -> {
                PlatformNameRules.requireCode(block.associationViewCode(), "associationViewCode");
                if (block.queryTemplateId() != null || block.externalRecordIdKey() != null || block.targetModuleAlias() != null || block.generationRuleId() != null)
                    throw new PlatformException("关联检查包含其他类型配置");
            }
            case GENERATED_RELATION -> {
                PlatformNameRules.requireModuleAlias(block.targetModuleAlias());
                if (block.queryTemplateId() != null || block.externalRecordIdKey() != null || block.associationViewCode() != null)
                    throw new PlatformException("生成关系检查包含其他类型配置");
            }
            default -> throw new PlatformException("不支持的任务检查配置");
        }
    }

    private com.fasterxml.jackson.databind.JsonNode object(String text, String name) {
        try {
            var value = mapper.readTree(text == null ? "" : text);
            if (value == null || !value.isObject()) throw new PlatformException(name + "必须为 JSON 对象");
            return value;
        } catch (PlatformException invalid) { throw invalid; }
        catch (Exception invalid) { throw new PlatformException(name + "不是有效 JSON", invalid); }
    }

    private void requireChildOwner(String ownerId, String definitionId) {
        if (ownerId != null && !ownerId.equals(definitionId)) throw new PlatformException("检查或指引不属于当前业务完成项");
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    /** Inline authoring may omit ownership; explicit ownership cannot import another tenant's configuration. */
    private void requireAuthoredOwnership(EntityContract entity) {
        if (Boolean.TRUE.equals(entity.getDeleted())) throw new PlatformException("业务任务配置已删除");
        if (entity.getTenantId() != null && !TenantContext.tenantFilterBypassed()
                && !java.util.Objects.equals(entity.getTenantId(), TenantContext.currentTenantId().orElse(null)))
            throw new PlatformException("业务任务配置不属于当前租户");
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
        if (id == null || id.isBlank()) throw new PlatformException("业务任务未配置完成策略");
        var definition = WorkflowTenantScope.visible(definitions.findById(id));
        if (definition == null || Boolean.TRUE.equals(definition.getDeleted()) || !Boolean.TRUE.equals(definition.getEnabled()))
            throw new PlatformException("业务完成项不存在或已停用: " + id);
        return new WorkflowBusinessTaskSpec(definition,
                checks.query(WorkflowTenantScope.criteria().eq("taskDefinitionId", id).eq("enabled", true), ALL, Sort.asc("sortOrder"))
                        .stream().filter(item -> !Boolean.TRUE.equals(item.getDeleted())).toList(),
                guides.query(WorkflowTenantScope.criteria().eq("taskDefinitionId", id).eq("enabled", true), ALL, Sort.asc("sortOrder"))
                        .stream().filter(item -> !Boolean.TRUE.equals(item.getDeleted())).toList());
    }
}
