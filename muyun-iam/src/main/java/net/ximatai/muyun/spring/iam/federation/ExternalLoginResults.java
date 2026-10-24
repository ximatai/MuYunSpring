package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.iam.user.LoginResult;

import java.time.Instant;
import java.util.List;

/** Protocol values of the external login flow. */
public final class ExternalLoginResults {
    private ExternalLoginResults() {
    }

    /** An identity provider offered on a tenant's sign-in entry. */
    public record LoginMethod(String providerId, String title, String providerType) {
    }

    /**
     * A started transaction. {@code browserProof} is returned once and must stay in the initiating browser;
     * it is required to redeem the result and is never placed in a URL.
     */
    public record Start(String transactionId, String authorizationUrl, String browserProof, Instant expiresAt) {
    }

    /**
     * Outcome of redeeming a transaction. {@code login} is present for a successful sign-in and
     * {@code binding} for a successful bind; {@code failureCode} explains a denial.
     */
    public record Redemption(ExternalLoginStatus status, ExternalLoginPurpose purpose, String failureCode,
                             LoginResult login, IdentityView binding) {
    }

    /** A binding as shown to its owner or to administrators; never carries provider secrets. */
    public record IdentityView(String id, String providerId, String providerTitle, String externalUserId,
                               String displayName, Instant boundAt, Instant lastAuthenticatedAt) {
    }

    /** The current user's bindings together with providers still available to bind. */
    public record OwnIdentities(List<IdentityView> bindings, List<LoginMethod> bindableProviders) {
    }
}
