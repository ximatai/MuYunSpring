package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

public record StaticModuleActionDefinition(
        String actionCode,
        String permissionActionCode,
        String title,
        EntityActionLevel actionLevel,
        EntityActionCategory category,
        EntityActionAccessMode accessMode,
        boolean actionAuth,
        boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        EntityActionExecutorType executorType,
        String executorKey
) {
    public StaticModuleActionDefinition(String actionCode,
                                        String permissionActionCode,
                                        String title,
                                        EntityActionLevel actionLevel,
                                        EntityActionAccessMode accessMode,
                                        boolean actionAuth,
                                        boolean dataAuth,
                                        ActionDefaultGrantPolicy defaultGrantPolicy) {
        this(actionCode, permissionActionCode, title, actionLevel, null, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, null, null);
    }

    public StaticModuleActionDefinition {
        actionCode = PlatformNameRules.requireActionCode(actionCode, "actionCode");
        permissionActionCode = permissionActionCode == null || permissionActionCode.isBlank()
                ? actionCode
                : PlatformNameRules.requireActionCode(permissionActionCode, "permissionActionCode");
        title = title == null || title.isBlank() ? actionCode : title.trim();
        actionLevel = actionLevel == null ? EntityActionLevel.ANY : actionLevel;
        accessMode = accessMode == null ? EntityActionAccessMode.AUTH_REQUIRED : accessMode;
        defaultGrantPolicy = defaultGrantPolicy == null ? ActionDefaultGrantPolicy.NONE : defaultGrantPolicy;
        if (executorKey != null && executorKey.isBlank()) {
            executorKey = null;
        }
    }

    public static StaticModuleActionDefinition recordAction(String actionCode, String title) {
        return new StaticModuleActionDefinition(
                actionCode,
                actionCode,
                title,
                EntityActionLevel.RECORD,
                null,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                true,
                ActionDefaultGrantPolicy.NONE,
                null,
                null
        );
    }

    public static StaticModuleActionDefinition platformAction(PlatformAction action) {
        return new StaticModuleActionDefinition(
                action.code(),
                action.permissionActionCode(),
                action.title(),
                toEntityLevel(action.level()),
                null,
                EntityActionAccessMode.valueOf(action.accessMode().name()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                null,
                null
        );
    }

    public static StaticModuleActionDefinition workflowAction(String actionCode, String title) {
        return new StaticModuleActionDefinition(
                actionCode,
                actionCode,
                title,
                EntityActionLevel.RECORD,
                EntityActionCategory.WORKFLOW,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                false,
                ActionDefaultGrantPolicy.NONE,
                EntityActionExecutorType.SERVICE,
                "platform.workflow"
        );
    }

    /** Complete executable fallback consumed by HTTP authorization and data-scope resolution. */
    public ActionExecutionPolicy executionPolicy() {
        return new ActionExecutionPolicy(
                actionCode,
                actionLevel == null ? PlatformActionLevel.ANY : PlatformActionLevel.valueOf(actionLevel.name()),
                accessMode == null ? ActionAccessMode.AUTH_REQUIRED : ActionAccessMode.valueOf(accessMode.name()),
                actionAuth,
                dataAuth,
                defaultGrantPolicy,
                actionAuth && !actionCode.equals(permissionActionCode) ? permissionActionCode : null
        );
    }

    private static EntityActionLevel toEntityLevel(PlatformActionLevel level) {
        if (level == null) {
            return EntityActionLevel.ANY;
        }
        return switch (level) {
            case LIST -> EntityActionLevel.LIST;
            case RECORD -> EntityActionLevel.RECORD;
            case BATCH -> EntityActionLevel.BATCH;
            case DEFAULT, ANY -> EntityActionLevel.ANY;
        };
    }
}
