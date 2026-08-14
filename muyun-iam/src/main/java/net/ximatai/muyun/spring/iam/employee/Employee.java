package net.ximatai.muyun.spring.iam.employee;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;

@Getter
@Setter
@Table(name = "iam_employee", comment = "Employee")
@SortPartitionBy(fields = {"organizationId", "departmentId"}, message = "Employee sort can only move records within the same department")
@TenantUniqueConstraint(fields = {"organizationId", "employeeNo"})
@InitialDataFields(
        managed = {"organizationId", "departmentId", "employeeNo"},
        operator = {"title", "gender", "mobile", "email", "enabled", "sortOrder"}
)
public class Employee extends StandardEnabledSortableEntity {
    @FileReference(allowedMediaTypes = {"image/png", "image/jpeg", "image/gif", "image/webp"},
            maxFileSizeBytes = 1048576, storagePolicy = FileReferenceStoragePolicy.DATABASE_INLINE)
    @Column(name = "avatar_asset_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Employee avatar managed file asset id")
    private String avatarAssetId;

    @Column(name = "organization_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Organization id")
    @ReferenceTo(target = OrganizationService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
    private String organizationId;

    /** Stable read fact reused by detail, list and domain read facades. */
    @ReferenceLoad(source = "organizationId", field = "title")
    private transient String organizationTitle;

    @Column(name = "department_id", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Department id")
    @ReferenceTo(target = DepartmentService.class,
            integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
    private String departmentId;

    /** Stable read fact reused by detail, list and domain read facades. */
    @ReferenceLoad(source = "departmentId", field = "title")
    private transient String departmentTitle;

    @Column(name = "employee_no", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Employee number")
    private String employeeNo;

    @DictionaryField(
            source = "iam.gender",
            title = "性别",
            sortOrder = 10,
            initialItems = {
                    @DictionaryField.InitialItem(code = "1", title = "男", sortOrder = 10),
                    @DictionaryField.InitialItem(code = "2", title = "女", sortOrder = 20)
            }
    )
    @Column(name = "gender", type = ColumnType.VARCHAR, length = 64, comment = "Gender")
    private String gender;

    @OptionLoad(source = "gender")
    private transient String genderTitle;

    @Column(name = "mobile", type = ColumnType.VARCHAR, length = 32, comment = "Mobile")
    private String mobile;

    @Column(name = "email", type = ColumnType.VARCHAR, length = 128, comment = "Email")
    private String email;
}
