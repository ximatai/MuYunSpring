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

@Getter
@Setter
@Table(name = "platform_record_write_back_effect_log", comment = "Record write-back field effect log")
public class RecordWriteBackEffectLog extends StandardEntity {
    @Indexed
    @Column(name = "execution_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Execution log id")
    @Required
    @NormalizeText
    private String executionId;

    @Indexed
    @Column(name = "trace_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Trace id")
    @Required
    @NormalizeText
    private String traceId;

    @Indexed
    @Column(name = "rule_id", type = ColumnType.VARCHAR, length = 32, comment = "Rule id")
    private String ruleId;

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

    @Column(name = "target_module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false,
            comment = "Target module alias")
    @Required
    @NormalizeText
    private String targetModuleAlias;

    @Indexed
    @Column(name = "target_record_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target record id")
    @Required
    @NormalizeText
    private String targetRecordId;

    @Column(name = "target_field", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Target field")
    @Required
    @NormalizeText
    private String targetField;

    @Column(name = "source_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Source type")
    @Required
    private RecordWriteBackFieldSourceType sourceType;

    @Column(name = "source_field", type = ColumnType.VARCHAR, length = 64, comment = "Source field")
    private String sourceField;

    @Column(name = "operation", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Field operation")
    @Required
    private RecordWriteBackFieldOperation operation;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Effect status")
    private RecordWriteBackEffectStatus status = RecordWriteBackEffectStatus.APPLIED;

    @Column(name = "contribution_value", type = ColumnType.TEXT, comment = "Active contribution value")
    private String contributionValue;

    @Column(name = "delta_value", type = ColumnType.TEXT, comment = "Numeric delta value")
    private String deltaValue;

    @Column(name = "before_value", type = ColumnType.TEXT, comment = "Target field value before write-back")
    private String beforeValue;

    @Column(name = "after_value", type = ColumnType.TEXT, comment = "Target field value after write-back")
    private String afterValue;
}
