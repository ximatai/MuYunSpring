package net.ximatai.muyun.spring.common.tenant;

/**
 * Creates or reconciles organization defaults in the caller's transaction.
 * The organization exists in an active tenant and may itself be disabled.
 * Implementations must be idempotent: explicit provisioning can replay this callback.
 */
public interface OrganizationCreationProvisioner {
    void afterOrganizationCreated(String tenantId, String organizationId);
}
