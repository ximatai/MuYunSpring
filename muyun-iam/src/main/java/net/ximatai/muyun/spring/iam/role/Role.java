package net.ximatai.muyun.spring.iam.role;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.common.initialdata.InitialDataFields;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;

@Getter
@Setter
@Table(name = "iam_role", comment = "Role")
@SortPartitionBy(fields = {"ownerScopeType", "ownerScopeKey"}, message = "role sort scope must stay inside the same owner scope")
@TenantUniqueConstraint(fields = {"ownerScopeType", "ownerScopeKey", "assignmentType", "roleKind", "title"})
@InitialDataFields(
        managed = {"assignmentType", "roleKind", "memberRoleIds", "ownerScopeType", "ownerScopeId",
                "sharePolicy", "builtIn", "systemManaged", "systemPurpose", "description"},
        operator = {"title", "enabled", "sortOrder"}
)
public class Role extends StandardEnabledSortableEntity {
    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "assignment_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Role assignment type", defaultVal = @Default(varchar = "employment"))
    private RoleAssignmentType assignmentType = RoleAssignmentType.EMPLOYMENT;

    @OptionLoad(source = "assignmentType")
    private transient String assignmentTypeTitle;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "role_kind", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Role kind",
            defaultVal = @Default(varchar = "standard"))
    private RoleKind roleKind = RoleKind.STANDARD;

    @OptionLoad(source = "roleKind")
    private transient String roleKindTitle;

    @Column(name = "member_role_ids", type = ColumnType.TEXT, comment = "Member role ids for role group")
    private String memberRoleIds;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "owner_scope_type", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Role owner scope type", defaultVal = @Default(varchar = "tenant"))
    private RoleOwnerScopeType ownerScopeType = RoleOwnerScopeType.TENANT;

    @OptionLoad(source = "ownerScopeType")
    private transient String ownerScopeTypeTitle;

    @Column(name = "owner_scope_id", type = ColumnType.VARCHAR, length = 64, comment = "Role owner scope id")
    private String ownerScopeId;

    @Column(name = "owner_scope_key", type = ColumnType.VARCHAR, length = 96, nullable = false,
            comment = "Stable non-null role owner scope key", defaultVal = @Default(varchar = "tenant:"))
    private String ownerScopeKey;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "share_policy", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Role share policy", defaultVal = @Default(varchar = "private"))
    private RoleSharePolicy sharePolicy = RoleSharePolicy.PRIVATE;

    @OptionLoad(source = "sharePolicy")
    private transient String sharePolicyTitle;

    @Column(name = "built_in", type = ColumnType.BOOLEAN, comment = "Built-in role flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean builtIn = Boolean.FALSE;

    @Column(name = "system_managed", type = ColumnType.BOOLEAN, comment = "System managed role flag",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemManaged = Boolean.FALSE;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "system_purpose", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Platform-recognized system role purpose", defaultVal = @Default(varchar = "none"))
    private RoleSystemPurpose systemPurpose = RoleSystemPurpose.NONE;

    @OptionLoad(source = "systemPurpose")
    private transient String systemPurposeTitle;

    @Column(name = "description", type = ColumnType.TEXT, comment = "Role description")
    private String description;
}
