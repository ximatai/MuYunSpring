package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import java.time.Instant;

/** Persistent directory topic facts; execution leases belong to the job platform. */
@Getter
@Setter
@Table(name = "iam_directory_department_mapping", comment = "DirectoryDepartmentMapping")
@TenantUniqueConstraint(fields = {"providerId", "externalDepartmentId"})
public class DirectoryDepartmentMapping extends StandardEntity {
    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "providerId")
    private String providerId;
    @Column(name = "external_department_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "externalDepartmentId")
    private String externalDepartmentId;
    @Column(name = "root_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "rootId")
    private String rootId;
    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "organizationId")
    private String organizationId;
    @Column(name = "department_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "departmentId")
    private String departmentId;
    @Column(name = "parent_external_id", type = ColumnType.VARCHAR, length = 128, comment = "parentExternalId")
    private String parentExternalId;
    @Column(name = "last_run_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "lastRunId")
    private String lastRunId;
    @Column(name = "last_seen_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "lastSeenAt")
    private Instant lastSeenAt;
}
