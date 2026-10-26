package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;

import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.platform.job.JobRunStatus;

/** Persistent directory topic facts; execution leases belong to the job platform. */
@Getter
@Setter
@Table(name = "iam_directory_sync_run", comment = "DirectorySyncRun")
@CompositeIndex(columns = {"status", "updated_at", "id"})
@CompositeIndex(columns = {"tenant_id", "scope_id", "mode", "completed", "created_at"})
@TenantUniqueConstraint(fields = "jobRunId")
public class DirectorySyncRun extends StandardEntity {
    @Column(name = "authority_fingerprint", type = ColumnType.VARCHAR, length = 64)
    private String authorityFingerprint;

    @Column(name = "job_run_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "jobRunId")
    private String jobRunId;
    @Column(name = "policy_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "policyId")
    private String policyId;
    @Column(name = "scope_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "scopeId")
    private String scopeId;

    @Column(
            name = "mode",
            type = ColumnType.VARCHAR,
            length = 16,
            nullable = false,
            comment = "mode")
    @OptionField(type = OptionSourceType.ENUM, enumType = DirectorySyncMode.class)
    private String mode;

    @Column(
            name = "status",
            type = ColumnType.VARCHAR,
            length = 32,
            nullable = false,
            comment = "status")
    @OptionField(type = OptionSourceType.ENUM, enumType = JobRunStatus.class)
    private String status;

    @Column(name = "cursor", type = ColumnType.TEXT, nullable = false, comment = "cursor")
    private String cursor;
    @Column(name = "statistics", type = ColumnType.TEXT, nullable = false, comment = "statistics")
    private String statistics;
    @Column(name = "failed_pages", type = ColumnType.TEXT, comment = "failedPages")
    private String failedPages;
    @Column(name = "completed", type = ColumnType.BOOLEAN, nullable = false, comment = "completed")
    private Boolean completed;
    @Column(name = "retention_started", type = ColumnType.BOOLEAN, comment = "Topic facts are being removed")
    private Boolean retentionStarted = false;
}
