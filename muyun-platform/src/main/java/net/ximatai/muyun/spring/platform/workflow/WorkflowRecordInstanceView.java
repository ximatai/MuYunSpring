package net.ximatai.muyun.spring.platform.workflow;

import java.time.Instant;

/** A bounded record-owned workflow directory without duplicating executable snapshots. */
public record WorkflowRecordInstanceView(String id, Integer versionNo,
        WorkflowInstanceStatus instanceStatus, boolean approvalEnabled, Instant startedAt) {
    static WorkflowRecordInstanceView from(WorkflowInstance instance) {
        return new WorkflowRecordInstanceView(instance.getId(), instance.getVersionNo(),
                instance.getInstanceStatus(), Boolean.TRUE.equals(instance.getApprovalEnabled()), instance.getStartedAt());
    }
}
