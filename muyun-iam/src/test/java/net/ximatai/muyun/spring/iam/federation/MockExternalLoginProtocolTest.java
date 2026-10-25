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
            "verifier-1".repeat(5), URI.create("http://workbench.test/app/"), URI.create("http://platform.test/api"));

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
                .contains("redirect_uri=http%3A%2F%2Fworkbench.test%2Fapp%2F")
                .contains("code_challenge=" + context.codeChallenge())
                .contains("code_challenge_method=S256");
    }

    @Test
    void verifiesTheTypedUserWithinTheConfiguredEnterprise() {
        VerifiedExternalIdentity identity = protocol().verify(provider("corp-a"), Map.of("code", code("emp-1")), context);

        assertThat(identity.externalEnterpriseId()).isEqualTo("corp-a");
        assertThat(identity.externalUserId()).isEqualTo("emp-1");
        assertThat(identity.authenticatedAt()).isEqualTo(clock.instant());
        assertThat(protocol().verify(provider(null), Map.of("code", code("emp-1")), context).externalEnterpriseId())
                .isEqualTo(MockExternalLoginProtocol.DEFAULT_ENTERPRISE_ID);
    }

    @Test
    void redeemsACodeOnlyForTheRequestWhosePkceChallengeObtainedIt() {
        ExternalAuthorizationContext other = new ExternalAuthorizationContext("state-2", "verifier-2".repeat(5),
                context.callbackUri(), context.apiBaseUri());

        assertThatThrownBy(() -> protocol().verify(provider(null), Map.of("code", code("emp-1")), other))
                .isInstanceOf(ExternalIdentityVerificationException.class);
    }

    @Test
    void rejectsCallbacksWithoutAMockCode() {
        assertThatThrownBy(() -> protocol().verify(provider(null), Map.of("code", "emp-1"), context))
                .isInstanceOf(ExternalIdentityVerificationException.class)
                .satisfies(error -> assertThat(((ExternalIdentityVerificationException) error).providerUnavailable()).isFalse());
    }

    private String code(String externalUserId) {
        return MockExternalLoginProtocol.CODE_PREFIX + context.codeChallenge() + ":" + externalUserId;
    }

    private MockExternalLoginProtocol protocol() {
        return new MockExternalLoginProtocol(() -> PlatformRuntimeMode.DEVELOPMENT, clock);
    }

    private static IdentityProviderDescriptor provider(String enterpriseId) {
        return new IdentityProviderDescriptor("mock_idp", MockExternalLoginProtocol.PROVIDER_TYPE, enterpriseId,
                "mock_idp", null, null, Map.of());
    }
}
