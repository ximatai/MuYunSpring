package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class DynamicWorkflowModuleRecordGuard implements WorkflowModuleRecordGuard {
    public static final String SUBMIT_ACTION_CODE = "submit";
    private static final ActionExecutionPolicy SUBMIT_POLICY = new ActionExecutionPolicy(
            SUBMIT_ACTION_CODE,
            PlatformActionLevel.RECORD,
            ActionAccessMode.AUTH_REQUIRED,
            true,
            true,
            ActionDefaultGrantPolicy.NONE,
            null
    );

    private static final ActionExecutionPolicy APPROVAL_SUBMIT_POLICY =
            WorkflowActionPolicyService.runtimePolicy("submitApproval");

    private final DynamicRecordService dynamicRecordService;

    public DynamicWorkflowModuleRecordGuard(DynamicRecordService dynamicRecordService) {
        this.dynamicRecordService = dynamicRecordService;
    }

    @Override
    public void beforeSubmit(WorkflowSubmitRequest request) {
        requireRecordAction(request.moduleAlias(), request.recordId(), currentActionPolicy(request));
    }

    @Override
    public void requireRecordAction(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        String entityAlias = mainEntityAliasOrNull(moduleAlias);
        if (entityAlias == null) {
            return;
        }
        dynamicRecordService.requireRecordActionScope(moduleAlias, entityAlias, policy,
                Set.of(recordId), CurrentUserContext.currentUser());
        if (dynamicRecordService.selectSystem(moduleAlias, entityAlias, recordId) == null) {
            throw new PlatformException("dynamic record not found: " + moduleAlias + "." + recordId);
        }
    }

    private String mainEntityAliasOrNull(String moduleAlias) {
        try {
            return dynamicRecordService.mainEntityAlias(moduleAlias);
        } catch (ModuleDefinitionException ignored) {
            return null;
        }
    }

    private ActionExecutionPolicy currentActionPolicy(WorkflowSubmitRequest request) {
        return ActionExecutionContextHolder.current()
                .filter(context -> request.moduleAlias().equals(context.moduleAlias()))
                .filter(context -> !context.hasRecordContext() || context.recordIds().contains(request.recordId()))
                .map(context -> context.actionPolicy())
                .orElse(request.approvalRequired() ? APPROVAL_SUBMIT_POLICY : SUBMIT_POLICY);
    }
}
