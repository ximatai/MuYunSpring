package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoleActionExecutionPolicyService implements ActionExecutionPolicyService {
    public static final String DECISION_ANONYMOUS_ALLOWED = "ANONYMOUS_ALLOWED";
    public static final String DECISION_SYSTEM_USER = "SYSTEM_USER";
    public static final String DECISION_LOGIN_REQUIRED = "LOGIN_REQUIRED";
    public static final String DECISION_ACTION_AUTH_DISABLED = "ACTION_AUTH_DISABLED";
    public static final String DECISION_ACTION_DEFAULT_GRANT = "ACTION_DEFAULT_GRANT";
    public static final String DECISION_TENANT_ADMIN_GRANTED = "TENANT_ADMIN_GRANTED";
    public static final String DECISION_ROLE_GRANTED = "ROLE_GRANTED";

    private final RoleService roleService;
    private final TenantApplicationService tenantApplicationService;

    public RoleActionExecutionPolicyService(RoleService roleService) {
        this(roleService, null);
    }

    @Autowired
    public RoleActionExecutionPolicyService(RoleService roleService,
                                            TenantApplicationService tenantApplicationService) {
        this.roleService = roleService;
        this.tenantApplicationService = tenantApplicationService;
    }

    @Override
    public void requireAuthorized(ActionExecutionContext context) {
        authorize(context);
    }

    @Override
    public ActionAuthorizationResult authorize(ActionExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (context.actionPolicy().accessMode() == ActionAccessMode.ANONYMOUS_ALLOWED) {
            return ActionAuthorizationResult.allowed(context, DECISION_ANONYMOUS_ALLOWED);
        }
        CurrentUser currentUser = context.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("action requires current user"));
        if (currentUser.system()) {
            return ActionAuthorizationResult.allowed(context, DECISION_SYSTEM_USER);
        }
        requireOpenedApplication(currentUser, context.moduleAlias());
        if (context.actionPolicy().accessMode() == ActionAccessMode.LOGIN_REQUIRED) {
            return ActionAuthorizationResult.allowed(context, DECISION_LOGIN_REQUIRED);
        }
        if (!context.actionPolicy().actionAuth()) {
            return ActionAuthorizationResult.allowed(context, DECISION_ACTION_AUTH_DISABLED);
        }
        if (grantsAuthenticatedUser(context.actionPolicy().defaultGrantPolicy())) {
            return ActionAuthorizationResult.allowed(context, DECISION_ACTION_DEFAULT_GRANT);
        }
        if (isDirectTenantAdministrator(currentUser, context)) {
            return ActionAuthorizationResult.allowed(context, DECISION_TENANT_ADMIN_GRANTED);
        }
        String permissionActionCode = context.actionPolicy().permissionActionCode();
        ActingContext actingContext = ActingContextHolder.current()
                .filter(acting -> acting.matches(context.moduleAlias(), context.actionCode()))
                .orElse(null);
        if (actingContext != null) {
            if (!currentUser.userId().equals(actingContext.operator().userId())) {
                throw new PlatformAccessDeniedException("acting context operator does not match current user");
            }
            if (roleService.hasActionPermission(actingContext.principal(), context.moduleAlias(), permissionActionCode)) {
                return ActionAuthorizationResult.allowed(context, DECISION_ROLE_GRANTED);
            }
            throw new PlatformAccessDeniedException("action permission denied: " + context.permissionCode());
        }
        if (roleService.hasActionPermission(currentUser.userId(), context.moduleAlias(), permissionActionCode)) {
            return ActionAuthorizationResult.allowed(context, DECISION_ROLE_GRANTED);
        }
        throw new PlatformAccessDeniedException("action permission denied: " + context.permissionCode());
    }

    private boolean grantsAuthenticatedUser(ActionDefaultGrantPolicy policy) {
        return policy != null && policy.grantsAuthenticatedUser();
    }

    private boolean isDirectTenantAdministrator(CurrentUser currentUser, ActionExecutionContext context) {
        if (ActingContextHolder.current().filter(acting -> acting.matches(context.moduleAlias(), context.actionCode()))
                .isPresent()) {
            return false;
        }
        return roleService.hasTenantAdministratorAccess(currentUser.userId(), currentUser.tenantId());
    }

    private void requireOpenedApplication(CurrentUser currentUser, String moduleAlias) {
        if (tenantApplicationService == null) {
            return;
        }
        String tenantId = currentUser.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new PlatformAccessDeniedException("tenant user requires tenant context");
        }
        tenantApplicationService.requireApplicationOpened(tenantId,
                PlatformNameRules.applicationAliasOfModuleAlias(moduleAlias));
    }
}
