package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

public record DynamicActionDescriptor(
        String code,
        String title,
        boolean enabled,
        EntityActionLevel actionLevel,
        EntityActionCategory category,
        EntityActionAccessMode accessMode,
        boolean actionAuth,
        boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        String authInheritActionCode,
        boolean availabilityCondition,
        String unavailableMessage,
        EntityActionExecutorType executorType,
        String executorKey,
        ActionPermissionDescriptor permission,
        net.ximatai.muyun.spring.common.i18n.TextReference titleSource
) {
    public DynamicActionDescriptor withTitleSource(net.ximatai.muyun.spring.common.i18n.TextReference source) {
        return new DynamicActionDescriptor(code, title, enabled, actionLevel, category, accessMode,
                actionAuth, dataAuth, defaultGrantPolicy, authInheritActionCode, availabilityCondition,
                unavailableMessage, executorType, executorKey, permission, source);
    }
    public DynamicActionDescriptor(String code, String title, boolean enabled, EntityActionLevel actionLevel,
            EntityActionCategory category, EntityActionAccessMode accessMode, boolean actionAuth, boolean dataAuth,
            ActionDefaultGrantPolicy defaultGrantPolicy, String authInheritActionCode, boolean availabilityCondition,
            String unavailableMessage, EntityActionExecutorType executorType, String executorKey,
            ActionPermissionDescriptor permission) {
        this(code, title, enabled, actionLevel, category, accessMode, actionAuth, dataAuth, defaultGrantPolicy,
                authInheritActionCode, availabilityCondition, unavailableMessage, executorType, executorKey, permission, null);
    }
    public DynamicActionDescriptor(String code,
                                   String title,
                                   boolean enabled,
                                   EntityActionLevel actionLevel,
                                   EntityActionCategory category,
                                   EntityActionAccessMode accessMode,
                                   boolean actionAuth,
                                   boolean dataAuth,
                                   ActionDefaultGrantPolicy defaultGrantPolicy,
                                   String authInheritActionCode,
                                   boolean availabilityCondition,
                                   String unavailableMessage,
                                   EntityActionExecutorType executorType,
                                   String executorKey) {
        this(code, title, enabled, actionLevel, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, authInheritActionCode, availabilityCondition,
                unavailableMessage, executorType, executorKey, null);
    }

    public DynamicActionDescriptor(String code,
                                   String title,
                                   boolean enabled,
                                   EntityActionLevel actionLevel,
                                   EntityActionCategory category,
                                   EntityActionAccessMode accessMode,
                                   boolean actionAuth,
                                   boolean dataAuth,
                                   String authInheritActionCode,
                                   boolean availabilityCondition,
                                   String unavailableMessage,
                                   EntityActionExecutorType executorType,
                                   String executorKey) {
        this(code, title, enabled, actionLevel, category, accessMode, actionAuth, dataAuth,
                ActionDefaultGrantPolicy.NONE, authInheritActionCode, availabilityCondition,
                unavailableMessage, executorType, executorKey, null);
    }

    public DynamicActionDescriptor withPermission(String moduleAlias) {
        return new DynamicActionDescriptor(code, title, enabled, actionLevel, category, accessMode,
                actionAuth, dataAuth, defaultGrantPolicy, authInheritActionCode, availabilityCondition, unavailableMessage,
                executorType, executorKey, ActionPermissionDescriptor.of(moduleAlias, this), titleSource);
    }
}
