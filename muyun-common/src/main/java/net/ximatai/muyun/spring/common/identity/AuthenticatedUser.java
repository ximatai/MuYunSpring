package net.ximatai.muyun.spring.common.identity;

import java.util.Objects;

/** Verified identity and non-secret credential provenance for one request. */
public record AuthenticatedUser(CurrentUser user, String method, String credentialId) {
    public AuthenticatedUser { Objects.requireNonNull(user, "user"); }
}
