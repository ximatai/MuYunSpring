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
        boolean formSupported,
        java.util.Map<PageActionAnchor, PageActionInvocation> invocations,
        net.ximatai.muyun.spring.common.i18n.TextReference titleSource
) {
    public PlatformModuleRuntimeAction {
        invocations = invocations == null ? java.util.Map.of() : java.util.Map.copyOf(invocations);
    }
    public PlatformModuleRuntimeAction withInvocations(java.util.Map<PageActionAnchor, PageActionInvocation> value) {
        return new PlatformModuleRuntimeAction(actionCode, permissionActionCode, title, actionLevel, category,
                accessMode, actionAuth, dataAuth, defaultGrantPolicy, executorType, executorKey,
                authorized, authorizationDecision, bindingPending, formSupported, value, titleSource);
    }
    public PlatformModuleRuntimeAction withTitleSource(net.ximatai.muyun.spring.common.i18n.TextReference source) {
        return new PlatformModuleRuntimeAction(actionCode, permissionActionCode, title, actionLevel, category,
                accessMode, actionAuth, dataAuth, defaultGrantPolicy, executorType, executorKey,
                authorized, authorizationDecision, bindingPending, formSupported, invocations, source);
    }
    public PlatformModuleRuntimeAction(String actionCode, String permissionActionCode, String title,
            PlatformActionLevel actionLevel, EntityActionCategory category, ActionAccessMode accessMode,
            boolean actionAuth, boolean dataAuth, ActionDefaultGrantPolicy defaultGrantPolicy,
            EntityActionExecutorType executorType, String executorKey, boolean authorized,
            String authorizationDecision, boolean bindingPending, boolean formSupported,
            java.util.Map<PageActionAnchor, PageActionInvocation> invocations) {
        this(actionCode, permissionActionCode, title, actionLevel, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, executorType, executorKey, authorized, authorizationDecision,
                bindingPending, formSupported, invocations, null);
    }
    public PlatformModuleRuntimeAction(String actionCode, String permissionActionCode, String title,
            PlatformActionLevel actionLevel, EntityActionCategory category, ActionAccessMode accessMode,
            boolean actionAuth, boolean dataAuth, ActionDefaultGrantPolicy defaultGrantPolicy,
            EntityActionExecutorType executorType, String executorKey, boolean authorized,
            String authorizationDecision, boolean bindingPending, boolean formSupported) {
        this(actionCode, permissionActionCode, title, actionLevel, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, executorType, executorKey, authorized, authorizationDecision,
                bindingPending, formSupported, java.util.Map.of());
    }
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
