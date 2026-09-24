package net.ximatai.muyun.spring.common.tenant;

/**
 * Idempotent tenant initialization extension. Runs in the caller's mutation transaction;
 * failures roll back database changes made by all participating extensions.
 */
public interface TenantCreationProvisioner {
    void afterTenantCreated(String tenantId);
}
