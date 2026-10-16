package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.capability.ApprovalCapable;
import java.time.Instant;

/** Platform-owned approval summary; independent of any workflow engine. */
public record ApprovalState(String instanceId, String status, String submittedBy,
                            Instant submittedAt, Instant completedAt) {
    public static ApprovalState empty() { return new ApprovalState(null, null, null, null, null); }

    static ApprovalState read(ApprovalCapable record) {
        return new ApprovalState(record.getApprovalInstanceId(), record.getApprovalStatus(),
                record.getApprovalSubmittedBy(), record.getApprovalSubmittedAt(), record.getApprovalCompletedAt());
    }

    void apply(ApprovalCapable record) {
        record.setApprovalInstanceId(instanceId);
        record.setApprovalStatus(status);
        record.setApprovalSubmittedBy(submittedBy);
        record.setApprovalSubmittedAt(submittedAt);
        record.setApprovalCompletedAt(completedAt);
    }
}
