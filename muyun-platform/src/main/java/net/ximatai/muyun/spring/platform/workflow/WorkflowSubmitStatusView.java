package net.ximatai.muyun.spring.platform.workflow;

public record WorkflowSubmitStatusView(
        String moduleAlias,
        String recordId,
        String displayStatus,
        String instanceId,
        WorkflowInstanceStatus instanceStatus,
        WorkflowApprovalStatus approvalStatus,
        WorkflowDefinitionSummaryView definition,
        String errorMessage,
        boolean canSubmit
) {
    public static WorkflowSubmitStatusView current(WorkflowInstance instance) {
        return current(instance, net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
    }
    public static WorkflowSubmitStatusView current(WorkflowInstance instance, String operatorId) {
        return new WorkflowSubmitStatusView(
                instance.getModuleAlias(),
                instance.getRecordId(),
                displayStatus(instance),
                instance.getId(),
                instance.getInstanceStatus(),
                instance.getApprovalStatus(),
                WorkflowDefinitionSummaryView.of(instance),
                null,
                instance.getInstanceStatus() == WorkflowInstanceStatus.REJECTED
                        && instance.getRejectResubmitMode() == WorkflowRejectResubmitMode.RESTART
                        && operatorId != null && operatorId.equals(instance.getStartedBy()));
    }

    public static WorkflowSubmitStatusView unsubmitted(String moduleAlias, String recordId,
                                                       WorkflowDefinitionSelection selection) {
        return new WorkflowSubmitStatusView(moduleAlias, recordId, "UNSUBMITTED", null, null, null,
                WorkflowDefinitionSummaryView.of(selection), null, true);
    }

    public static WorkflowSubmitStatusView noWorkflow(String moduleAlias, String recordId, String errorMessage) {
        return new WorkflowSubmitStatusView(moduleAlias, recordId, "NO_WORKFLOW", null, null, null, null,
                errorMessage, false);
    }

    public static WorkflowSubmitStatusView matchError(String moduleAlias, String recordId, String errorMessage) {
        return new WorkflowSubmitStatusView(moduleAlias, recordId, "MATCH_ERROR", null, null, null, null,
                errorMessage, false);
    }

    private static String displayStatus(WorkflowInstance instance) {
        if (Boolean.TRUE.equals(instance.getApprovalEnabled()) && instance.getApprovalStatus() != null) {
            return instance.getApprovalStatus().name();
        }
        return instance.getInstanceStatus() == null ? "RUNNING" : instance.getInstanceStatus().name();
    }
}
