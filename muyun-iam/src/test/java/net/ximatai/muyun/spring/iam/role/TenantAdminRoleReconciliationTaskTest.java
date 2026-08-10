package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminRoleReconciliationTaskTest {
    @Test
    void shouldRepairTenantAdminRoleForEveryExistingTenant() {
        TenantService tenantService = mock(TenantService.class);
        Tenant tenant = new Tenant();
        tenant.setId("tenant_a");
        when(tenantService.list(any(Criteria.class))).thenReturn(List.of(tenant));
        DefaultTenantRoleProvisioner provisioner = mock(DefaultTenantRoleProvisioner.class);

        new TenantAdminRoleReconciliationTask(tenantService, provisioner).run();

        verify(provisioner).ensureTenantAdminRole("tenant_a");
    }
}
