package net.ximatai.muyun.spring.iam.tenant;

import java.util.List;

/**
 * Public, unauthenticated login-entry facts for a tenant selected by the login URL.
 *
 * <p>This projection deliberately contains only the locked tenant identity, its decorative branding and
 * the additional sign-in methods the tenant offers. It is not a tenant-management or current-session
 * projection.</p>
 */
public record TenantLoginContext(String tenantId, TenantBranding branding, List<TenantLoginMethod> externalLoginMethods) {
    public TenantLoginContext {
        externalLoginMethods = externalLoginMethods == null ? List.of() : List.copyOf(externalLoginMethods);
    }

    public TenantLoginContext(String tenantId, TenantBranding branding) {
        this(tenantId, branding, List.of());
    }
}
