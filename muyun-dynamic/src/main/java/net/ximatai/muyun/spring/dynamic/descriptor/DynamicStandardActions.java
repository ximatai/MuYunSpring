package net.ximatai.muyun.spring.dynamic.descriptor;

import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityStandardActionCatalog;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DynamicStandardActions {
    private DynamicStandardActions() {
    }

    static List<DynamicActionDescriptor> from(String moduleAlias,
                                              EntityDefinition entity,
                                              List<EntityActionDefinition> configuredActions) {
        Map<String, DynamicActionDescriptor> actions = new LinkedHashMap<>();
        for (EntityActionDefinition standard : EntityStandardActionCatalog.from(entity)) {
            if (isHttpOnly(standard.actionCode())) continue;
            put(actions, action(moduleAlias, entity.alias(), standard, null));
        }
        for (EntityActionDefinition configured : configuredActions) {
            if (entity.alias().equals(configured.entityAlias()) && !isHttpOnly(configured.actionCode())) {
                actions.put(configured.actionCode(), action(moduleAlias, entity.alias(), configured,
                        actions.get(configured.actionCode())));
            }
        }
        return List.copyOf(actions.values());
    }

    private static DynamicActionDescriptor action(String moduleAlias,
                                                  String entityAlias,
                                                  EntityActionDefinition configured,
                                                  DynamicActionDescriptor standard) {
        EntityActionDefinition execution = standard == null ? configured : null;
        DynamicActionDescriptor descriptor = new DynamicActionDescriptor(
                configured.actionCode(),
                configured.title(),
                configured.enabled(),
                standard == null ? configured.level() : standard.actionLevel(),
                standard == null ? configured.category() : standard.category(),
                standard == null ? configured.accessMode() : standard.accessMode(),
                standard == null ? configured.actionAuth() : standard.actionAuth(),
                standard == null ? configured.dataAuth() : standard.dataAuth(),
                configured.defaultGrantPolicy(),
                standard == null ? configured.authInheritActionCode() : standard.authInheritActionCode(),
                configured.hasAvailabilityCondition(),
                configured.unavailableMessage(),
                execution == null ? standard.executorType() : execution.executorType(),
                execution == null ? standard.executorKey() : execution.executorKey()
        );
        if (moduleAlias == null) {
            return descriptor;
        }
        return descriptor.withPermission(moduleAlias);
    }

    private static void put(Map<String, DynamicActionDescriptor> actions, DynamicActionDescriptor action) {
        actions.put(action.code(), action);
    }

    private static boolean isHttpOnly(String actionCode) {
        return PlatformAction.fromCode(actionCode)
                .flatMap(action -> CapabilityModuleRegistry.defaultRegistry().actionOwner(action)
                        .map(contribution -> contribution.isHttpOnlyDynamicAction(action)))
                .orElse(false);
    }

}
