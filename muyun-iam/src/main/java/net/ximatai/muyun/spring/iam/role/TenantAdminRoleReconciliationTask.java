package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.Objects;

/** Reconciles the platform-managed tenant-admin role for tenants created before its current contract. */
public class TenantAdminRoleReconciliationTask implements PlatformBootstrapTask {
    private final TenantService tenantService;
    private final DefaultTenantRoleProvisioner tenantRoleProvisioner;

    public TenantAdminRoleReconciliationTask(TenantService tenantService,
                                             DefaultTenantRoleProvisioner tenantRoleProvisioner) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantRoleProvisioner = Objects.requireNonNull(tenantRoleProvisioner,
                "tenantRoleProvisioner must not be null");
    }

    @Override
    public String name() {
        return "platform.tenant-admin-role-reconciliation";
    }

    @Override
    public int order() {
        return 21;
    }

    @Override
    public void run() {
        tenantService.list(Criteria.of())
                .forEach(tenant -> tenantRoleProvisioner.ensureTenantAdminRole(tenant.getId()));
    }
}
