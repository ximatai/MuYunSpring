package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * Per-transaction values a protocol needs to talk to its provider.
 *
 * @param state        opaque one-time value that the provider must echo back unchanged as {@code state}
 * @param codeVerifier PKCE code verifier of this transaction, kept on the server only; protocols send
 *                     {@link #codeChallenge()} with the authorization request and this value with the code
 *                     exchange, so an authorization result can only be redeemed by the transaction that asked
 *                     for it
 * @param callbackUri  registered workbench address the provider returns the browser to; must match between
 *                     authorization and verification
 * @param apiBaseUri   public base address of the platform HTTP API, for protocols that serve their own pages
 */
public record ExternalAuthorizationContext(String state, String codeVerifier, URI callbackUri, URI apiBaseUri) {
    /** The only PKCE challenge method the platform issues. */
    public static final String CODE_CHALLENGE_METHOD = "S256";

    public ExternalAuthorizationContext {
        Objects.requireNonNull(codeVerifier, "codeVerifier");
        Objects.requireNonNull(callbackUri, "callbackUri");
        Objects.requireNonNull(apiBaseUri, "apiBaseUri");
    }

    /** PKCE S256 challenge of {@link #codeVerifier()}: base64url without padding of its SHA-256 digest. */
    public String codeChallenge() {
        return codeChallengeOf(codeVerifier);
    }

    static String codeChallengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
