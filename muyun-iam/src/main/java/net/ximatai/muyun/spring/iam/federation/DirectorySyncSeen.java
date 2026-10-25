package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

/** Persistent directory topic facts; execution leases belong to the job platform. */
@Getter
@Setter
@Table(name = "iam_directory_sync_seen", comment = "DirectorySyncSeen")
@TenantUniqueConstraint(fields = {"runId", "externalUserId"})
public class DirectorySyncSeen extends StandardEntity {
    @Column(name = "run_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "runId")
    private String runId;
    @Column(name = "external_user_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "externalUserId")
    private String externalUserId;
}
