package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTenantRoleProvisionerTest {
    @Test
    void shouldProvisionTenantAdminRoleWithoutMaterializingActionGrants() {
        RoleService roleService = mock(RoleService.class);
        Role role = new Role();
        role.setId("tenant_admin_role");
        role.setSystemPurpose(RoleSystemPurpose.TENANT_ADMIN);
        when(roleService.ensureSystemManagedTenantAdminRole("tenant_a",
                DefaultTenantRoleProvisioner.tenantAdminRoleId("tenant_a"),
                RoleService.TENANT_ADMIN_ROLE_TITLE,
                DefaultTenantRoleProvisioner.TENANT_ADMIN_ROLE_DESCRIPTION)).thenReturn(role);
        DefaultTenantRoleProvisioner provisioner = new DefaultTenantRoleProvisioner(roleService);

        Role provisioned = provisioner.ensureTenantAdminRole("tenant_a");

        assertThat(provisioned.getSystemPurpose()).isEqualTo(RoleSystemPurpose.TENANT_ADMIN);
        assertThat(TenantContext.currentTenantId()).isEmpty();
        verify(roleService).ensureSystemManagedTenantAdminRole("tenant_a",
                DefaultTenantRoleProvisioner.tenantAdminRoleId("tenant_a"),
                RoleService.TENANT_ADMIN_ROLE_TITLE,
                DefaultTenantRoleProvisioner.TENANT_ADMIN_ROLE_DESCRIPTION);
    }
}
