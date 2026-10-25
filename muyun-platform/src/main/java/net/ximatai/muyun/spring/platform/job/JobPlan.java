package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "platform_job_plan", comment = "Background job plan")
@CompositeIndex(columns = {"plan_key"}, unique = true)
@CompositeIndex(columns = {"enabled", "next_fire_at"})
public class JobPlan extends StandardEntity {
    @Column(name = "job_type", type = ColumnType.VARCHAR, length = 128, comment = "jobType", nullable = false)
    private String jobType;

    @Column(name = "scope", type = ColumnType.VARCHAR, length = 16, comment = "scope", nullable = false)
    private String scope;

    @Column(name = "object_key", type = ColumnType.VARCHAR, length = 512, comment = "objectKey", nullable = false)
    private String objectKey;

    @Column(name = "plan_key", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "planKey")
    private String planKey;

    @Column(name = "schedule_kind", type = ColumnType.VARCHAR, length = 16, comment = "scheduleKind", nullable = false)
    private String scheduleKind;

    @Column(name = "expression", type = ColumnType.VARCHAR, length = 256, comment = "expression")
    private String expression;

    @Column(name = "zone_id", type = ColumnType.VARCHAR, length = 64, comment = "zoneId")
    private String zoneId;

    @Column(name = "interval_millis", type = ColumnType.BIGINT, comment = "intervalMillis")
    private Long intervalMillis;

    @Column(name = "initial_delay_millis", type = ColumnType.BIGINT, comment = "initialDelayMillis")
    private Long initialDelayMillis;

    @Column(name = "enabled", type = ColumnType.BOOLEAN, comment = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "available", type = ColumnType.BOOLEAN, comment = "available", nullable = false)
    private Boolean available;

    @Column(name = "managed_by", type = ColumnType.VARCHAR, length = 128, comment = "managedBy")
    private String managedBy;

    /** Persisted {@link JobIdentityMode} name; SERVICE_ACCOUNT is reserved. */
    @Column(name = "identity_mode", type = ColumnType.VARCHAR, length = 32, comment = "identityMode", nullable = false)
    private String identityMode;

    @JsonIgnore
    @Column(name = "parameters_json", type = ColumnType.TEXT, comment = "parametersJson", nullable = false)
    private String parametersJson;

    @Column(name = "next_fire_at", type = ColumnType.TIMESTAMP, comment = "nextFireAt")
    private Instant nextFireAt;

    @Column(name = "consecutive_failures", type = ColumnType.INT, comment = "consecutiveFailures", nullable = false)
    private Integer consecutiveFailures;

    @Column(name = "last_status", type = ColumnType.VARCHAR, length = 16, comment = "lastStatus")
    private String lastStatus;

    @Column(name = "skipped_count", type = ColumnType.BIGINT, comment = "skippedCount", nullable = false)
    private Long skippedCount;

    /** Configuration revision; runtime state such as the next fire time does not advance it. */
    @Column(name = "config_revision", type = ColumnType.INT, comment = "configRevision", nullable = false)
    private Integer configRevision;
}
