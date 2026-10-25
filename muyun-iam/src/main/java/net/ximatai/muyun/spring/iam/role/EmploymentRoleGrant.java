package net.ximatai.muyun.spring.iam.role;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

@Getter
@Setter
@Table(name = "iam_employment_role_grant", comment = "Employment role grant")
@CompositeIndex(columns = {"tenant_id", "role_id", "employee_position_id", "source", "source_provider_id"}, unique = true)
@InitialDataFields(
        includeId = false,
        identity = {"roleId", "employeePositionId", "source", "sourceProviderId"},
        managed = {"enabled"}
)
public class EmploymentRoleGrant extends StandardEntity {
    @Column(name = "role_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Role id")
    private String roleId;

    @Column(name = "employee_position_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Employee position id")
    private String employeePositionId;

    @Column(name = "source", type = ColumnType.VARCHAR, length = 16, nullable = false,
            comment = "manual or directory", defaultVal = @Default(varchar = "manual"))
    private String source = "manual";

    @Column(name = "source_provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Owning provider; dash for manual", defaultVal = @Default(varchar = "-"))
    private String sourceProviderId = "-";

    @Column(name = "enabled", type = ColumnType.BOOLEAN, nullable = false, comment = "Enabled flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean enabled = Boolean.TRUE;
}
