package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_workflow_instance", comment = "Workflow instance")
@CompositeIndex(columns = {"tenant_id", "module_alias", "record_id", "instance_status"})
@CompositeIndex(columns = {"tenant_id", "definition_id", "started_at"})
public class WorkflowInstance extends StandardEntity {
    @Column(name = "definition_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow definition id")
    @Required
    private String definitionId;

    @Column(name = "workflow_version_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow version id")
    @Required
    private String workflowVersionId;

    @Column(name = "version_no", type = ColumnType.INT, nullable = false, comment = "Workflow version number")
    private Integer versionNo;

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    private String moduleAlias;

    @Column(name = "record_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Record id")
    @Required
    private String recordId;

    @Column(name = "auth_org_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Frozen business organization id for workflow assignment")
    private String authOrgId;

    @Column(name = "approval_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether workflow governs approval status", defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean approvalEnabled = Boolean.FALSE;

    @Column(name = "approval_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Approval status", defaultVal = @Default(varchar = "none"))
    private WorkflowApprovalStatus approvalStatus = WorkflowApprovalStatus.NONE;

    @Column(name = "instance_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Instance status", defaultVal = @Default(varchar = "running"))
    private WorkflowInstanceStatus instanceStatus = WorkflowInstanceStatus.RUNNING;

    @Column(name = "approval_completed_at", type = ColumnType.TIMESTAMP, comment = "Approval completed at")
    private Instant approvalCompletedAt;

    @Column(name = "started_by", type = ColumnType.VARCHAR, length = 64, comment = "Started by")
    private String startedBy;

    @Column(name = "started_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Started at")
    private Instant startedAt;

    @Column(name = "completed_at", type = ColumnType.TIMESTAMP, comment = "Completed at")
    private Instant completedAt;

    @Column(name = "terminated_at", type = ColumnType.TIMESTAMP, comment = "Terminated at")
    private Instant terminatedAt;

    @Column(name = "reject_resubmit_mode", type = ColumnType.VARCHAR, length = 32,
            comment = "Reject resubmit mode")
    private WorkflowRejectResubmitMode rejectResubmitMode;

    @Column(name = "reject_return_node_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Reject return node key")
    private String rejectReturnNodeKey;

    @Column(name = "reject_return_owner_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Reject return owner id")
    private String rejectReturnOwnerId;

    @Column(name = "previous_instance_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Previous workflow instance id")
    private String previousInstanceId;

    @Column(name = "current_node_keys", type = ColumnType.TEXT, comment = "Current active node keys")
    private String currentNodeKeys;

    @Column(name = "last_action_code", type = ColumnType.VARCHAR, length = 64, comment = "Last action code")
    private String lastActionCode;

    @Column(name = "last_action_reason", type = ColumnType.TEXT, comment = "Last action reason")
    private String lastActionReason;

    @Column(name = "last_operator_id", type = ColumnType.VARCHAR, length = 64, comment = "Last operator id")
    private String lastOperatorId;

    @Column(name = "last_operated_at", type = ColumnType.TIMESTAMP, comment = "Last operated at")
    private Instant lastOperatedAt;

    @Column(name = "snapshot_text", type = ColumnType.TEXT, nullable = false, comment = "Workflow snapshot")
    @Required
    private String snapshotText;

    @Column(name = "semantic_json", type = ColumnType.TEXT, comment = "Frozen designer semantic workflow json")
    private String semanticJson;

    @Column(name = "layout_json", type = ColumnType.TEXT, comment = "Frozen designer layout json")
    private String layoutJson;
}
