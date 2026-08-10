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
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "mr")).thenReturn(true);
        when(roleService.hasTenantAdministratorAccess("user-1", "tenant-a")).thenReturn(true);
        RoleMenuVisibilityPolicyService service = new RoleMenuVisibilityPolicyService(roleService, tenantApplicationService);

        assertThat(service.canViewModuleMenu(
                "mr.expert",
                Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant-a")))).isTrue();

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
