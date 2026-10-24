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
 * so the complete browser and server flow can run without a real identity provider. It refuses to start in
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
    public URI authorizationUri(IdentityProvider provider, ExternalAuthorizationContext context) {
        return ExternalIdentityUris.withQuery(
                ExternalIdentityUris.resolve(context.apiBaseUri(), "iam.auth/external/mock/authorize"),
                Map.of("state", context.state(), "redirect_uri", context.callbackUri().toString()));
    }

    @Override
    public VerifiedExternalIdentity verify(IdentityProvider provider, Map<String, String> callbackParameters,
                                           ExternalAuthorizationContext context) {
        String code = callbackParameters.get("code");
        if (code == null || !code.startsWith(CODE_PREFIX) || code.length() == CODE_PREFIX.length()) {
            throw ExternalIdentityVerificationException.rejected("mock authorization code is missing");
        }
        String externalUserId = code.substring(CODE_PREFIX.length());
        String enterpriseId = provider.getExternalEnterpriseId() == null
                ? DEFAULT_ENTERPRISE_ID
                : provider.getExternalEnterpriseId();
        return new VerifiedExternalIdentity(enterpriseId, externalUserId, externalUserId, clock.instant());
    }
}
