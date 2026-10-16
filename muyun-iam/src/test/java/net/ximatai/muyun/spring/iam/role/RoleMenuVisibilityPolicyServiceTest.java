package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleMenuVisibilityPolicyServiceTest {
    @Test
    void loginRequiredPersonalMenuNeedsNoManagementRoleButStillRequiresOpenedApplication() {
        var roles = mock(RoleService.class);
        var applications = mock(TenantApplicationService.class);
        var actions = mock(net.ximatai.muyun.spring.platform.module.PlatformModuleActionService.class);
        var menu = new net.ximatai.muyun.spring.platform.module.PlatformModuleAction();
        menu.setActionCode("menu");
        menu.setAccessMode(net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.LOGIN_REQUIRED);
        menu.setActionAuth(false);
        menu.setDataAuth(false);
        when(actions.findByModuleAliasAndActionCode("iam.workflow_workbench", "menu")).thenReturn(menu);
        when(applications.isApplicationAvailable("tenant-a", "iam")).thenReturn(true);
        var service = new RoleMenuVisibilityPolicyService(roles, applications, null, actions);
        var user = Optional.of(CurrentUser.tenantUser("ordinary", "User", "tenant-a"));

        assertThat(service.canViewModuleMenu("iam.workflow_workbench", user)).isTrue();
        assertThat(service.canViewModuleMenu("iam.role", user)).isFalse();
        assertThat(service.canViewModuleMenu("platform.workflow_admin", user)).isFalse();
        assertThat(service.canViewModuleMenu("iam.workflow_workbench", Optional.empty())).isFalse();
        verify(roles, never()).hasActionPermission("ordinary", "iam.workflow_workbench", "menu");
        menu.setEnabled(false);
        assertThat(service.canViewModuleMenu("iam.workflow_workbench", user)).isFalse();
        menu.setEnabled(true);
        menu.setAccessModeOverride(net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.AUTH_REQUIRED);
        assertThat(service.canViewModuleMenu("iam.workflow_workbench", user)).isFalse();
        menu.setAccessModeOverride(null);
        when(applications.isApplicationAvailable("tenant-a", "iam")).thenReturn(false);
        assertThat(service.canViewModuleMenu("iam.workflow_workbench", user)).isFalse();
    }

    @Test
    void shouldUseMenuActionPermissionForModuleMenuVisibility() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        when(roleService.hasActionPermission("user-1", "crm.customer", "menu")).thenReturn(true);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "crm")).thenReturn(true);
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(roleService, tenantApplicationService);

        assertThat(service.canViewModuleMenu(
                "crm.customer",
                Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant-a")))).isTrue();
        assertThat(service.canViewModuleMenu(
                "crm.contract",
                Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant-a")))).isFalse();
        verify(roleService).hasActionPermission("user-1", "crm.customer", "menu");
    }

    @Test
    void shouldNotTreatViewPermissionAsMenuVisibilityPermission() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        when(roleService.hasActionPermission("user-1", "crm.customer", "view")).thenReturn(true);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "crm")).thenReturn(true);
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(roleService, tenantApplicationService);

        assertThat(service.canViewModuleMenu(
                "crm.customer",
                Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant-a")))).isFalse();
    }

    @Test
    void shouldShowOpenedApplicationMenuToTenantAdministratorWithoutMenuGrant() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        TenantAdminImplicitGrantPolicy tenantAdminPolicy = mock(TenantAdminImplicitGrantPolicy.class);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "mr")).thenReturn(true);
        CurrentUser user = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        when(tenantAdminPolicy.grants(user, "mr.expert", "menu")).thenReturn(true);
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(roleService,
                tenantApplicationService, tenantAdminPolicy);

        assertThat(service.canViewModuleMenu(
                "mr.expert",
                Optional.of(user))).isTrue();

        verify(roleService, never()).hasActionPermission("user-1", "mr.expert", "menu");
    }

    @Test
    void shouldKeepSystemUserVisibleAndAnonymousHidden() {
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(mock(RoleService.class));

        assertThat(service.canViewModuleMenu(
                "crm.customer",
                Optional.of(CurrentUser.systemUser("system", "System")))).isTrue();
        assertThat(service.canViewModuleMenu("crm.customer", Optional.empty())).isFalse();
    }

    @Test
    void shouldHideTenantMenuWhenItsApplicationIsNotEnabled() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "crm")).thenReturn(false);
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(roleService, tenantApplicationService);

        assertThat(service.canViewModuleMenu(
                "crm.customer",
                Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant-a")))).isFalse();
        verify(roleService, never()).hasActionPermission("user-1", "crm.customer", "menu");
    }
}
