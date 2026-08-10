package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class BuiltInRolePermissionTemplateService {
    public static final String TENANT_ADMIN_TEMPLATE_ALIAS = "tenant.admin";
    public static final String ORGANIZATION_ADMIN_TEMPLATE_ALIAS = "organization.admin";
    /**
     * @deprecated Tenant-admin access is no longer materialized as role-action rows. Retained
     * only as a source-compatible catalog for callers migrating to the platform role purpose.
     */
    @Deprecated(forRemoval = false)
    public static final List<String> TENANT_ADMIN_MODULE_ALIASES = List.of(
            OrganizationService.MODULE_ALIAS,
            DepartmentService.MODULE_ALIAS,
            EmployeeService.MODULE_ALIAS,
            EmployeeAccountService.MODULE_ALIAS,
            UserAccountService.MODULE_ALIAS,
            RoleService.MODULE_ALIAS
    );
    public static final List<String> ORGANIZATION_ADMIN_MODULE_ALIASES = List.of(
            OrganizationService.MODULE_ALIAS,
            DepartmentService.MODULE_ALIAS,
            EmployeeService.MODULE_ALIAS,
            UserAccountService.MODULE_ALIAS
    );
    private static final Set<String> ORGANIZATION_ADMIN_EXCLUDED_ACTIONS = Set.of(
            actionKey(EmployeeService.MODULE_ALIAS, "employeeAccounts")
    );

    private final RoleService roleService;
    private final RoleGrantableActionResolver grantableActionResolver;

    public BuiltInRolePermissionTemplateService(RoleService roleService,
                                                RoleGrantableActionResolver grantableActionResolver) {
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.grantableActionResolver = Objects.requireNonNull(grantableActionResolver,
                "grantableActionResolver must not be null");
    }

    /**
     * @deprecated Tenant-admin access is interpreted at runtime from {@link RoleSystemPurpose}.
     * This method does not participate in authorization correctness.
     */
    @Deprecated(forRemoval = false)
    public int applyTenantAdminTemplate(String roleId) {
        return applyTemplate(roleId, TENANT_ADMIN_MODULE_ALIASES, DataScopePolicy.NONE, Set.of());
    }

    public int applyOrganizationAdminTemplate(String roleId) {
        return applyTemplate(roleId, ORGANIZATION_ADMIN_MODULE_ALIASES, DataScopePolicy.NONE,
                ORGANIZATION_ADMIN_EXCLUDED_ACTIONS);
    }

    private int applyTemplate(String roleId,
                              List<String> moduleAliases,
                              DataScopePolicy dataScopePolicy,
                              Set<String> excludedActions) {
        int changed = 0;
        for (GrantableAction action : grantableActionResolver.resolve(moduleAliases)) {
            if (excludedActions.contains(actionKey(action.moduleAlias(), action.permissionActionCode()))) {
                continue;
            }
            changed += roleService.grantAction(
                    roleId,
                    action.moduleAlias(),
                    action.actionCode(),
                    action.dataAuth() ? dataScopePolicy : DataScopePolicy.NONE,
                    TenantScopePolicy.CURRENT_TENANT
            );
        }
        return changed;
    }

    private static String actionKey(String moduleAlias, String actionCode) {
        return moduleAlias + ":" + actionCode;
    }
}
