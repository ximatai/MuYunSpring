package net.ximatai.muyun.spring.platform.workflow;

public interface WorkflowApprovalSummaryWriter {
    void writeSubmitted(WorkflowApprovalSummary summary);

    /** Management recovery may close an orphan; existing records retain the normal write contract. */
    default void writeSubmittedIfPresent(WorkflowApprovalSummary summary) { writeSubmitted(summary); }

    default void clearCurrentIfPresent(String tenantId, String moduleAlias, String recordId) {
        clearCurrent(tenantId, moduleAlias, recordId);
    }

    default void clearCurrent(String moduleAlias, String recordId) {
    }

    default void clearCurrent(String tenantId, String moduleAlias, String recordId) {
        clearCurrent(moduleAlias, recordId);
    }
}
