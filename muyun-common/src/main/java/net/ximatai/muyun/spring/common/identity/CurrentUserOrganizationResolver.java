package net.ximatai.muyun.spring.common.identity;

import java.util.Optional;

/** Resolves the organization of a principal from its personnel identity, when one exists. */
@FunctionalInterface
public interface CurrentUserOrganizationResolver {
    CurrentUserOrganizationResolver NONE = currentUser -> Optional.empty();

    Optional<String> resolveOrganizationId(CurrentUser currentUser);
}
