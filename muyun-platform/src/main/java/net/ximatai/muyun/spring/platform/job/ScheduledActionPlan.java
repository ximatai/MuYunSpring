package net.ximatai.muyun.spring.platform.job;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "platform_scheduled_action_plan", comment = "Scheduled module action plan")
public class ScheduledActionPlan extends StandardEntity {
    /** An absent update version must stay absent; insert lifecycle initializes persisted version zero. */
    public ScheduledActionPlan() { setVersion(null); }

    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "moduleAlias")
    private String moduleAlias;
    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "actionCode")
    private String actionCode;
    @Column(name = "arguments_json", type = ColumnType.TEXT, nullable = false, comment = "argumentsJson")
    private String argumentsJson = "{}";
    /** Persisted {@link JobIdentityMode} name; SERVICE_ACCOUNT is reserved. */
    @Column(name = "identity_mode", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "identityMode")
    private String identityMode = "INITIATOR";
    @Column(name = "execution_user_id", type = ColumnType.VARCHAR, length = 64, comment = "executionUserId")
    private String executionUserId;
    @Column(name = "job_plan_id", type = ColumnType.VARCHAR, length = 32, comment = "jobPlanId")
    private String jobPlanId;
    @Column(name = "schedule_kind", type = ColumnType.VARCHAR, length = 16, nullable = false, comment = "scheduleKind")
    private String scheduleKind = "FIXED_DELAY";
    @Column(name = "expression", type = ColumnType.VARCHAR, length = 256, comment = "expression")
    private String expression;
    @Column(name = "zone_id", type = ColumnType.VARCHAR, length = 64, comment = "zoneId")
    private String zoneId = "UTC";
    @Column(name = "interval_millis", type = ColumnType.BIGINT, comment = "intervalMillis")
    private Long intervalMillis = 3600000L;
    @Column(name = "initial_delay_millis", type = ColumnType.BIGINT, comment = "initialDelayMillis")
    private Long initialDelayMillis = 60000L;
    @Column(name = "enabled", type = ColumnType.BOOLEAN, nullable = false, comment = "enabled")
    private Boolean enabled = true;
}
