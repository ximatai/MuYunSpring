package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.menu.DefaultTenantMenuProvisioner;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.Objects;

/** Reconciles system-provided default menus for existing tenants after static declarations are registered. */
public class TenantMenuReconciliationTask implements PlatformBootstrapTask {
    private final TenantService tenantService;
    private final DefaultTenantMenuProvisioner menuProvisioner;

    public TenantMenuReconciliationTask(TenantService tenantService,
                                        DefaultTenantMenuProvisioner menuProvisioner) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.menuProvisioner = Objects.requireNonNull(menuProvisioner, "menuProvisioner must not be null");
    }

    @Override
    public String name() {
        return "platform.tenant-menu-reconciliation";
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public void run() {
        tenantService.list(Criteria.of())
                .forEach(tenant -> menuProvisioner.reconcileTenantAdminMenus(tenant.getId()));
    }
}
