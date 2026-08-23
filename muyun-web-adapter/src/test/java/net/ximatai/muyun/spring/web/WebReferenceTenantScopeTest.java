package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebReferenceTenantScopeTest {
    @Test
    void shouldUseOnlyThePersistedSourceTenantForSameTenantReferences() {
        AtomicReference<String> observedTenant = new AtomicReference<>();
        WebReferenceResolveRequest request = request(new WebReferenceSource("order-1"), Map.of("tenantId", "forged"));

        try (TenantContext.Scope ignored = TenantContext.system("reference test")) {
            WebReferenceTenantScope.within(request, ReferenceTenantScope.SAME_TENANT,
                    sourceId -> "order-1".equals(sourceId) ? "tenant-a" : null,
                    () -> {
                        observedTenant.set(TenantContext.currentTenantId().orElse(null));
                        return null;
                    });
        }

        assertThat(observedTenant.get()).isEqualTo("tenant-a");
    }

    @Test
    void shouldFailClosedWhenAnExplicitSourceCannotBeResolved() {
        WebReferenceResolveRequest request = request(new WebReferenceSource("missing"), Map.of());

        assertThatThrownBy(() -> WebReferenceTenantScope.within(request, ReferenceTenantScope.SAME_TENANT,
                ignored -> null, () -> null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference source record is not available: missing");
    }

    @Test
    void shouldLeaveGlobalReferencesInTheAmbientScope() {
        AtomicReference<String> observedTenant = new AtomicReference<>();
        try (TenantContext.Scope ignored = TenantContext.system("reference test")) {
            WebReferenceTenantScope.within(request(new WebReferenceSource("order-1"), Map.of()),
                    ReferenceTenantScope.GLOBAL,
                    sourceId -> "tenant-a",
                    () -> {
                        observedTenant.set(TenantContext.currentTenantId().orElse(null));
                        return null;
                    });
        }
        assertThat(observedTenant.get()).isNull();
    }

    private static WebReferenceResolveRequest request(WebReferenceSource source, Map<String, Object> formValues) {
        return new WebReferenceResolveRequest(WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                WebPageRequest.DEFAULT, true, formValues, source, null, null, null, Map.of());
    }
}
