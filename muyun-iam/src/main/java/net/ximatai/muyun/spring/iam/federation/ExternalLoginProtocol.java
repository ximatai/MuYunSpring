package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.util.Map;
import java.util.Set;

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

    /**
     * Protocol configuration keys that decide whose identities the provider vouches for, such as an issuer or
     * tenant endpoint. Like the provider type and client id they are locked while bindings exist, because the
     * same external user id under another authority is a different person.
     */
    default Set<String> identityScopeConfigurationKeys() {
        return Set.of();
    }

    /** Only enterprise-internal, single-enterprise applications may supply a configured enterprise. */
    default boolean singleEnterpriseApplication(IdentityProviderDescriptor provider) { return false; }

    /**
     * Builds the provider authorization address that the browser opens for this transaction. Protocols whose
     * provider supports PKCE send {@link ExternalAuthorizationContext#codeChallenge()} with the
     * {@link ExternalAuthorizationContext#CODE_CHALLENGE_METHOD} method and the verifier when redeeming the code;
     * a protocol that cannot must bind the authorization result to the transaction by an equivalent provider
     * mechanism.
     */
    URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context);

    /** Optional recognized OAuth QR presentation; never includes credentials or a PKCE verifier. */
    default QrCode qrCode(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
        return null;
    }

    record QrCode(String providerType, String scriptUrl, String authorizationUrl, String clientId,
                  String redirectUri, String state, String scope, String messageOrigin) { }

    /**
     * Verifies the callback parameters on the server and returns the authenticated identity. Values
     * delivered through the browser are untrusted until the provider confirms them.
     *
     * @throws ExternalIdentityVerificationException when the provider rejects or cannot confirm the callback
     */
    VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> callbackParameters,
                                    ExternalAuthorizationContext context);
}
