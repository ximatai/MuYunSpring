package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Narrow policy for the platform-managed tenant-admin implicit grant.
 *
 * <p>It deliberately does not become a second role model or a generic module-scope framework.
 * The existing action catalog remains the source of grantable actions; the policy only centralizes
 * the additional eligibility boundary required by this privileged identity.</p>
 */
@Service
public class TenantAdminImplicitGrantPolicy {
    private static final Set<String> SYSTEM_TENANT_MODULES = Set.of(TenantService.MODULE_ALIAS);

    private final RoleService roleService;
    private final TenantApplicationService tenantApplicationService;
    private final RoleGrantableActionResolver grantableActionResolver;

    public TenantAdminImplicitGrantPolicy(RoleService roleService,
                                          TenantApplicationService tenantApplicationService,
                                          RoleGrantableActionResolver grantableActionResolver) {
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.tenantApplicationService = Objects.requireNonNull(tenantApplicationService,
                "tenantApplicationService must not be null");
        this.grantableActionResolver = Objects.requireNonNull(grantableActionResolver,
                "grantableActionResolver must not be null");
    }

    public boolean grants(CurrentUser user, String moduleAlias, String actionCode) {
        if (user == null || user.system() || user.tenantId() == null || user.tenantId().isBlank()
                || moduleAlias == null || moduleAlias.isBlank() || actionCode == null || actionCode.isBlank()) {
            return false;
        }
        if (!roleService.hasTenantAdministratorAccess(user.userId(), user.tenantId())) {
            return false;
        }
        String applicationAlias;
        try {
            applicationAlias = PlatformNameRules.applicationAliasOfModuleAlias(moduleAlias);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!tenantApplicationService.isApplicationAvailable(user.tenantId(), applicationAlias)
                || SYSTEM_TENANT_MODULES.contains(moduleAlias)) {
            return false;
        }
        return grantableActionResolver.resolve(List.of(moduleAlias)).stream()
                .anyMatch(action -> actionCode.equals(action.actionCode())
                        || actionCode.equals(action.permissionActionCode()));
    }
}
