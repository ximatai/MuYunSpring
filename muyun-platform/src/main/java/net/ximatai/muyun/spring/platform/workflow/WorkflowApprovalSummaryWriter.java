package net.ximatai.muyun.spring.platform.workflow;

public interface WorkflowApprovalSummaryWriter {
    void writeSubmitted(WorkflowApprovalSummary summary);

    default void clearCurrent(String moduleAlias, String recordId) {
    }

    default void clearCurrent(String tenantId, String moduleAlias, String recordId) {
        clearCurrent(moduleAlias, recordId);
    }
}
