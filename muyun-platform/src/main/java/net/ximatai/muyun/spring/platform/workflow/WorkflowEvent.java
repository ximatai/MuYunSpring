package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.content.BusinessContent;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

@Getter
@Setter
@Table(name = "platform_workflow_event", comment = "Workflow event")
@CompositeIndex(columns = {"instance_id", "occurred_at"})
@CompositeIndex(columns = {"tenant_id", "operator_id", "occurred_at"})
public class WorkflowEvent extends StandardEntity {
    @Column(name = "instance_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Workflow instance id")
    private String instanceId;

    @Column(name = "node_instance_id", type = ColumnType.VARCHAR, length = 32, comment = "Workflow node instance id")
    private String nodeInstanceId;

    @Column(name = "task_id", type = ColumnType.VARCHAR, length = 32, comment = "Workflow task id")
    private String taskId;

    @Column(name = "event_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Event type")
    private WorkflowEventType eventType;

    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, comment = "Action code")
    private String actionCode;

    @Column(name = "operator_id", type = ColumnType.VARCHAR, length = 64, comment = "Operator id")
    private String operatorId;

    @Column(name = "message", type = ColumnType.TEXT, comment = "Event message")
    private String message;

    @Column(name = "message_source_text", type = ColumnType.TEXT, comment = "Frozen declared system message source")
    private String messageSourceText;

    @BusinessContent(key = "workflow-opinion")
    @Column(name = "reason", type = ColumnType.TEXT, comment = "Original operator reason")
    private String reason;

    @Column(name = "reason_source_language", type = ColumnType.VARCHAR, length = 255,
            comment = "Explicit source language of the immutable human opinion")
    private String reasonSourceLanguage;

    @Column(name = "payload_text", type = ColumnType.TEXT, comment = "Event payload")
    private String payloadText;

    @Column(name = "occurred_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Occurred at")
    private Instant occurredAt;
}
