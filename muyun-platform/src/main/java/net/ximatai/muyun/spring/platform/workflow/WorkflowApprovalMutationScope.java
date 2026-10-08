package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import java.util.Set;

/** Carries the already verified workflow action into the narrowly bound business summary command. */
final class WorkflowApprovalMutationScope {
    private WorkflowApprovalMutationScope() {}
    static void run(String moduleAlias, String recordId, String actionCode, Runnable mutation) {
        var existing = ActionExecutionContextHolder.current().filter(context -> moduleAlias.equals(context.moduleAlias()))
                .filter(context -> context.recordIds().contains(recordId));
        var policy = existing.map(ActionExecutionContext::actionPolicy)
                .orElseGet(() -> WorkflowActionPolicyService.runtimePolicy(actionCode));
        try (var scope = ActionExecutionContextHolder.use(ActionExecutionContext.ofPolicy(moduleAlias, policy,
                Set.of(recordId), CurrentUserContext.currentUser()))) { mutation.run(); }
    }
}
