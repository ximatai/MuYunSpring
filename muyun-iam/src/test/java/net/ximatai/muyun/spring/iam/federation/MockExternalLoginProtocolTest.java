package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockExternalLoginProtocolTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final ExternalAuthorizationContext context = new ExternalAuthorizationContext("state-1",
            URI.create("http://platform.test/api/iam.auth/external/mock_idp/callback"), URI.create("http://platform.test/api"));

    @Test
    void refusesToStartOutsideDevelopmentMode() {
        assertThatThrownBy(() -> new MockExternalLoginProtocol(() -> PlatformRuntimeMode.PRODUCTION, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development");
    }

    @Test
    void pointsTheBrowserAtTheLocalAuthorizationPage() {
        URI uri = protocol().authorizationUri(provider(null), context);

        assertThat(uri.toString()).startsWith("http://platform.test/api/iam.auth/external/mock/authorize?")
                .contains("state=state-1")
                .contains("redirect_uri=http%3A%2F%2Fplatform.test%2Fapi%2Fiam.auth%2Fexternal%2Fmock_idp%2Fcallback");
    }

    @Test
    void verifiesTheTypedUserWithinTheConfiguredEnterprise() {
        VerifiedExternalIdentity identity = protocol().verify(provider("corp-a"), Map.of("code", "mock:emp-1"), context);

        assertThat(identity.externalEnterpriseId()).isEqualTo("corp-a");
        assertThat(identity.externalUserId()).isEqualTo("emp-1");
        assertThat(identity.authenticatedAt()).isEqualTo(clock.instant());
        assertThat(protocol().verify(provider(null), Map.of("code", "mock:emp-1"), context).externalEnterpriseId())
                .isEqualTo(MockExternalLoginProtocol.DEFAULT_ENTERPRISE_ID);
    }

    @Test
    void rejectsCallbacksWithoutAMockCode() {
        assertThatThrownBy(() -> protocol().verify(provider(null), Map.of("code", "emp-1"), context))
                .isInstanceOf(ExternalIdentityVerificationException.class)
                .satisfies(error -> assertThat(((ExternalIdentityVerificationException) error).providerUnavailable()).isFalse());
    }

    private MockExternalLoginProtocol protocol() {
        return new MockExternalLoginProtocol(() -> PlatformRuntimeMode.DEVELOPMENT, clock);
    }

    private static IdentityProvider provider(String enterpriseId) {
        IdentityProvider provider = new IdentityProvider();
        provider.setId("mock_idp");
        provider.setProviderType(MockExternalLoginProtocol.PROVIDER_TYPE);
        provider.setExternalEnterpriseId(enterpriseId);
        return provider;
    }
}
