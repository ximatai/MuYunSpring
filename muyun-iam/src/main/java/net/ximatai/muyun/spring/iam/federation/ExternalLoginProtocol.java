package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.util.Map;

/**
 * Vendor protocol behind an identity provider type. A plugin implements it as a Spring bean and the
 * platform selects it by the provider type stored on the identity provider.
 *
 * <p>Implementations only prove who the user is at the provider. They receive a read-only
 * {@link IdentityProviderDescriptor}, never create accounts, read or write IAM data, or issue platform
 * sessions; the platform owns state, browser binding, account resolution, admission and session issuance.</p>
 */
public interface ExternalLoginProtocol {
    /** Stable protocol key stored as the identity provider's type. */
    String providerType();

    /**
     * Checks protocol-specific provider settings when an administrator saves the provider.
     *
     * @throws IllegalArgumentException with a message safe to show to administrators
     */
    default void validateConfiguration(Map<String, String> configuration) {
    }

    /** Builds the provider authorization address that the browser opens for this transaction. */
    URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context);

    /**
     * Verifies the callback parameters on the server and returns the authenticated identity. Values
     * delivered through the browser are untrusted until the provider confirms them.
     *
     * @throws ExternalIdentityVerificationException when the provider rejects or cannot confirm the callback
     */
    VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> callbackParameters,
                                    ExternalAuthorizationContext context);
}
