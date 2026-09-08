package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;

public record ModuleActionContribution(
        String moduleAlias,
        String entityAlias,
        String actionCode,
        String permissionActionCode,
        String title,
        EntityActionCategory category,
        EntityActionLevel actionLevel,
        EntityActionAccessMode accessMode,
        boolean actionAuth,
        boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        String availableExpression,
        String unavailableMessage,
        EntityActionExecutorType executorType,
        String executorKey,
        ModuleActionSourceType sourceType,
        String sourceId,
        String sourceVersionId,
        ModuleActionBindingType bindingType,
        String bindingId,
        String bindingAlias,
        boolean enabled,
        boolean formSupported
) {
    public ModuleActionContribution(
        String moduleAlias,
        String entityAlias,
        String actionCode,
        String permissionActionCode,
        String title,
        EntityActionCategory category,
        EntityActionLevel actionLevel,
        EntityActionAccessMode accessMode,
        boolean actionAuth,
        boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        String availableExpression,
        String unavailableMessage,
        EntityActionExecutorType executorType,
        String executorKey,
        ModuleActionSourceType sourceType,
        String sourceId,
        String sourceVersionId,
        ModuleActionBindingType bindingType,
        String bindingId,
        String bindingAlias,
        boolean enabled
    ) {
        this(moduleAlias, entityAlias, actionCode, permissionActionCode, title, category, actionLevel, accessMode, actionAuth, dataAuth, defaultGrantPolicy, availableExpression, unavailableMessage, executorType, executorKey, sourceType, sourceId, sourceVersionId, bindingType, bindingId, bindingAlias, enabled, false);
    }

    public ModuleActionContribution {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (entityAlias != null && entityAlias.isBlank()) {
            entityAlias = null;
        }
        if (entityAlias != null) {
            entityAlias = PlatformNameRules.requireIdentifier(entityAlias, "entityAlias");
        }
        actionCode = PlatformNameRules.requireActionCode(actionCode, "actionCode");
        permissionActionCode = permissionActionCode == null || permissionActionCode.isBlank()
                ? actionCode
                : PlatformNameRules.requireActionCode(permissionActionCode, "permissionActionCode");
        title = title == null || title.isBlank() ? actionCode : title.trim();
        category = category == null ? EntityActionDefinition.defaultCategory(actionCode) : category;
        actionLevel = actionLevel == null ? EntityActionDefinition.defaultLevel(actionCode, category) : actionLevel;
        accessMode = accessMode == null ? EntityActionAccessMode.AUTH_REQUIRED : accessMode;
        defaultGrantPolicy = defaultGrantPolicy == null ? ActionDefaultGrantPolicy.NONE : defaultGrantPolicy;
        executorType = executorType == null ? EntityActionDefinition.defaultExecutorType(category) : executorType;
        if (availableExpression != null && availableExpression.isBlank()) {
            availableExpression = null;
        }
        if (unavailableMessage != null && unavailableMessage.isBlank()) {
            unavailableMessage = null;
        }
        if (executorKey != null && executorKey.isBlank()) {
            executorKey = null;
        }
        if (sourceId != null && sourceId.isBlank()) {
            sourceId = null;
        }
        if (sourceVersionId != null && sourceVersionId.isBlank()) {
            sourceVersionId = null;
        }
        if (bindingId != null && bindingId.isBlank()) {
            bindingId = null;
        }
        if (bindingAlias != null && bindingAlias.isBlank()) {
            bindingAlias = null;
        }
    }
}
