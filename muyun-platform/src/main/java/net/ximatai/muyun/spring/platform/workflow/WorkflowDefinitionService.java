package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.stereotype.Service;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
@DisablePlatformOperations({PlatformAction.ENABLE, PlatformAction.DISABLE})
public class WorkflowDefinitionService extends AbstractAbilityService<WorkflowDefinition> implements
        SoftDeleteAbility<WorkflowDefinition>,
        EnableAbility<WorkflowDefinition>,
        SortAbility<WorkflowDefinition>,
        QueryAbility<WorkflowDefinition>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.workflow.definition";

    public WorkflowDefinitionService(BaseDao<WorkflowDefinition, String> workflowDefinitionDao) {
        super(MODULE_ALIAS, WorkflowDefinition.class, workflowDefinitionDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, WorkflowDefinition.class, java.util.List.of("id", "applicationAlias", "moduleAlias", "alias", "approvalEnabled", "organizationId", "matchExpression", "defaultDefinition", "actionCode", "definitionStatus", "currentVersionNo", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public String resourceKey() {
        return "workflowDefinition";
    }

    @Override
    public String resourceName() {
        return "工作流定义";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforeInsert(WorkflowDefinition definition) {
        normalizeAndValidate(definition);
    }

    @Override
    public void beforeUpdate(WorkflowDefinition definition) {
        normalizeAndValidate(definition);
        if (definition.getId() != null) {
            var stored = select(definition.getId());
            if (stored != null && stored.getCurrentVersionNo() != null
                    && !java.util.Objects.equals(stored.getActionCode(), definition.getActionCode()))
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.workflow-definition.published-action-code-is-immutable",
                        "已发布流程不能修改模块动作编码"));
        }
    }

    private void normalizeAndValidate(WorkflowDefinition definition) {
        definition.setApplicationAlias(PlatformNameRules.requireApplicationAlias(definition.getApplicationAlias()));
        definition.setModuleAlias(PlatformNameRules.requireModuleAlias(definition.getModuleAlias()));
        definition.setAlias(definition.getAlias() == null || definition.getAlias().isBlank()
                ? null : PlatformNameRules.requireIdentifier(definition.getAlias(), "workflowAlias"));
        if (definition.getApprovalEnabled() == null) {
            definition.setApprovalEnabled(Boolean.FALSE);
        }
        if (definition.getActionCode() != null && definition.getActionCode().isBlank()) {
            definition.setActionCode(null);
        }
        if (definition.getActionCode() != null) {
            definition.setActionCode(PlatformNameRules.requireActionCode(definition.getActionCode(), "actionCode"));
        }
        if (!Boolean.TRUE.equals(definition.getApprovalEnabled()) && definition.getActionCode() == null)
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-definition.action-code-required",
                    "非审批流程必须配置模块动作编码"));
        if (definition.getDefinitionStatus() == null) {
            definition.setDefinitionStatus(WorkflowDefinitionStatus.DRAFT);
        }
        if (!Boolean.TRUE.equals(definition.getApprovalEnabled()) && definition.getActionCode() != null) {
            rejectDuplicate(definition, Criteria.of()
                            .eq("moduleAlias", definition.getModuleAlias())
                            .eq("approvalEnabled", Boolean.FALSE)
                            .eq("actionCode", definition.getActionCode()),
                    "workflow actionCode must be unique within module: " + definition.getActionCode());
        }
        if (definition.getAlias() != null) {
            rejectDuplicate(definition, Criteria.of()
                            .eq("moduleAlias", definition.getModuleAlias())
                            .eq("alias", definition.getAlias()),
                    "workflowAlias must be unique within module: " + definition.getAlias());
        }
    }
}
