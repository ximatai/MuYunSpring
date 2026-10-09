package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.role.RoleActionGrantVerifier;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class PlatformRoleActionGrantVerifier implements RoleActionGrantVerifier {
    private final PlatformModuleService moduleService;
    private final PlatformModuleActionService moduleActionService;
    private final StaticModuleActionRegistry staticModuleActionRegistry;

    public PlatformRoleActionGrantVerifier(PlatformModuleService moduleService,
                                           PlatformModuleActionService moduleActionService) {
        this(moduleService, moduleActionService, new StaticModuleActionRegistry());
    }

    @Autowired
    public PlatformRoleActionGrantVerifier(PlatformModuleService moduleService,
                                           PlatformModuleActionService moduleActionService,
                                           StaticModuleActionRegistry staticModuleActionRegistry) {
        this.moduleService = moduleService;
        this.moduleActionService = moduleActionService;
        this.staticModuleActionRegistry = staticModuleActionRegistry == null
                ? new StaticModuleActionRegistry()
                : staticModuleActionRegistry;
    }

    @Override
    public String resolveGrantablePermissionActionCode(String moduleAlias, String actionCode) {
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null) {
            throw new PlatformException("role action requires existing module: " + moduleAlias);
        }
        List<PlatformModuleAction> persistedActions = persistedModuleActions(moduleAlias);
        List<PlatformModuleAction> registeredActions = grantableActions(persistedActions);
        if (!registeredActions.isEmpty()) {
            return registeredActions.stream()
                    .filter(action -> actionCode.equals(action.getActionCode()))
                    .map(this::permissionActionCode)
                    .findFirst()
                    .orElseThrow(() -> new PlatformException("role action requires configured module action: "
                            + moduleAlias + "." + actionCode));
        }
        if (persistedActions.isEmpty() && (module.getModuleKind() == null || module.getModuleKind() == ModuleKind.STATIC)) {
            return PlatformAction.fromCode(actionCode)
                    .filter(action -> staticModuleActionRegistry.isGrantable(moduleAlias, action))
                    .map(PlatformAction::permissionActionCode)
                    .orElseThrow(() -> new PlatformException("role action requires configured module action: "
                            + moduleAlias + "." + actionCode));
        }
        throw new PlatformException("role action requires configured module action: "
                + moduleAlias + "." + actionCode);
    }

    @Override
    public boolean requiresDataScope(String moduleAlias, String actionCode) {
        PlatformModule module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null) {
            throw new PlatformException("role action requires existing module: " + moduleAlias);
        }
        List<PlatformModuleAction> persistedActions = persistedModuleActions(moduleAlias);
        List<PlatformModuleAction> registeredActions = grantableActions(persistedActions);
        if (!registeredActions.isEmpty()) {
            return registeredActions.stream()
                    .filter(action -> actionCode.equals(action.getActionCode()))
                    .map(moduleActionService::effectiveDataAuth)
                    .findFirst()
                    .orElseThrow(() -> new PlatformException("role action requires configured module action: "
                            + moduleAlias + "." + actionCode));
        }
        if (persistedActions.isEmpty() && (module.getModuleKind() == null || module.getModuleKind() == ModuleKind.STATIC)) {
            return PlatformAction.fromCode(actionCode)
                    .filter(action -> staticModuleActionRegistry.isGrantable(moduleAlias, action))
                    .map(PlatformAction::dataAuth)
                    .orElseThrow(() -> new PlatformException("role action requires configured module action: "
                            + moduleAlias + "." + actionCode));
        }
        throw new PlatformException("role action requires configured module action: "
                + moduleAlias + "." + actionCode);
    }

    private List<PlatformModuleAction> persistedModuleActions(String moduleAlias) {
        return moduleActionService.listByModuleAliases(List.of(moduleAlias)).stream()
                .filter(action -> Boolean.TRUE.equals(action.getEnabled()))
                .toList();
    }

    private List<PlatformModuleAction> grantableActions(List<PlatformModuleAction> actions) {
        return actions.stream()
                .filter(PlatformModuleAction::effectiveActionAuth)
                .toList();
    }

    private String permissionActionCode(PlatformModuleAction action) {
        String permissionActionCode = action.getPermissionActionCode();
        return permissionActionCode == null || permissionActionCode.isBlank()
                ? Objects.requireNonNull(action.getActionCode(), "actionCode must not be null")
                : permissionActionCode;
    }
}
