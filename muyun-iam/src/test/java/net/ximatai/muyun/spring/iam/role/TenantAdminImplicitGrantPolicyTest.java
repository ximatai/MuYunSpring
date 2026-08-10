package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminImplicitGrantPolicyTest {
    @Test
    void shouldGrantOnlyCataloguedActionOfOpenedTenantApplication() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        RoleGrantableActionResolver actionResolver = mock(RoleGrantableActionResolver.class);
        CurrentUser user = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        when(roleService.hasTenantAdministratorAccess("user-1", "tenant-a")).thenReturn(true);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "mr")).thenReturn(true);
        when(actionResolver.resolve(List.of("mr.expert"))).thenReturn(List.of(
                GrantableAction.ofPlatformDefaults("mr.expert", PlatformAction.QUERY)));
        TenantAdminImplicitGrantPolicy policy = new TenantAdminImplicitGrantPolicy(roleService,
                tenantApplicationService, actionResolver);

        assertThat(policy.grants(user, "mr.expert", "query")).isTrue();
        assertThat(policy.grants(user, "mr.expert", "view")).isTrue();
        assertThat(policy.grants(user, "mr.expert", "delete")).isFalse();
    }

    @Test
    void shouldNeverImplicitlyGrantSystemTenantModule() {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        RoleGrantableActionResolver actionResolver = mock(RoleGrantableActionResolver.class);
        CurrentUser user = CurrentUser.tenantUser("user-1", "User", "tenant-a");
        when(roleService.hasTenantAdministratorAccess("user-1", "tenant-a")).thenReturn(true);
        when(tenantApplicationService.isApplicationAvailable("tenant-a", "iam")).thenReturn(true);
        TenantAdminImplicitGrantPolicy policy = new TenantAdminImplicitGrantPolicy(roleService,
                tenantApplicationService, actionResolver);

        assertThat(policy.grants(user, "iam.tenant", "query")).isFalse();

        verify(actionResolver, never()).resolve(List.of("iam.tenant"));
    }
}
