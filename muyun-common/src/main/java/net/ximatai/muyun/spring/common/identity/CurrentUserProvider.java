package net.ximatai.muyun.spring.common.identity;

import java.util.Optional;

public interface CurrentUserProvider {
    Optional<CurrentUser> currentUser();

    default Optional<AuthenticatedUser> authenticatedUser() {
        return currentUser().map(user -> new AuthenticatedUser(user, null, null));
    }
}
