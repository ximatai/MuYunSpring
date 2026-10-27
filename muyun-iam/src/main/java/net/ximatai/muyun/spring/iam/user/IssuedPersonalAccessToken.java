package net.ximatai.muyun.spring.iam.user;

/** Full secret exists only in this one-time issuance response, never an entity or audit summary. */
public record IssuedPersonalAccessToken(String token, PersonalAccessTokenView accessToken) {
    @Override public String toString() { return "IssuedPersonalAccessToken[REDACTED]"; }
}
