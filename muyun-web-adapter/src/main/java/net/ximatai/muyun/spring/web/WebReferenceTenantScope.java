package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Applies the tenant boundary for a reference interaction.
 *
 * <p>The client may identify the record being edited, but never supplies the
 * tenant used for authorization. Callers load that record through their normal
 * source runtime and this executor scopes both candidate lookup and value
 * translation identically.</p>
 */
public final class WebReferenceTenantScope {
    private WebReferenceTenantScope() {
    }

    public static <T> T within(WebReferenceResolveRequest request,
                               ReferenceTenantScope policy,
                               Function<String, String> persistedRecordTenant,
                               Supplier<T> operation) {
        if (policy != ReferenceTenantScope.SAME_TENANT) {
            return operation.get();
        }
        WebReferenceSource source = request == null ? null : request.source();
        if (source == null || !source.persisted()) {
            return operation.get();
        }
        String tenantId = persistedRecordTenant.apply(source.recordId());
        if (tenantId == null || tenantId.isBlank()) {
            throw new PlatformException("reference source record is not available: " + source.recordId());
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            return operation.get();
        }
    }
}
