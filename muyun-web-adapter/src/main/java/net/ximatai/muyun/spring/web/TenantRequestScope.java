package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Common request-time gate for delivery endpoints that require an active tenant.
 *
 * <p>This is a delivery concern rather than a platform-module concern: any Web module may
 * require or preflight the same request scope without depending on another {@code *-web}
 * module. Callers must still enforce the requirement at their authoritative endpoint; the
 * non-throwing method is only appropriate for discovery projections.</p>
 */
@Service
public class TenantRequestScope {
    private final ActiveTenantVerifier activeTenantVerifier;

    public TenantRequestScope(ActiveTenantVerifier activeTenantVerifier) {
        this.activeTenantVerifier = activeTenantVerifier;
    }

    public void requireActiveTenant(String resource) {
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException(resource + " requires tenant context"));
        activeTenantVerifier.verifyActiveTenant(tenantId);
    }

    /** Whether the current request has a tenant that can enter an active-tenant endpoint. */
    public boolean hasActiveTenant() {
        return hasActiveTenant(TenantContext.currentTenantId().orElse(null));
    }

    /** Checks a tenant captured before a delivery layer changes the ambient request scope. */
    public boolean hasActiveTenant(String tenantId) {
        if (tenantId == null) {
            return false;
        }
        try {
            activeTenantVerifier.verifyActiveTenant(tenantId);
            return true;
        } catch (PlatformException ignored) {
            return false;
        }
    }
}
