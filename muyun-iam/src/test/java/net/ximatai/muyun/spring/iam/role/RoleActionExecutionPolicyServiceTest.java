package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.ApplicationNotOpenedException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleActionExecutionPolicyServiceTest {
    @Test
    void shouldAllowSystemUserWithoutRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(context(CurrentUser.systemUser("system", "System")));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_SYSTEM_USER);
        assertThat(result.operatorId()).isEqualTo("system");
        assertThat(result.operatorType()).isEqualTo(ActionAuthorizationResult.OPERATOR_SYSTEM);
        verify(roleService, never()).hasActionPermission("system", "sales.contract", "view");
    }

    @Test
    void shouldAllowWhenRoleActionIsEnabled() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.hasActionPermission("user-1", "sales.contract", "view")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(context(CurrentUser.tenantUser("user-1", "Alice", "tenant_a")));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ROLE_GRANTED);
        assertThat(result.operatorId()).isEqualTo("user-1");
        assertThat(result.operatorType()).isEqualTo(ActionAuthorizationResult.OPERATOR_USER);
        verify(roleService).hasActionPermission("user-1", "sales.contract", "view");
    }

    @Test
    void shouldAllowTenantAdministratorForNewlyOpenedApplicationWithoutRoleAction() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        TenantAdminImplicitGrantPolicy tenantAdminPolicy = mock(TenantAdminImplicitGrantPolicy.class);
        CurrentUser user = CurrentUser.tenantUser("user-1", "Alice", "tenant_a");
        when(tenantAdminPolicy.grants(user, "sales.contract", PlatformAction.QUERY.executionPolicy())).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService,
                tenantApplicationService, tenantAdminPolicy);

        ActionAuthorizationResult result = policy.authorize(context(user));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_TENANT_ADMIN_GRANTED);
        verify(tenantApplicationService).requireApplicationOpened("tenant_a", "sales");
        verify(tenantAdminPolicy).grants(user, "sales.contract", PlatformAction.QUERY.executionPolicy());
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "view");
    }

    @Test
    void shouldRejectTenantAdministratorBeforePrivilegeLookupWhenApplicationIsNotOpened() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        TenantAdminImplicitGrantPolicy tenantAdminPolicy = mock(TenantAdminImplicitGrantPolicy.class);
        org.mockito.Mockito.doThrow(new ApplicationNotOpenedException("tenant_a", "sales"))
                .when(tenantApplicationService).requireApplicationOpened("tenant_a", "sales");
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService,
                tenantApplicationService, tenantAdminPolicy);

        assertThatThrownBy(() -> policy.authorize(context(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))))
                .isInstanceOf(ApplicationNotOpenedException.class);

        verify(tenantAdminPolicy, never()).grants(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldNotImplicitlyGrantSystemTenantModuleToTenantAdministrator() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        RoleGrantableActionResolver actionResolver = mock(RoleGrantableActionResolver.class);
        CurrentUser user = CurrentUser.tenantUser("user-1", "Alice", "tenant_a");
        when(roleService.hasTenantAdministratorAccess("user-1", "tenant_a")).thenReturn(true);
        when(tenantApplicationService.isApplicationAvailable("tenant_a", "iam")).thenReturn(true);
        TenantAdminImplicitGrantPolicy tenantAdminPolicy = new TenantAdminImplicitGrantPolicy(roleService,
                tenantApplicationService, actionResolver);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService,
                tenantApplicationService, tenantAdminPolicy);

        assertThatThrownBy(() -> policy.authorize(ActionExecutionContext.ofActionCode(
                "iam.tenant", "query", Set.of(), Optional.of(user))))
                .isInstanceOf(PlatformAccessDeniedException.class);

        verify(actionResolver, never()).resolve(java.util.List.of("iam.tenant"));
    }

    @Test
    void shouldRejectTenantActionBeforeRoleLookupWhenApplicationIsNotOpened() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        org.mockito.Mockito.doThrow(new ApplicationNotOpenedException("tenant_a", "sales"))
                .when(tenantApplicationService).requireApplicationOpened("tenant_a", "sales");
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService,
                tenantApplicationService);

        assertThatThrownBy(() -> policy.authorize(context(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))))
                .isInstanceOf(ApplicationNotOpenedException.class)
                .satisfies(cause -> assertThat(((ApplicationNotOpenedException) cause).code())
                        .isEqualTo("APPLICATION_NOT_OPENED"));

        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "view");
    }

    @Test
    void shouldCheckApplicationOpeningBeforeGrantingLoginOnlyAction() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService,
                tenantApplicationService);

        policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("profile", PlatformActionLevel.RECORD,
                        ActionAccessMode.LOGIN_REQUIRED, false, false, ActionDefaultGrantPolicy.NONE, null),
                Set.of("contract-1"),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        verify(tenantApplicationService).requireApplicationOpened("tenant_a", "sales");
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "profile");
    }

    @Test
    void shouldUseActingPrincipalForRoleLookupWhenActingContextMatchesAction() {
        RoleService roleService = mock(RoleService.class);
        BusinessPrincipal principal = BusinessPrincipal.employee("employee-1", "org-1", "dept-1");
        when(roleService.hasActionPermission(principal, "sales.contract", "view")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        try (ActingContextHolder.Scope ignored = ActingContextHolder.use(new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                principal,
                "sales.contract",
                "query"))) {
            ActionAuthorizationResult result = policy.authorize(context(
                    CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a")));

            assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ROLE_GRANTED);
        }

        verify(roleService).hasActionPermission(principal, "sales.contract", "view");
        verify(roleService, never()).hasActionPermission("assistant-user", "sales.contract", "view");
    }

    @Test
    void shouldIgnoreActingPrincipalWhenActingContextDoesNotMatchAction() {
        RoleService roleService = mock(RoleService.class);
        BusinessPrincipal principal = BusinessPrincipal.employee("employee-1", "org-1", "dept-1");
        when(roleService.hasActionPermission("assistant-user", "sales.contract", "view")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        try (ActingContextHolder.Scope ignored = ActingContextHolder.use(new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                principal,
                "sales.contract",
                "create"))) {
            ActionAuthorizationResult result = policy.authorize(context(
                    CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a")));

            assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ROLE_GRANTED);
        }

        verify(roleService, never()).hasActionPermission(principal, "sales.contract", "view");
        verify(roleService).hasActionPermission("assistant-user", "sales.contract", "view");
    }

    @Test
    void shouldDenyWhenMatchingActingPrincipalIsNotGrantedEvenIfOperatorIsGranted() {
        RoleService roleService = mock(RoleService.class);
        BusinessPrincipal principal = BusinessPrincipal.employee("employee-1", "org-1", "dept-1");
        when(roleService.hasActionPermission("assistant-user", "sales.contract", "view")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        try (ActingContextHolder.Scope ignored = ActingContextHolder.use(new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                principal,
                "sales.contract",
                "query"))) {
            assertThatThrownBy(() -> policy.authorize(context(
                    CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))))
                    .isInstanceOf(PlatformAccessDeniedException.class)
                    .hasMessageContaining("sales.contract:view");
        }

        verify(roleService).hasActionPermission(principal, "sales.contract", "view");
        verify(roleService, never()).hasActionPermission("assistant-user", "sales.contract", "view");
    }

    @Test
    void shouldRejectWhenActingOperatorDoesNotMatchCurrentUser() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        try (ActingContextHolder.Scope ignored = ActingContextHolder.use(new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("other-user", "Other", "tenant_a"),
                BusinessPrincipal.employee("employee-1", "org-1", "dept-1"),
                "sales.contract",
                "query"))) {
            assertThatThrownBy(() -> policy.authorize(context(
                    CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))))
                    .isInstanceOf(PlatformAccessDeniedException.class)
                    .hasMessageContaining("operator does not match");
        }

        verify(roleService, never()).hasActionPermission("assistant-user", "sales.contract", "view");
    }

    @Test
    void shouldRejectWhenNoRoleActionMatches() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        assertThatThrownBy(() -> policy.requireAuthorized(context(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))))
                .isInstanceOf(PlatformAccessDeniedException.class)
                .hasMessageContaining("sales.contract:view");
    }

    @Test
    void shouldRejectAnonymousAction() {
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(mock(RoleService.class));

        assertThatThrownBy(() -> policy.requireAuthorized(ActionExecutionContext.ofActionCode(
                "sales.contract",
                "query",
                Set.of(),
                Optional.empty()
        ))).isInstanceOf(AuthenticationRequiredException.class)
                .hasMessageContaining("current user");
    }

    @Test
    void shouldAllowAnonymousPolicyWithoutCurrentUser() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("publicSearch", PlatformActionLevel.LIST,
                        ActionAccessMode.ANONYMOUS_ALLOWED, false, false, ActionDefaultGrantPolicy.NONE, null),
                Set.of(),
                Optional.empty()
        ));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ANONYMOUS_ALLOWED);
        assertThat(result.operatorType()).isEqualTo(ActionAuthorizationResult.OPERATOR_ANONYMOUS);
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "publicSearch");
    }

    @Test
    void shouldAllowLoginOnlyPolicyWithoutRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("profile", PlatformActionLevel.RECORD,
                        ActionAccessMode.LOGIN_REQUIRED, false, false, ActionDefaultGrantPolicy.NONE, null),
                Set.of("contract-1"),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_LOGIN_REQUIRED);
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "profile");
    }

    @Test
    void shouldAllowActionAuthDisabledPolicyWithoutRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("summary", PlatformActionLevel.LIST,
                        ActionAccessMode.AUTH_REQUIRED, false, false, ActionDefaultGrantPolicy.NONE, null),
                Set.of(),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ACTION_AUTH_DISABLED);
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "summary");
    }

    @Test
    void shouldUseInheritedActionCodeForRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.hasActionPermission("user-1", "sales.contract", "view")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("export", PlatformActionLevel.LIST,
                        ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, "view"),
                Set.of(),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        assertThat(result.permissionCode()).isEqualTo("sales.contract:view");
        assertThat(result.permissionActionCode()).isEqualTo("view");
        verify(roleService).hasActionPermission("user-1", "sales.contract", "view");
    }

    @Test
    void shouldAuthorizeMenuActionWithMenuPermissionInsteadOfViewPermission() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.hasActionPermission("user-1", "sales.contract", "menu")).thenReturn(true);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPlatformAction(
                "sales.contract",
                PlatformAction.MENU,
                Set.of(),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        assertThat(result.permissionCode()).isEqualTo("sales.contract:menu");
        assertThat(result.permissionActionCode()).isEqualTo("menu");
        verify(roleService).hasActionPermission("user-1", "sales.contract", "menu");
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "view");
    }

    @Test
    void shouldAllowDefaultAuthenticatedGrantWithoutRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        RoleActionExecutionPolicyService policy = new RoleActionExecutionPolicyService(roleService);

        ActionAuthorizationResult result = policy.authorize(ActionExecutionContext.ofPolicy(
                "sales.contract",
                new ActionExecutionPolicy("follow", PlatformActionLevel.RECORD,
                        ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.OWNER, null),
                Set.of("contract-1"),
                Optional.of(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))
        ));

        assertThat(result.decision()).isEqualTo(RoleActionExecutionPolicyService.DECISION_ACTION_DEFAULT_GRANT);
        verify(roleService, never()).hasActionPermission("user-1", "sales.contract", "follow");
    }

    private ActionExecutionContext context(CurrentUser user) {
        return ActionExecutionContext.ofActionCode(
                "sales.contract",
                "query",
                Set.of("contract-1"),
                Optional.of(user)
        );
    }
}
