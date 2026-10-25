package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Development-only provider that lets a developer pick any external user id on a local authorization page,
 * so the complete browser and server flow, including PKCE, can run without a real identity provider. It refuses to start in
 * production runtime mode because it authenticates whoever is typed in.
 */
@Component
@Conditional(MockIdentityProviderCondition.class)
public class MockExternalLoginProtocol implements ExternalLoginProtocol {
    public static final String PROVIDER_TYPE = "mock";
    static final String CODE_PREFIX = "mock:";
    static final String DEFAULT_ENTERPRISE_ID = "mock-enterprise";

    private final Clock clock;

    @Autowired
    public MockExternalLoginProtocol(PlatformRuntimeModeProvider runtimeModeProvider) {
        this(runtimeModeProvider, Clock.systemUTC());
    }

    MockExternalLoginProtocol(PlatformRuntimeModeProvider runtimeModeProvider, Clock clock) {
        if (!Objects.requireNonNull(runtimeModeProvider, "runtimeModeProvider").isDevelopment()) {
            throw new IllegalStateException(
                    "the mock identity provider is only available in development runtime mode");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
        return ExternalIdentityUris.withQuery(
                ExternalIdentityUris.resolve(context.apiBaseUri(), "iam.auth/external/mock/authorize"),
                Map.of("state", context.state(), "redirect_uri", context.callbackUri().toString(),
                        "code_challenge", context.codeChallenge(),
                        "code_challenge_method", ExternalAuthorizationContext.CODE_CHALLENGE_METHOD));
    }

    @Override
    public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> callbackParameters,
                                           ExternalAuthorizationContext context) {
        // Like a real provider, the code is bound to the PKCE challenge of the request that obtained it.
        String code = callbackParameters.get("code");
        String[] parts = code == null || !code.startsWith(CODE_PREFIX)
                ? new String[0]
                : code.substring(CODE_PREFIX.length()).split(":", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            throw ExternalIdentityVerificationException.rejected("mock authorization code is missing");
        }
        if (!parts[0].equals(context.codeChallenge())) {
            throw ExternalIdentityVerificationException.rejected("mock authorization code was issued to another request");
        }
        String externalUserId = parts[1];
        String enterpriseId = provider.externalEnterpriseId() == null
                ? DEFAULT_ENTERPRISE_ID
                : provider.externalEnterpriseId();
        return new VerifiedExternalIdentity(enterpriseId, externalUserId, externalUserId, clock.instant());
    }
}
