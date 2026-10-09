package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Resolves one effective submission policy for previews, domain calls and summary writes. */
@Service
public class WorkflowSubmitActionPolicyResolver {
    private final ObjectProvider<PlatformModuleActionService> actions;

    public WorkflowSubmitActionPolicyResolver(ObjectProvider<PlatformModuleActionService> actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public ActionExecutionPolicy resolve(WorkflowSubmitRequest request) {
        return ActionExecutionContextHolder.current()
                .filter(context -> request.moduleAlias().equals(context.moduleAlias()))
                .filter(context -> !context.hasRecordContext() || context.recordIds().contains(request.recordId()))
                .map(ActionExecutionContext::actionPolicy)
                .orElseGet(() -> request.approvalRequired()
                        ? actions.getObject().requireExecutionPolicy(request.moduleAlias(), "submitApproval")
                        : WorkflowActionPolicyService.runtimePolicy("submit"));
    }
}
