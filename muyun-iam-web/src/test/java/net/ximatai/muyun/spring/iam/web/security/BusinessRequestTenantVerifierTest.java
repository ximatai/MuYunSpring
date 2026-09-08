package net.ximatai.muyun.spring.iam.web.security;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BusinessRequestTenantVerifierTest {
    @Test
    void shouldRequireTenantReferenceAuthorizationAndActiveTenantOnEveryRequest() {
        var tenants = mock(TenantService.class);
        var policies = mock(ActionExecutionPolicyService.class);
        var verifier = new BusinessRequestTenantVerifier(tenants, policies);
        when(tenants.select("a")).thenReturn(new Tenant());
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin"))) {
            verifier.verify("a");
            verify(policies).authorizeAction(eq("iam.tenant"), any(), any());
            verify(tenants).verifyActiveTenant("a");
            assertThatThrownBy(() -> verifier.verify("unknown")).isInstanceOf(IllegalArgumentException.class);
            doThrow(new IllegalArgumentException("disabled")).when(tenants).verifyActiveTenant("a");
            assertThatThrownBy(() -> verifier.verify("a")).hasMessage("disabled");
            doThrow(new IllegalArgumentException("unauthorized")).when(policies).authorizeAction(any(), any(), any());
            assertThatThrownBy(() -> verifier.verify("a")).hasMessage("unauthorized");
        }
    }

    @Test
    void shouldUseOnlyAuthenticatedTenantWithoutRequiringTenantManagementPermission() {
        var tenants = mock(TenantService.class);
        var policies = mock(ActionExecutionPolicyService.class);
        var verifier = new BusinessRequestTenantVerifier(tenants, policies);
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("u", "u", "a"))) {
            verifier.verify("a");
            verify(tenants).verifyActiveTenant("a");
            verifyNoInteractions(policies);
            assertThatThrownBy(() -> verifier.verify("b")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
