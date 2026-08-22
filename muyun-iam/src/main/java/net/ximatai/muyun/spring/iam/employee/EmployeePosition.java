package net.ximatai.muyun.spring.iam.employee;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.common.model.capability.SortCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.position.PositionService;

@Getter
@Setter
@Table(name = "iam_employee_position", comment = "Employee position")
@SortPartitionBy(fields = "employeeId", message = "Employee position sort can only move records within the same employee")
@CompositeIndex(columns = {"tenant_id", "employee_id", "organization_id", "department_id", "position_id"},
        unique = true)
public class EmployeePosition extends StandardEntity implements EnabledCapable, SortCapable {
    @Column(name = "employee_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Employee id")
    @ChildOf
    @ReferenceTo(target = EmployeeService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
    private String employeeId;

    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Organization id")
    @ReferenceTo(target = OrganizationService.class)
    private String organizationId;

    @ReferenceLoad(source = "organizationId", field = "title")
    private transient String organizationTitle;

    @Column(name = "department_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Department id")
    @ReferenceTo(target = DepartmentService.class,
            candidateBindings = @ReferenceCandidateBinding(sourceField = "organizationId", targetField = "organizationId"))
    private String departmentId;

    @ReferenceLoad(source = "departmentId", field = "title")
    private transient String departmentTitle;

    @Column(name = "position_id", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Position id")
    @ReferenceTo(target = PositionService.class)
    private String positionId;

    @ReferenceLoad(source = "positionId", field = "title")
    private transient String positionTitle;

    @Column(name = "primary_position", type = ColumnType.BOOLEAN, comment = "Primary position",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean primaryPosition = Boolean.FALSE;

    @Column(name = PlatformAbilityFields.ENABLED_COLUMN, type = ColumnType.BOOLEAN, comment = "Enabled flag",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean enabled = Boolean.TRUE;

    @Column(name = PlatformAbilityFields.SORT_COLUMN, type = ColumnType.INT, comment = "Sort order")
    private Integer sortOrder;
}
