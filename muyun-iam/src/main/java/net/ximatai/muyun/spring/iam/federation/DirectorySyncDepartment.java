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
@Table(name = "iam_directory_sync_department", comment = "DirectorySyncDepartment")
@CompositeIndex(columns = {"run_id", "id"})
@TenantUniqueConstraint(fields = {"runId", "externalDepartmentId"})
public class DirectorySyncDepartment extends StandardEntity {
    @Column(name = "run_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "runId")
    private String runId;
    @Column(name = "external_department_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "externalDepartmentId")
    private String externalDepartmentId;
    @Column(name = "root_id", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "rootId")
    private String rootId;
    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "organizationId")
    private String organizationId;
}
