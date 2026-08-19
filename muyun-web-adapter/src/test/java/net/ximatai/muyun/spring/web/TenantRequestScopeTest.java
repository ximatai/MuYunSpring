package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TenantRequestScopeTest {
    @Test
    void shouldRequireAndVerifyTheCurrentTenant() {
        ActiveTenantVerifier verifier = mock(ActiveTenantVerifier.class);
        TenantRequestScope scope = new TenantRequestScope(verifier);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            scope.requireActiveTenant("crm.customer");
        }

        verify(verifier).verifyActiveTenant("tenant-a");
    }

    @Test
    void shouldRejectMissingOrInactiveTenantAndOfferSafeDiscoveryCheck() {
        ActiveTenantVerifier verifier = mock(ActiveTenantVerifier.class);
        doThrow(new PlatformException("tenant inactive")).when(verifier).verifyActiveTenant("tenant-disabled");
        TenantRequestScope scope = new TenantRequestScope(verifier);

        assertThatThrownBy(() -> scope.requireActiveTenant("crm.customer"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("crm.customer requires tenant context");
        assertThat(scope.hasActiveTenant(null)).isFalse();
        assertThat(scope.hasActiveTenant("tenant-disabled")).isFalse();
    }
}
