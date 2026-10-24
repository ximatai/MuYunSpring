package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.util.Map;

/**
 * Vendor protocol behind an identity provider type. A plugin implements it as a Spring bean and the
 * platform selects it by {@link IdentityProvider#getProviderType()}.
 *
 * <p>Implementations only prove who the user is at the provider. They never create accounts, read or
 * write IAM data, or issue platform sessions; the platform owns state, replay protection, account
 * resolution, admission and session issuance.</p>
 */
public interface ExternalLoginProtocol {
    /** Stable protocol key stored on {@link IdentityProvider#getProviderType()}. */
    String providerType();

    /** Builds the provider authorization address that the browser opens for this transaction. */
    URI authorizationUri(IdentityProvider provider, ExternalAuthorizationContext context);

    /**
     * Verifies the provider callback on the server and returns the authenticated identity. Values
     * supplied by the browser are untrusted until the provider confirms them.
     *
     * @throws ExternalIdentityVerificationException when the provider rejects or cannot confirm the callback
     */
    VerifiedExternalIdentity verify(IdentityProvider provider, Map<String, String> callbackParameters,
                                    ExternalAuthorizationContext context);
}
