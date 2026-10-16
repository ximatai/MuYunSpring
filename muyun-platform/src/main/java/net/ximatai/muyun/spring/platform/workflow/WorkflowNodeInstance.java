package net.ximatai.muyun.spring.platform.workflow;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_workflow_node_instance", comment = "Workflow node instance")
@CompositeIndex(columns = {"instance_id", "node_key"})
@CompositeIndex(columns = {"instance_id", "node_status"})
public class WorkflowNodeInstance extends StandardEntity {
    @Column(name = "instance_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow instance id")
    private String instanceId;

    @Column(name = "node_key", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Node key")
    private String nodeKey;

    @Column(name = "node_title", type = ColumnType.VARCHAR, length = 128, comment = "Node title")
    private String nodeTitle;

    @Column(name = "node_run_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Node run id")
    private String nodeRunId;

    @Column(name = "node_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Node type")
    private WorkflowNodeType nodeType;

    @Column(name = "node_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Node status", defaultVal = @Default(varchar = "waiting"))
    private WorkflowNodeStatus nodeStatus = WorkflowNodeStatus.WAITING;

    @Column(name = "approval_mode", type = ColumnType.VARCHAR, length = 32, comment = "Approval mode")
    private WorkflowApprovalMode approvalMode;

    @Column(name = "approval_ratio", type = ColumnType.INT, comment = "Approval ratio percent")
    private Integer approvalRatio;

    @Column(name = "milestone_type", type = ColumnType.VARCHAR, length = 32, comment = "Milestone type")
    private WorkflowMilestoneType milestoneType;

    @Column(name = "converge_mode", type = ColumnType.VARCHAR, length = 32, comment = "Converge mode")
    private WorkflowConvergeMode convergeMode;

    @Column(name = "converge_ratio", type = ColumnType.INT, comment = "Converge ratio percent")
    private Integer convergeRatio;

    @Column(name = "route_mode", type = ColumnType.VARCHAR, length = 32, comment = "Branch route mode")
    private WorkflowRouteMode routeMode;

    @Column(name = "converge_node_key", type = ColumnType.VARCHAR, length = 64, comment = "Paired converge node")
    private String convergeNodeKey;

    @Column(name = "selector_node_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Manual route selector source node key")
    private String selectorNodeKey;

    @Column(name = "require_manual_selection_reason", type = ColumnType.BOOLEAN,
            comment = "Require manual route selection reason")
    private Boolean requireManualSelectionReason;

    @Column(name = "route_id", type = ColumnType.VARCHAR, length = 32, comment = "Current route id")
    private String routeId;

    @Column(name = "enter_route_id", type = ColumnType.VARCHAR, length = 32, comment = "Enter route id")
    private String enterRouteId;

    @Column(name = "branch_run_id", type = ColumnType.VARCHAR, length = 64, comment = "Branch run id")
    private String branchRunId;

    @Column(name = "converge_run_id", type = ColumnType.VARCHAR, length = 64, comment = "Converge run id")
    private String convergeRunId;

    @Column(name = "required_route_count", type = ColumnType.INT, comment = "Required route count")
    private Integer requiredRouteCount;

    @Column(name = "arrived_route_count", type = ColumnType.INT, comment = "Arrived route count")
    private Integer arrivedRouteCount;

    @Column(name = "completed_route_count", type = ColumnType.INT, comment = "Completed route count")
    private Integer completedRouteCount;

    @Column(name = "required_task_count", type = ColumnType.INT, comment = "Required task count")
    private Integer requiredTaskCount;

    @Column(name = "completed_task_count", type = ColumnType.INT, comment = "Completed task count")
    private Integer completedTaskCount;

    @Column(name = "approved_task_count", type = ColumnType.INT, comment = "Approved task count")
    private Integer approvedTaskCount;

    @Column(name = "rejected_task_count", type = ColumnType.INT, comment = "Rejected task count")
    private Integer rejectedTaskCount;

    @Column(name = "rollback_target_node_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Rollback target node key")
    private String rollbackTargetNodeKey;

    @Column(name = "task_definition_id", type = ColumnType.VARCHAR, length = 32, comment = "Task definition id")
    private String taskDefinitionId;

    @Column(name = "participant_policy_text", type = ColumnType.TEXT, comment = "Participant policy")
    private String participantPolicyText;

    @Column(name = "allow_reject", type = ColumnType.BOOLEAN, comment = "Allow reject")
    private Boolean allowReject;

    @Column(name = "auto_approve_same_user", type = ColumnType.BOOLEAN, comment = "Opt-in same actor automatic approval")
    private Boolean autoApproveSameUser;


    @Column(name = "require_reject_reason", type = ColumnType.BOOLEAN, comment = "Require reject reason")
    private Boolean requireRejectReason;

    @Column(name = "allow_reject_return_to_me", type = ColumnType.BOOLEAN,
            comment = "Allow reject return to me")
    private Boolean allowRejectReturnToMe;

    @Column(name = "allow_rollback", type = ColumnType.BOOLEAN, comment = "Allow rollback")
    private Boolean allowRollback;

    @Column(name = "require_rollback_reason", type = ColumnType.BOOLEAN, comment = "Require rollback reason")
    private Boolean requireRollbackReason;

    @Column(name = "allow_terminate", type = ColumnType.BOOLEAN, comment = "Allow terminate")
    private Boolean allowTerminate;

    @Column(name = "require_terminate_reason", type = ColumnType.BOOLEAN, comment = "Require terminate reason")
    private Boolean requireTerminateReason;

    @Column(name = "allow_add_sign", type = ColumnType.BOOLEAN, comment = "Allow add sign")
    private Boolean allowAddSign;

    @Column(name = "added_by_add_sign", type = ColumnType.BOOLEAN, comment = "Added by runtime add sign")
    private Boolean addedByAddSign;

    @Column(name = "add_sign_source_node_key", type = ColumnType.VARCHAR, length = 64,
            comment = "Add sign source node key")
    private String addSignSourceNodeKey;

    @Column(name = "add_sign_operator_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Add sign operator id")
    private String addSignOperatorId;

    @Column(name = "add_sign_at", type = ColumnType.TIMESTAMP, comment = "Add sign at")
    private Instant addSignAt;

    @Column(name = "warning_duration_minutes", type = ColumnType.INT, comment = "Warning duration minutes")
    private Integer warningDurationMinutes;

    @Column(name = "overtime_duration_minutes", type = ColumnType.INT, comment = "Overtime duration minutes")
    private Integer overtimeDurationMinutes;

    @Column(name = "overtime_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Overtime status", defaultVal = @Default(varchar = "normal"))
    private WorkflowOvertimeStatus overtimeStatus = WorkflowOvertimeStatus.NORMAL;

    @Column(name = "warned_at", type = ColumnType.TIMESTAMP, comment = "Warned at")
    private Instant warnedAt;

    @Column(name = "overdue_at", type = ColumnType.TIMESTAMP, comment = "Overdue at")
    private Instant overdueAt;

    @Column(name = "activated_at", type = ColumnType.TIMESTAMP, comment = "Activated at")
    private Instant activatedAt;

    @Column(name = "completed_at", type = ColumnType.TIMESTAMP, comment = "Completed at")
    private Instant completedAt;

    @Column(name = "node_snapshot_text", type = ColumnType.TEXT, comment = "Node snapshot")
    private String nodeSnapshotText;
}
