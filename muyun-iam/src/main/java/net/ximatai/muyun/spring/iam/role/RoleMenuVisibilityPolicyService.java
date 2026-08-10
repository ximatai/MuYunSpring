package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.MenuVisibilityPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RoleMenuVisibilityPolicyService implements MenuVisibilityPolicyService {
    private final RoleService roleService;
    private final TenantApplicationService tenantApplicationService;

    public RoleMenuVisibilityPolicyService(RoleService roleService) {
        this(roleService, null);
    }

    @Autowired
    public RoleMenuVisibilityPolicyService(RoleService roleService,
                                            TenantApplicationService tenantApplicationService) {
        this.roleService = roleService;
        this.tenantApplicationService = tenantApplicationService;
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
        return roleService.hasTenantAdministratorAccess(user.userId(), user.tenantId())
                || roleService.hasActionPermission(user.userId(), moduleAlias, PlatformAction.MENU.code());
    }

    private String applicationAliasOf(String moduleAlias) {
        try {
            return PlatformNameRules.applicationAliasOfModuleAlias(moduleAlias);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }
}
