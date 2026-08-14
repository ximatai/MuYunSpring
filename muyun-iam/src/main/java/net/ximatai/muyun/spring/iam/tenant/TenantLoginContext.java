package net.ximatai.muyun.spring.iam.tenant;

/**
 * Public, unauthenticated login-entry facts for a tenant selected by the login URL.
 *
 * <p>This projection deliberately contains only the locked tenant identity and its
 * decorative branding. It is not a tenant-management or current-session projection.</p>
 */
public record TenantLoginContext(String tenantId, TenantBranding branding) {
}
