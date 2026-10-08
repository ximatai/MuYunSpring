package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.MenuVisibilityPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RoleMenuVisibilityPolicyService implements MenuVisibilityPolicyService {
    private final RoleService roleService;
    private final TenantApplicationService tenantApplicationService;
    private final TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy;
    private final PlatformModuleActionService moduleActions;

    public RoleMenuVisibilityPolicyService(RoleService roleService) {
        this(roleService, null, null);
    }

    public RoleMenuVisibilityPolicyService(RoleService roleService,
                                            TenantApplicationService tenantApplicationService) {
        this(roleService, tenantApplicationService, null);
    }

    public RoleMenuVisibilityPolicyService(RoleService roleService,
                                            TenantApplicationService tenantApplicationService,
                                            TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy) {
        this(roleService, tenantApplicationService, tenantAdminImplicitGrantPolicy, null);
    }

    @Autowired
    public RoleMenuVisibilityPolicyService(RoleService roleService,
                                            TenantApplicationService tenantApplicationService,
                                            TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy,
                                            PlatformModuleActionService moduleActions) {
        this.roleService = roleService;
        this.tenantApplicationService = tenantApplicationService;
        this.tenantAdminImplicitGrantPolicy = tenantAdminImplicitGrantPolicy;
        this.moduleActions = moduleActions;
    }

    @Override
    public boolean canViewModuleMenu(String moduleAlias, Optional<CurrentUser> currentUser) {
        CurrentUser user = currentUser.orElse(null);
        if (user == null) {
            return false;
        }
        if (user.system()) {
            return true;
        }
        String applicationAlias = applicationAliasOf(moduleAlias);
        if (applicationAlias == null || user.tenantId() == null || user.tenantId().isBlank()
                || tenantApplicationService == null
                || !tenantApplicationService.isApplicationAvailable(user.tenantId(), applicationAlias)) {
            return false;
        }
        return hasLoginRequiredMenu(moduleAlias) || (tenantAdminImplicitGrantPolicy != null
                && tenantAdminImplicitGrantPolicy.grants(user, moduleAlias, PlatformAction.MENU.code()))
                || roleService.hasActionPermission(user.userId(), moduleAlias, PlatformAction.MENU.code());
    }

    private boolean hasLoginRequiredMenu(String moduleAlias) {
        if (moduleActions == null) return false;
        var action = moduleActions.findByModuleAliasAndActionCode(moduleAlias, PlatformAction.MENU.code());
        return action != null && !Boolean.FALSE.equals(action.getEnabled())
                && action.executionPolicy().accessMode() == ActionAccessMode.LOGIN_REQUIRED;
    }

    private String applicationAliasOf(String moduleAlias) {
        try {
            return PlatformNameRules.applicationAliasOfModuleAlias(moduleAlias);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }
}
