package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.spring.common.model.constraint.NormalizeText;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Indexed;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationEventType;

@Getter
@Setter
@Table(name = "platform_record_write_back_execution_log", comment = "Record write-back execution log")
public class RecordWriteBackExecutionLog extends StandardEntity {
    @Indexed
    @Column(name = "trace_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Trace id")
    @Required
    @NormalizeText
    private String traceId;

    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, comment = "Rule id")
    private String ruleId;

    @Column(name = "event_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Event id")
    @Required
    @NormalizeText
    private String eventId;

    @Column(name = "event_type", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Event type")
    @Required
    private DynamicRecordMutationEventType eventType;

    @Column(name = "depth", type = ColumnType.INT, nullable = false, comment = "Trace depth")
    private Integer depth = 0;

    @Column(name = "parent_execution_id", type = ColumnType.VARCHAR, length = 64, comment = "Parent execution id")
    private String parentExecutionId;

    @Column(name = "trigger_module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Trigger module alias")
    @Required
    @NormalizeText
    private String triggerModuleAlias;

    @Column(name = "trigger_record_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Trigger record id")
    @Required
    @NormalizeText
    private String triggerRecordId;

    @Column(name = "target_module_alias", type = ColumnType.VARCHAR, length = 128, comment = "Target module alias")
    private String targetModuleAlias;

    @Column(name = "target_record_id", type = ColumnType.VARCHAR, length = 64, comment = "Target record id")
    private String targetRecordId;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "Execution status")
    private RecordWriteBackExecutionStatus status = RecordWriteBackExecutionStatus.PLANNED;

    @Column(name = "message", type = ColumnType.TEXT, comment = "Execution message")
    private String message;

    @Column(name = "event_snapshot", type = ColumnType.TEXT, comment = "Event snapshot")
    private String eventSnapshot;

    @Column(name = "patch_snapshot", type = ColumnType.TEXT, comment = "Patch snapshot")
    private String patchSnapshot;
}
