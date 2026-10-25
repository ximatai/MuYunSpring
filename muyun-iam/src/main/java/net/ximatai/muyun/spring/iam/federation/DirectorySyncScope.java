package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

/** Persistent directory topic facts; execution leases belong to the job platform. */
@Getter
@Setter
@Table(name = "iam_directory_sync_scope", comment = "DirectorySyncScope")
public class DirectorySyncScope extends StandardEntity {
    @Column(name = "policy_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "policyId")
    private String policyId;
    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "providerId")
    private String providerId;
    @Column(name = "roots", type = ColumnType.TEXT, nullable = false, comment = "roots")
    private String roots;
    @Column(name = "position_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "positionId")
    private String positionId;
    @Column(name = "base_scope_id", type = ColumnType.VARCHAR, length = 32, comment = "Active revision reviewed by this proposal")
    private String baseScopeId;
    @Column(name = "impact_report", type = ColumnType.TEXT, comment = "Completed scope preview impact counts")
    private String impactReport;
    @Column(name = "report_run_id", type = ColumnType.VARCHAR, length = 32, comment = "Completed scope preview run")
    private String reportRunId;
    @Column(name = "role_id", type = ColumnType.VARCHAR, length = 32, comment = "roleId")
    private String roleId;
}
