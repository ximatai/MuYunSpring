package net.ximatai.muyun.spring.iam.federation;

import java.time.Instant;

/**
 * An identity the provider confirmed during server-side verification.
 *
 * @param externalEnterpriseId enterprise the identity belongs to at the provider
 * @param externalUserId       stable user id within the provider's identity namespace
 * @param displayName          optional name for confirmation screens; never used to match accounts
 * @param authenticatedAt      when the provider authenticated the user
 */
public record VerifiedExternalIdentity(String externalEnterpriseId,
                                       String externalUserId,
                                       String displayName,
                                       Instant authenticatedAt) {
    public VerifiedExternalIdentity {
        externalEnterpriseId = requireText(externalEnterpriseId, "externalEnterpriseId");
        externalUserId = requireText(externalUserId, "externalUserId");
        displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (authenticatedAt == null) {
            throw new IllegalArgumentException("authenticatedAt must not be null");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
