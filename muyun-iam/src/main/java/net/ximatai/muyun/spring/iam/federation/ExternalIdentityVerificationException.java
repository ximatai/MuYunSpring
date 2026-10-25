package net.ximatai.muyun.spring.iam.federation;

/**
 * Raised by a protocol when the provider callback cannot be turned into a verified identity. The message
 * must never contain authorization codes, tokens or secrets.
 */
public class ExternalIdentityVerificationException extends RuntimeException {
    private final boolean providerUnavailable;

    private ExternalIdentityVerificationException(String message, boolean providerUnavailable, Throwable cause) {
        super(message, cause);
        this.providerUnavailable = providerUnavailable;
    }

    /** The provider answered and refused or did not confirm the authorization. */
    public static ExternalIdentityVerificationException rejected(String message) {
        return new ExternalIdentityVerificationException(message, false, null);
    }

    /** The provider could not be reached or answered unexpectedly. */
    public static ExternalIdentityVerificationException unavailable(String message, Throwable cause) {
        return new ExternalIdentityVerificationException(message, true, cause);
    }

    public boolean providerUnavailable() {
        return providerUnavailable;
    }
}
