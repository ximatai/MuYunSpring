package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
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
@Table(name = "platform_job_run", comment = "Background job run")
@CompositeIndex(columns = {"plan_id", "scheduled_at"}, unique = true)
@CompositeIndex(columns = {"status", "available_at"})
@CompositeIndex(columns = {"mutex_key", "lease_until"})
@CompositeIndex(columns = {"deduplication_key", "status"})
@CompositeIndex(columns = {"plan_id", "finished_at"})
public class JobRun extends StandardEntity {
    @Column(name = "job_type", type = ColumnType.VARCHAR, length = 128, comment = "jobType", nullable = false)
    private String jobType;

    @Column(name = "scope", type = ColumnType.VARCHAR, length = 16, comment = "scope", nullable = false)
    private String scope;

    @Column(name = "plan_id", type = ColumnType.VARCHAR, length = 32, comment = "planId")
    private String planId;

    @Column(name = "plan_revision", type = ColumnType.INT, comment = "planRevision")
    private Integer planRevision;

    @Column(name = "scheduled_at", type = ColumnType.TIMESTAMP, comment = "scheduledAt")
    private Instant scheduledAt;

    @Column(name = "trigger_type", type = ColumnType.VARCHAR, length = 16, comment = "triggerType", nullable = false)
    @OptionField(type = OptionSourceType.ENUM, enumType = JobTriggerType.class)
    private String triggerType;

    @Column(name = "triggered_by", type = ColumnType.VARCHAR, length = 64, comment = "triggeredBy")
    private String triggeredBy;

    /** Persisted {@link JobIdentityMode} name; SERVICE_ACCOUNT is reserved. */
    @Column(name = "identity_mode", type = ColumnType.VARCHAR, length = 32, comment = "identityMode", nullable = false)
    @OptionField(type = OptionSourceType.ENUM, enumType = JobIdentityMode.class)
    private String identityMode;

    @JsonIgnore
    @Column(name = "parameters_json", type = ColumnType.TEXT, comment = "parametersJson", nullable = false)
    private String parametersJson;

    @Column(name = "mutex_key", type = ColumnType.VARCHAR, length = 64, comment = "mutexKey", nullable = false)
    private String mutexKey;

    @Column(name = "deduplication_key", type = ColumnType.VARCHAR, length = 64, comment = "deduplicationKey")
    private String deduplicationKey;

    @Column(name = "channel", type = ColumnType.VARCHAR, length = 64, comment = "channel", nullable = false)
    private String channel;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 16, comment = "status", nullable = false)
    @OptionField(type = OptionSourceType.ENUM, enumType = JobRunStatus.class)
    private String status;

    @Column(name = "attempts", type = ColumnType.INT, comment = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "available_at", type = ColumnType.TIMESTAMP, comment = "availableAt", nullable = false)
    private Instant availableAt;

    @Column(name = "node_id", type = ColumnType.VARCHAR, length = 64, comment = "nodeId")
    private String nodeId;

    @JsonIgnore
    @Column(name = "lease_token", type = ColumnType.VARCHAR, length = 64, comment = "leaseToken")
    private String leaseToken;

    @Column(name = "lease_until", type = ColumnType.TIMESTAMP, comment = "leaseUntil")
    private Instant leaseUntil;

    @Column(name = "started_at", type = ColumnType.TIMESTAMP, comment = "startedAt")
    private Instant startedAt;

    @Column(name = "first_started_at", type = ColumnType.TIMESTAMP, comment = "firstStartedAt")
    private Instant firstStartedAt;

    @Column(name = "finished_at", type = ColumnType.TIMESTAMP, comment = "finishedAt")
    private Instant finishedAt;

    @Column(name = "cancel_requested", type = ColumnType.BOOLEAN, comment = "cancelRequested", nullable = false)
    private Boolean cancelRequested;

    @Column(name = "progress", type = ColumnType.INT, comment = "progress", nullable = false)
    private Integer progress;

    @Column(name = "result_code", type = ColumnType.VARCHAR, length = 128, comment = "resultCode")
    @OptionField(type = OptionSourceType.ENUM, enumType = JobResultCode.class)
    private String resultCode;

    @Column(name = "statistics_json", type = ColumnType.TEXT, comment = "statisticsJson")
    private String statisticsJson;

    @Column(name = "error_summary", type = ColumnType.VARCHAR, length = 512, comment = "errorSummary")
    private String errorSummary;

    @Column(name = "trace_id", type = ColumnType.VARCHAR, length = 64, comment = "traceId", nullable = false)
    private String traceId;
}
