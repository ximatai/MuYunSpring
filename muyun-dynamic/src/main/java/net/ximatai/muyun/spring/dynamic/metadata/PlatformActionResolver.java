package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionGroup;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PlatformActionResolver {
    private PlatformActionResolver() {
    }

    static List<EntityActionDefinition> standardActions(EntityDefinition entity) {
        List<EntityActionDefinition> actions = new ArrayList<>();
        for (PlatformActionGroup group : actionGroupOrder()) {
            if (entity.supports(group.capability())) {
                actions.addAll(standardActions(entity, group));
            }
        }
        return List.copyOf(actions);
    }

    private static List<EntityActionDefinition> standardActions(EntityDefinition entity, PlatformActionGroup group) {
        var contribution = CapabilityModuleRegistry.defaultRegistry().find(group.capability())
                .map(module -> module.actionContribution());
        if (contribution.isPresent()) return contribution.get().standardActions().stream().map(action -> action(entity, action)).toList();
        return PlatformAction.ofGroup(group).stream()
                .map(action -> action(entity, action))
                .toList();
    }

    private static EntityActionDefinition action(EntityDefinition entity, PlatformAction action) {
        EntityActionLevel level = toLevel(action.level());
        return new EntityActionDefinition(entity.alias(), action.code(),
                action.title(), true, level,
                null, toAccessMode(action.accessMode()), action.actionAuth(),
                dataAuth(entity, action),
                action.defaultGrantPolicy(), action.inheritActionCode(),
                null, null, null, null);
    }

    private static boolean dataAuth(EntityDefinition entity, PlatformAction action) {
        if (!action.dataAuth()) {
            return false;
        }
        return entity.supports(EntityCapability.DATA_SCOPE) || action == PlatformAction.EXPORT;
    }

    private static List<PlatformActionGroup> actionGroupOrder() {
        return Arrays.asList(PlatformActionGroup.values());
    }

    private static EntityActionLevel toLevel(PlatformActionLevel value) {
        return switch (value) {
            case DEFAULT -> null;
            case LIST -> EntityActionLevel.LIST;
            case RECORD -> EntityActionLevel.RECORD;
            case BATCH -> EntityActionLevel.BATCH;
            case ANY -> EntityActionLevel.ANY;
        };
    }

    private static EntityActionAccessMode toAccessMode(ActionAccessMode value) {
        return switch (value) {
            case AUTH_REQUIRED -> EntityActionAccessMode.AUTH_REQUIRED;
            case LOGIN_REQUIRED -> EntityActionAccessMode.LOGIN_REQUIRED;
            case ANONYMOUS_ALLOWED -> EntityActionAccessMode.ANONYMOUS_ALLOWED;
        };
    }
}
