package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

@Component
public class RoleGrantableActionResolver {
    private final PlatformModuleService moduleService;
    private final PlatformModuleActionService moduleActionService;
    private final StaticModuleActionRegistry staticModuleActionRegistry;

    public RoleGrantableActionResolver(PlatformModuleService moduleService,
                                       PlatformModuleActionService moduleActionService) {
        this(moduleService, moduleActionService, new StaticModuleActionRegistry());
    }

    @Autowired
    public RoleGrantableActionResolver(PlatformModuleService moduleService,
                                       PlatformModuleActionService moduleActionService,
                                       StaticModuleActionRegistry staticModuleActionRegistry) {
        this.moduleService = moduleService;
        this.moduleActionService = moduleActionService;
        this.staticModuleActionRegistry = staticModuleActionRegistry == null
                ? new StaticModuleActionRegistry()
                : staticModuleActionRegistry;
    }

    public List<GrantableAction> resolve(List<String> moduleAliases) {
        if (moduleAliases == null || moduleAliases.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, GrantableAction> actions = new LinkedHashMap<>();
        for (String moduleAlias : moduleAliases.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
            if (module == null) {
                continue;
            }
            List<PlatformModuleAction> persistedActions = persistedModuleActions(moduleAlias);
            List<GrantableAction> registeredActions = grantableActions(moduleAlias, persistedActions);
            if (!persistedActions.isEmpty()) {
                for (GrantableAction action : registeredActions) {
                    put(actions, action);
                }
            } else if (module.getModuleKind() == null || module.getModuleKind() == ModuleKind.STATIC) {
                for (PlatformAction action : staticModuleActionRegistry.grantableActions(moduleAlias)) {
                    put(actions, GrantableAction.ofPlatformDefaults(moduleAlias, action));
                }
            }
        }
        return List.copyOf(actions.values());
    }

    private List<PlatformModuleAction> persistedModuleActions(String moduleAlias) {
        return moduleActionService.listByModuleAliases(List.of(moduleAlias)).stream()
                .filter(action -> Boolean.TRUE.equals(action.getEnabled()))
                .toList();
    }

    private List<GrantableAction> grantableActions(String moduleAlias, List<PlatformModuleAction> actions) {
        return actions.stream()
                .filter(PlatformModuleAction::effectiveActionAuth)
                .map(action -> toGrantableAction(moduleAlias, action))
                .toList();
    }

    private GrantableAction toGrantableAction(String moduleAlias, PlatformModuleAction action) {
        Optional<PlatformAction> platformAction = PlatformAction.fromCode(action.getActionCode());
        boolean usesPlatformDefaultTitle = platformAction
                .map(candidate -> candidate.usesDefaultTitle(action.getTitle()))
                .orElse(false);
        return new GrantableAction(
                moduleAlias,
                action.getActionCode(),
                action.getPermissionActionCode(),
                usesPlatformDefaultTitle ? platformAction.orElseThrow().title() : action.getTitle(),
                usesPlatformDefaultTitle ? platformAction.orElseThrow().titleKey() : null,
                action.effectiveActionAuth(),
                moduleActionService.effectiveDataAuth(action)
        );
    }

    private void put(LinkedHashMap<String, GrantableAction> actions, GrantableAction action) {
        String key = action.moduleAlias() + ":" + action.permissionActionCode();
        GrantableAction existing = actions.get(key);
        if (existing == null || action.actionCode().equals(action.permissionActionCode())) {
            actions.put(key, action);
        }
    }
}
