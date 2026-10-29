package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

/** Shares trusted storage scope selection between reception requests and public transfer tickets. */
final class MuYunFileServerStorageScope {
    private MuYunFileServerStorageScope() {
    }

    static String resolve(CurrentUser user, String systemScopeId) {
        String tenant = TenantContext.currentTenantId().orElse(null);
        if (tenant != null) {
            if (!user.system() && !tenant.equals(user.tenantId())) {
                throw new PlatformAccessDeniedException("file storage tenant does not match current identity");
            }
            return requireValidScope(tenant);
        }
        if (TenantContext.isSystem() && !user.system()) {
            throw new PlatformAccessDeniedException("system file storage requires a system identity");
        }
        // Non-Web callers may establish only their authenticated identity.
        if (!TenantContext.hasContext() && user.tenantId() != null) {
            return requireValidScope(user.tenantId());
        }
        if (!user.system()) {
            throw new PlatformAccessDeniedException("file storage requires an authenticated tenant identity");
        }
        if (systemScopeId == null || systemScopeId.isBlank()) {
            throw new IllegalStateException("muyun.file-transfer.muyun-fileserver.system-scope-id must not be blank");
        }
        return requireValidScope(systemScopeId.trim());
    }

    private static String requireValidScope(String scope) {
        if (!scope.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalStateException("file storage requires a valid storage scope");
        }
        return scope;
    }
}
