package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.module.ModuleActionBindingType;
import net.ximatai.muyun.spring.platform.module.ModuleActionContribution;
import net.ximatai.muyun.spring.platform.module.ModuleActionContributionRegistrar;
import net.ximatai.muyun.spring.platform.module.ModuleActionSourceType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WorkflowModuleActionContributor {
    private final ModuleActionContributionRegistrar actionRegistrar;
    private final PlatformModuleService moduleService;
    private final PlatformModuleActionService actionService;

    public WorkflowModuleActionContributor(ModuleActionContributionRegistrar actionRegistrar,
                                           PlatformModuleService moduleService, PlatformModuleActionService actionService) {
        this.actionRegistrar = java.util.Objects.requireNonNull(actionRegistrar);
        this.moduleService = java.util.Objects.requireNonNull(moduleService);
        this.actionService = java.util.Objects.requireNonNull(actionService);
    }

    @Transactional
    public void registerPublishedWorkflowAction(WorkflowDefinition definition, WorkflowVersion version) {
        if (definition == null) return;
        // Runtime actions form a global module directory shared by all workflow definitions and tenants.
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.lockMutationPartition(
                "workflow.module.actions", PlatformNameRules.requireModuleAlias(definition.getModuleAlias()));
        actionRegistrar.registerAll(contributions(definition, version));
    }

    public void disableWorkflowActions(WorkflowDefinition definition) {
        if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
            return;
        }
        actionRegistrar.disableBySource(ModuleActionSourceType.WORKFLOW_DEFINITION, definition.getId());
    }

    public ModuleActionContribution contribution(WorkflowDefinition definition, WorkflowVersion version) {
        if (definition == null || Boolean.TRUE.equals(definition.getApprovalEnabled())) {
            return null;
        }
        String actionCode = definition.getActionCode();
        if (actionCode == null || actionCode.isBlank()) {
            throw new PlatformException("non-approval workflow actionCode must not be blank: "
                    + definition.getModuleAlias() + "." + definition.getAlias());
        }
        String actionTitle = definition.getTitle() == null || definition.getTitle().isBlank()
                ? actionCode
                : definition.getTitle();
        return new ModuleActionContribution(
                definition.getModuleAlias(),
                null,
                PlatformNameRules.requireActionCode(actionCode, "actionCode"),
                actionCode,
                actionTitle,
                EntityActionCategory.WORKFLOW,
                EntityActionLevel.RECORD,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                false,
                ActionDefaultGrantPolicy.NONE,
                null,
                null,
                EntityActionExecutorType.SERVICE,
                DynamicWorkflowActionExecutor.EXECUTOR_KEY,
                ModuleActionSourceType.WORKFLOW_DEFINITION,
                definition.getId(),
                version == null ? null : version.getId(),
                ModuleActionBindingType.WORKFLOW_DEFINITION,
                definition.getId(),
                definition.getAlias(),
                true
        );
    }

    public List<ModuleActionContribution> contributions(WorkflowDefinition definition, WorkflowVersion version) {
        if (definition == null) {
            return List.of();
        }
        ArrayList<ModuleActionContribution> contributions = new ArrayList<>(runtimeContributions(definition, version));
        ModuleActionContribution workflowAction = contribution(definition, version);
        if (workflowAction != null) {
            contributions.add(workflowAction);
        }
        return List.copyOf(contributions);
    }

    private List<ModuleActionContribution> runtimeContributions(WorkflowDefinition definition, WorkflowVersion version) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(definition.getModuleAlias());
        String sourceId = runtimeSourceId(moduleAlias);
        var module = moduleService.resolveVisibleModule(moduleAlias);
        var actionCodes = new ArrayList<>(WorkflowActionPolicyService.RUNTIME_RECORD_ACTION_CODES);
        // Static modules own their submit endpoint; dynamic approval needs a durable grantable action.
        if (module != null && module.getModuleKind() == ModuleKind.DYNAMIC) {
            var existing = actionService.findByModuleAliasAndActionCode(moduleAlias, "submitApproval");
            boolean previouslyDeclared = existing != null
                    && existing.getSourceType() == ModuleActionSourceType.WORKFLOW_RUNTIME
                    && sourceId.equals(existing.getSourceId());
            // Publishing a non-approval definition must preserve the module's submission declaration.
            if (Boolean.TRUE.equals(definition.getApprovalEnabled()) || previouslyDeclared) actionCodes.add("submitApproval");
        }
        return actionCodes.stream()
                .map(actionCode -> new ModuleActionContribution(
                        moduleAlias,
                        null,
                        actionCode,
                        actionCode,
                        runtimeTitle(actionCode),
                        EntityActionCategory.WORKFLOW,
                        EntityActionLevel.RECORD,
                        EntityActionAccessMode.AUTH_REQUIRED,
                        true,
                        true,
                        ActionDefaultGrantPolicy.NONE,
                        null,
                        null,
                        EntityActionExecutorType.SERVICE,
                        DynamicWorkflowActionExecutor.EXECUTOR_KEY,
                        ModuleActionSourceType.WORKFLOW_RUNTIME,
                        sourceId,
                        version == null ? null : version.getId(),
                        null,
                        null,
                        null,
                        true
                ))
                .toList();
    }

    private String runtimeSourceId(String moduleAlias) {
        return "runtime:" + UUID.nameUUIDFromBytes(moduleAlias.getBytes(StandardCharsets.UTF_8));
    }

    private String runtimeTitle(String actionCode) {
        return switch (actionCode) {
            case "submitApproval" -> "提交审批";
            case "approve" -> "Workflow Approve";
            case "reject" -> "Workflow Reject";
            case "rollback" -> "Workflow Rollback";
            case "resubmit" -> "Workflow Resubmit";
            case "complete" -> "Workflow Task Complete";
            case "notice" -> "Workflow Notice";
            case "transfer" -> "Workflow Transfer";
            case "addSign" -> "Workflow Add Sign";
            case "invalidate" -> "Workflow Task Invalidate";
            case "cancel" -> "Workflow Task Cancel";
            case "revoke" -> "Workflow Revoke";
            case "terminate" -> "Workflow Terminate";
            case "reset" -> "Workflow Reset";
            default -> actionCode;
        };
    }
}
