package net.ximatai.muyun.spring.platform.module;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Default;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.annotation.TrueOrFalse;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledSortableEntity;
import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.option.OptionField;
import net.ximatai.muyun.spring.common.option.OptionSourceType;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

@Getter
@Setter
@Table(name = "platform_module_action", comment = "Platform module action")
@CompositeIndex(columns = {"module_alias", "action_code"}, unique = true)
@net.ximatai.muyun.spring.ability.SortPartitionBy(fields = "moduleAlias")
public class PlatformModuleAction extends StandardEnabledSortableEntity implements PlatformManagedCapable {
    @Column(name = "module_alias", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Module alias")
    @ReferenceTo(target = PlatformModuleService.class, tenantScope = ReferenceTenantScope.GLOBAL)
    private String moduleAlias;

    @Column(name = "action_code", type = ColumnType.VARCHAR, length = 64, nullable = false, comment = "Action code")
    private String actionCode;

    @Column(name = "entity_alias", type = ColumnType.VARCHAR, length = 64, comment = "Target entity alias")
    private String entityAlias;

    @Column(name = "permission_action_code", type = ColumnType.VARCHAR, length = 64,
            comment = "Permission action code")
    private String permissionActionCode;

    @Column(name = "title", type = ColumnType.VARCHAR, length = 128, nullable = false, comment = "Action title")
    private String title;

    @Column(name = "category", type = ColumnType.VARCHAR, length = 32, comment = "Action category")
    @OptionField(type = OptionSourceType.ENUM)
    private EntityActionCategory category;

    @Column(name = "action_level", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Action execution level")
    @OptionField(type = OptionSourceType.ENUM)
    private EntityActionLevel actionLevel;

    @Column(name = "access_mode", type = ColumnType.VARCHAR, length = 32, nullable = false,
            comment = "Action access mode", defaultVal = @Default(varchar = "AUTH_REQUIRED"))
    @OptionField(type = OptionSourceType.ENUM)
    private EntityActionAccessMode accessMode = EntityActionAccessMode.AUTH_REQUIRED;

    @Column(name = "action_auth", comment = "Whether action permission applies",
            defaultVal = @Default(bool = TrueOrFalse.TRUE))
    private Boolean actionAuth = Boolean.TRUE;

    @Column(name = "data_auth", comment = "Whether data permission applies",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean dataAuth = Boolean.FALSE;

    @Column(name = "access_mode_override", type = ColumnType.VARCHAR, length = 32,
            comment = "Governance override for action access mode")
    @OptionField(type = OptionSourceType.ENUM)
    private EntityActionAccessMode accessModeOverride;

    @Column(name = "action_auth_override", comment = "Governance override for action permission")
    private Boolean actionAuthOverride;

    @Column(name = "data_auth_override", comment = "Governance override for data permission")
    private Boolean dataAuthOverride;

    @Column(name = "default_grant_policy_override", type = ColumnType.VARCHAR, length = 32,
            comment = "Governance override for default action grant policy")
    @OptionField(type = OptionSourceType.ENUM)
    private ActionDefaultGrantPolicy defaultGrantPolicyOverride;

    @Column(name = "default_grant_policy", type = ColumnType.VARCHAR, length = 32,
            comment = "Default action grant policy")
    @OptionField(type = OptionSourceType.ENUM)
    private ActionDefaultGrantPolicy defaultGrantPolicy;

    @Column(name = "available_expression", type = ColumnType.TEXT, comment = "Availability expression")
    private String availableExpression;

    @Column(name = "unavailable_message", type = ColumnType.VARCHAR, length = 256, comment = "Unavailable message")
    private String unavailableMessage;

    @Column(name = "executor_type", type = ColumnType.VARCHAR, length = 32, comment = "Action executor type")
    @OptionField(type = OptionSourceType.ENUM)
    private EntityActionExecutorType executorType;

    @Column(name = "executor_key", type = ColumnType.VARCHAR, length = 128, comment = "Action executor key")
    private String executorKey;

    @Column(name = "source_type", type = ColumnType.VARCHAR, length = 64, comment = "Action contribution source type")
    private ModuleActionSourceType sourceType;

    @Column(name = "source_id", type = ColumnType.VARCHAR, length = 128, comment = "Action contribution source id")
    private String sourceId;

    @Column(name = "source_version_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Action contribution source version id")
    private String sourceVersionId;

    @Column(name = "binding_type", type = ColumnType.VARCHAR, length = 64, comment = "Action binding type")
    private ModuleActionBindingType bindingType;

    @Column(name = "binding_id", type = ColumnType.VARCHAR, length = 128, comment = "Action binding id")
    private String bindingId;

    @Column(name = "binding_alias", type = ColumnType.VARCHAR, length = 128, comment = "Action binding alias")
    private String bindingAlias;

    @Column(name = "system_managed", comment = "Whether action is managed by platform",
            defaultVal = @Default(bool = TrueOrFalse.FALSE))
    private Boolean systemManaged = Boolean.FALSE;

    public EntityActionAccessMode effectiveAccessMode() {
        return accessModeOverride == null ? accessMode : accessModeOverride;
    }

    public boolean effectiveActionAuth() {
        return actionAuthOverride == null ? Boolean.TRUE.equals(actionAuth) : actionAuthOverride;
    }

    public boolean effectiveDataAuth() {
        return dataAuthOverride == null ? Boolean.TRUE.equals(dataAuth) : dataAuthOverride;
    }

    public ActionDefaultGrantPolicy effectiveDefaultGrantPolicy() {
        return defaultGrantPolicyOverride == null ? defaultGrantPolicy : defaultGrantPolicyOverride;
    }
}
