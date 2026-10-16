package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;

public record WorkflowApprovalSummary(
        String tenantId,
        String moduleAlias,
        String recordId,
        String approvalInstanceId,
        WorkflowApprovalStatus approvalStatus,
        String approvalSubmittedBy,
        Instant approvalSubmittedAt,
        Instant approvalCompletedAt
) {
    public WorkflowApprovalSummary(String moduleAlias, String recordId, String approvalInstanceId,
                                   WorkflowApprovalStatus approvalStatus, String approvalSubmittedBy,
                                   Instant approvalSubmittedAt, Instant approvalCompletedAt) {
        this(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null),
                moduleAlias, recordId, approvalInstanceId, approvalStatus, approvalSubmittedBy,
                approvalSubmittedAt, approvalCompletedAt);
    }
}
