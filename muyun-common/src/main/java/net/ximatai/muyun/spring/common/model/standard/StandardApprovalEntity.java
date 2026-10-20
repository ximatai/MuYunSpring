package net.ximatai.muyun.spring.common.model.standard;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.ApprovalCapable;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;

import java.time.Instant;

@Getter
@Setter
public abstract class StandardApprovalEntity extends StandardEntity implements ApprovalCapable {
    @Column(name = PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN,
            type = ColumnType.VARCHAR,
            length = PlatformAbilityFields.APPROVAL_INSTANCE_LENGTH,
            comment = "审批实例")
    private String approvalInstanceId;

    @Column(name = PlatformAbilityFields.APPROVAL_STATUS_COLUMN,
            type = ColumnType.VARCHAR,
            length = PlatformAbilityFields.APPROVAL_STATUS_LENGTH,
            comment = "审批状态")
    private String approvalStatus;

    @Column(name = PlatformAbilityFields.APPROVAL_SUBMITTED_BY_COLUMN,
            type = ColumnType.VARCHAR,
            length = PlatformAbilityFields.APPROVAL_SUBMITTED_BY_LENGTH,
            comment = "审批提交人")
    private String approvalSubmittedBy;

    @Column(name = PlatformAbilityFields.APPROVAL_SUBMITTED_AT_COLUMN,
            type = ColumnType.TIMESTAMP,
            comment = "审批提交时间")
    private Instant approvalSubmittedAt;

    @Column(name = PlatformAbilityFields.APPROVAL_COMPLETED_AT_COLUMN,
            type = ColumnType.TIMESTAMP,
            comment = "审批完成时间")
    private Instant approvalCompletedAt;
}
