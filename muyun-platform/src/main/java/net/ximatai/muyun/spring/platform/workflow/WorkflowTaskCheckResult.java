package net.ximatai.muyun.spring.platform.workflow;

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
@Table(name = "platform_workflow_task_check_result", comment = "Workflow task check result")
@CompositeIndex(columns = {"task_id", "check_key", "check_run_id"}, unique = true)
public class WorkflowTaskCheckResult extends StandardEntity {
    /** Frozen authored display fact for the current evaluation; not persisted or used for checking. */
    private transient String checkTitle;

    @Column(name = "task_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Workflow task id")
    private String taskId;

    @Column(name = "check_key", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Check key")
    private String checkKey;

    @Column(name = "check_run_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Check run id")
    private String checkRunId;

    @Column(name = "check_kind", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Check kind")
    private WorkflowTaskCheckKind checkKind;

    @Column(name = "check_status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Check status", defaultVal = @Default(varchar = "not_checked"))
    private WorkflowTaskCheckStatus checkStatus = WorkflowTaskCheckStatus.NOT_CHECKED;

    @Column(name = "passed", type = ColumnType.BOOLEAN, comment = "Whether check passed",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean passed = Boolean.FALSE;

    @Column(name = "checked_at", type = ColumnType.TIMESTAMP, comment = "Checked at")
    private Instant checkedAt;

    @Column(name = "failure_message", type = ColumnType.VARCHAR, length = 256, comment = "Failure message")
    private String failureMessage;

    @Column(name = "result_payload_text", type = ColumnType.TEXT, comment = "Check result payload")
    private String resultPayloadText;
}
