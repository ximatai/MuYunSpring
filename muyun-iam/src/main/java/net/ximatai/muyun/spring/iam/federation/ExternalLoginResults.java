package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.iam.tenant.TenantLoginMethod;
import net.ximatai.muyun.spring.iam.user.LoginResult;

import java.time.Instant;
import java.util.List;

/** HTTP protocol values of the external sign-in and binding flow. */
public final class ExternalLoginResults {
    private ExternalLoginResults() {
    }

    /**
     * A started transaction. {@code state} and {@code browserProof} stay in the initiating browser; only the
     * browser that started the transaction can complete it, and the proof never travels in a URL.
     */
    public record Start(String transactionId, String state, String authorizationUrl, String browserProof,
                        Instant expiresAt, ExternalLoginProtocol.QrCode qrCode) {
    }

    /**
     * Outcome of completing a transaction. {@code login} is present for a successful sign-in and
     * {@code binding} for a successful bind; {@code failure} explains a denial.
     */
    public record Completion(ExternalLoginStatus status, ExternalLoginPurpose purpose, ExternalLoginFailure failure,
                             LoginResult login, IdentityView binding, Confirmation confirmation) {
        public Completion(ExternalLoginStatus status, ExternalLoginPurpose purpose, ExternalLoginFailure failure,
                LoginResult login, IdentityView binding) { this(status, purpose, failure, login, binding, null); }
    }

    public record Confirmation(String displayName, String tenantId, List<String> departmentIds, List<String> placements) {
    }

    /** A binding as shown to its owner; never carries provider secrets. */
    public record IdentityView(String id, String providerId, String providerTitle, String externalUserId,
                               String displayName, Instant boundAt, Instant lastAuthenticatedAt) {
    }

    /** The current user's bindings together with providers still available to bind. */
    public record OwnIdentities(List<IdentityView> bindings, List<TenantLoginMethod> bindableProviders) {
    }
}
