package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoad;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.constraint.Required;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionLoad;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.role.RoleService;

/** A tenant's decision to accept one identity provider and how its users may use it. */
@Getter
@Setter
@Table(name = "iam_tenant_identity_policy", comment = "Tenant external identity policy")
@TenantUniqueConstraint(fields = "providerId")
public class TenantIdentityPolicy extends StandardEnabledSortableEntity {
    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Identity provider id")
    @ReferenceTo(target = IdentityProviderService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    @Required
    private String providerId;

    @ReferenceLoad(source = "providerId", field = "title")
    private transient String providerTitle;

    @Column(name = "login_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether users may sign in with this provider", defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean loginEnabled = Boolean.TRUE;

    @Column(name = "binding_enabled", type = ColumnType.BOOLEAN, nullable = false,
            comment = "Whether signed-in users may bind this provider", defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean bindingEnabled = Boolean.TRUE;

    @OptionField(type = OptionSourceType.ENUM)
    @Column(name = "provisioning_mode", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Handling of verified identities without a local account",
            defaultVal = @Default(varchar = "disabled"))
    private ProvisioningMode provisioningMode = ProvisioningMode.DISABLED;

    @OptionLoad(source = "provisioningMode")
    private transient String provisioningModeTitle;

    @Column(name = "sync_roots", type = ColumnType.TEXT, comment = "JSON root to organization mappings")
    private String syncRoots;
    @Column(name = "sync_scope_confirmed", type = ColumnType.BOOLEAN, comment = "Explicit whole enterprise or empty scope confirmation")
    private Boolean syncScopeConfirmed = false;

    @Column(
            name = "sync_position_id",
            type = ColumnType.VARCHAR,
            length = 32,
            comment = "Default directory position")
    @ReferenceTo(target = PositionService.class)
    private String syncPositionId;

    @Column(
            name = "sync_role_id",
            type = ColumnType.VARCHAR,
            length = 32,
            comment = "Default employment role")
    @ReferenceTo(target = RoleService.class)
    private String syncRoleId;

    @Column(name = "sync_expression", type = ColumnType.VARCHAR, length = 128, comment = "Minute precision cron")
    private String syncExpression;
    @Column(name = "sync_zone_id", type = ColumnType.VARCHAR, length = 64, comment = "Directory schedule time zone")
    private String syncZoneId;
    @Column(name = "sync_enabled", type = ColumnType.BOOLEAN, comment = "Directory schedule enabled")
    private Boolean syncEnabled = false;
    @Column(name = "sync_scope_id", type = ColumnType.VARCHAR, length = 32, comment = "Immutable directory configuration version")
    private String syncScopeId;
    @Column(name = "sync_plan_id", type = ColumnType.VARCHAR, length = 32, comment = "Managed job plan")
    private String syncPlanId;

    @Column(name = "pending_sync_scope_id", type = ColumnType.VARCHAR, length = 32,
            comment = "Directory scope awaiting impact review")
    private String pendingSyncScopeId;

    @Column(name = "source_status_max_age_seconds", type = ColumnType.INT, nullable = false,
            defaultVal = @Default(number = 86400), comment = "Maximum authoritative source status age")
    private Integer sourceStatusMaxAgeSeconds = 86400;
}
