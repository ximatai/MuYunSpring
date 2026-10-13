package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

public record EntityActionDefinition(
        String entityAlias,
        String actionCode,
        String title,
        boolean enabled,
        EntityActionLevel level,
        EntityActionCategory category,
        EntityActionAccessMode accessMode,
        Boolean actionAuth,
        Boolean dataAuth,
        ActionDefaultGrantPolicy defaultGrantPolicy,
        String authInheritActionCode,
        String availableExpression,
        String unavailableMessage,
        EntityActionExecutorType executorType,
        String executorKey,
        net.ximatai.muyun.spring.common.i18n.TextReference titleSource
) {
    public EntityActionDefinition {
        category = category == null ? defaultCategory(actionCode) : category;
        level = level == null ? defaultLevel(actionCode, category) : level;
        accessMode = accessMode == null ? EntityActionAccessMode.AUTH_REQUIRED : accessMode;
        actionAuth = actionAuth == null ? accessMode == EntityActionAccessMode.AUTH_REQUIRED : actionAuth;
        dataAuth = dataAuth != null && dataAuth;
        defaultGrantPolicy = defaultGrantPolicy == null ? ActionDefaultGrantPolicy.NONE : defaultGrantPolicy;
        executorType = executorType == null ? defaultExecutorType(category) : executorType;
    }

    public EntityActionDefinition withTitleSource(net.ximatai.muyun.spring.common.i18n.TextReference source) {
        return new EntityActionDefinition(entityAlias, actionCode, title, enabled, level, category, accessMode,
                actionAuth, dataAuth, defaultGrantPolicy, authInheritActionCode, availableExpression,
                unavailableMessage, executorType, executorKey, source);
    }
    public EntityActionDefinition(String entityAlias, String actionCode, String title, boolean enabled,
            EntityActionLevel level, EntityActionCategory category, EntityActionAccessMode accessMode,
            Boolean actionAuth, Boolean dataAuth, ActionDefaultGrantPolicy defaultGrantPolicy,
            String authInheritActionCode, String availableExpression, String unavailableMessage,
            EntityActionExecutorType executorType, String executorKey) {
        this(entityAlias, actionCode, title, enabled, level, category, accessMode, actionAuth, dataAuth,
                defaultGrantPolicy, authInheritActionCode, availableExpression, unavailableMessage, executorType, executorKey, null);
    }

    public EntityActionDefinition(String entityAlias,
                                  String actionCode,
                                  String title,
                                  boolean enabled,
                                  EntityActionLevel level,
                                  EntityActionCategory category,
                                  EntityActionAccessMode accessMode,
                                  Boolean actionAuth,
                                  Boolean dataAuth,
                                  String authInheritActionCode,
                                  String availableExpression,
                                  String unavailableMessage,
                                  EntityActionExecutorType executorType,
                                  String executorKey) {
        this(entityAlias, actionCode, title, enabled, level, category, accessMode,
                actionAuth, dataAuth, ActionDefaultGrantPolicy.NONE, authInheritActionCode,
                availableExpression, unavailableMessage, executorType, executorKey);
    }

    public EntityActionDefinition(String entityAlias,
                                  String actionCode,
                                  String title,
                                  boolean enabled,
                                  EntityActionCategory category) {
        this(entityAlias, actionCode, title, enabled, null, category, null,
                null, null, null, null, null, null, null);
    }

    public EntityActionDefinition(String entityAlias,
                                  String actionCode,
                                  String title,
                                  boolean enabled) {
        this(entityAlias, actionCode, title, enabled,
                PlatformAction.fromCode(actionCode).isPresent() ? EntityActionCategory.STANDARD : EntityActionCategory.CUSTOM);
    }

    public static EntityActionDefinition enabled(String entityAlias,
                                                 String actionCode,
                                                 String title) {
        return new EntityActionDefinition(entityAlias, actionCode, title, true);
    }

    public EntityActionDefinition availableWhen(String expression) {
        return availableWhen(expression, null);
    }

    public EntityActionDefinition availableWhen(String expression, String message) {
        return new EntityActionDefinition(entityAlias, actionCode, title, enabled, level, category,
                accessMode, actionAuth, dataAuth, defaultGrantPolicy, authInheritActionCode,
                expression, message, executorType, executorKey, titleSource);
    }

    public boolean hasAvailabilityCondition() {
        return availableExpression != null && !availableExpression.isBlank();
    }

    public static EntityActionLevel defaultLevel(String actionCode, EntityActionCategory category) {
        PlatformAction platformAction = PlatformAction.fromCode(actionCode).orElse(null);
        if (platformAction != null) {
            return switch (platformAction.level()) {
                case DEFAULT -> EntityActionLevel.LIST;
                case LIST -> EntityActionLevel.LIST;
                case RECORD -> EntityActionLevel.RECORD;
                case BATCH -> EntityActionLevel.BATCH;
                case ANY -> EntityActionLevel.ANY;
            };
        }
        if (category != EntityActionCategory.STANDARD) {
            return EntityActionLevel.ANY;
        }
        return EntityActionLevel.LIST;
    }

    public static EntityActionCategory defaultCategory(String actionCode) {
        return PlatformAction.fromCode(actionCode).isPresent() ? EntityActionCategory.STANDARD : EntityActionCategory.CUSTOM;
    }

    public static EntityActionExecutorType defaultExecutorType(EntityActionCategory category) {
        return switch (category) {
            case DIALOG -> EntityActionExecutorType.DIALOG;
            case WORKFLOW -> EntityActionExecutorType.WORKFLOW;
            case GENERATE -> EntityActionExecutorType.GENERATE;
            case CUSTOM -> EntityActionExecutorType.SERVICE;
            case STANDARD -> EntityActionExecutorType.STANDARD;
        };
    }
}
