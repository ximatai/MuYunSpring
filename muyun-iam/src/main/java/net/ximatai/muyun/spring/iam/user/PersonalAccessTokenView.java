package net.ximatai.muyun.spring.iam.user;

import java.time.Instant;

/** Safe management projection, deliberately excludes hash and full secret. */
public record PersonalAccessTokenView(String id, String name, String displayPrefix, Instant createdAt,
                                      Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {
    static PersonalAccessTokenView of(PersonalAccessToken token) {
        return new PersonalAccessTokenView(token.getId(), token.getName(), token.getDisplayPrefix(),
                token.getCreatedAt(), token.getExpiresAt(), token.getRevokedAt(), token.getLastUsedAt());
    }
}
