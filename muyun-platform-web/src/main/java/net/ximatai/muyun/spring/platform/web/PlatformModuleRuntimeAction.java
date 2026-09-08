package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;

public record PlatformModuleRuntimeAction(
        String actionCode,
        String permissionActionCode,
        String title,
        PlatformActionLevel actionLevel,
        EntityActionCategory category,
        ActionAccessMode accessMode,
        boolean actionAuth,
        boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        EntityActionExecutorType executorType,
        String executorKey,
        boolean authorized,
        String authorizationDecision,
        boolean bindingPending,
        boolean formSupported
) {
    public PlatformModuleRuntimeAction(String actionCode, String permissionActionCode, String title,
            PlatformActionLevel actionLevel, EntityActionCategory category, ActionAccessMode accessMode,
            boolean actionAuth, boolean dataAuth, ActionDefaultGrantPolicy defaultGrantPolicy,
            EntityActionExecutorType executorType, String executorKey, boolean authorized,
            String authorizationDecision, boolean bindingPending) {
        this(actionCode, permissionActionCode, title, actionLevel, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, executorType, executorKey, authorized, authorizationDecision,
                bindingPending, false);
    }
    public PlatformModuleRuntimeAction(String actionCode, String permissionActionCode, String title,
            PlatformActionLevel actionLevel, EntityActionCategory category, ActionAccessMode accessMode,
            boolean actionAuth, boolean dataAuth, ActionDefaultGrantPolicy defaultGrantPolicy,
            EntityActionExecutorType executorType, String executorKey, boolean authorized, String authorizationDecision) {
        this(actionCode, permissionActionCode, title, actionLevel, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, executorType, executorKey, authorized, authorizationDecision, false, false);
    }
}
