package net.ximatai.muyun.spring.platform.workflow;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardSortableEntity;

@Getter
@Setter
@Table(name = "platform_workflow_node_definition", comment = "Workflow node definition")
@CompositeIndex(columns = {"workflow_version_id", "node_key"}, unique = true)
public class WorkflowNodeDefinition extends StandardSortableEntity {
    @Column(name = "workflow_version_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow version id")
    private String workflowVersionId;

    @Column(name = "node_key", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Node key")
    private String nodeKey;

    @Column(name = "node_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Node type")
    private WorkflowNodeType nodeType;

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

    @Column(name = "task_definition_id", type = ColumnType.VARCHAR, length = 32, comment = "Task definition id")
    private String taskDefinitionId;

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

    @Column(name = "warning_duration_minutes", type = ColumnType.INT, comment = "Warning duration minutes")
    private Integer warningDurationMinutes;

    @Column(name = "overtime_duration_minutes", type = ColumnType.INT, comment = "Overtime duration minutes")
    private Integer overtimeDurationMinutes;

    @Column(name = "participant_policy_text", type = ColumnType.TEXT, comment = "Participant policy")
    private String participantPolicyText;

    @Column(name = "node_config_text", type = ColumnType.TEXT, comment = "Node config")
    private String nodeConfigText;
}
