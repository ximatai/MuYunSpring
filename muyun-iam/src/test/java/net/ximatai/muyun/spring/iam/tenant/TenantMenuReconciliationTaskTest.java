package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.menu.DefaultTenantMenuProvisioner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantMenuReconciliationTaskTest {
    @Test
    void shouldReconcileSystemMenuCopiesForEveryExistingTenant() {
        TenantService tenantService = mock(TenantService.class);
        Tenant first = new Tenant();
        first.setId("tenant_a");
        Tenant second = new Tenant();
        second.setId("tenant_b");
        when(tenantService.list(any(Criteria.class))).thenReturn(List.of(first, second));
        DefaultTenantMenuProvisioner provisioner = mock(DefaultTenantMenuProvisioner.class);

        new TenantMenuReconciliationTask(tenantService, provisioner).run();

        verify(provisioner).reconcileTenantAdminMenus("tenant_a");
        verify(provisioner).reconcileTenantAdminMenus("tenant_b");
    }
}
