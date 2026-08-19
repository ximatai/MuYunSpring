package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;

import java.util.Optional;

/**
 * Resolves page context whose authority is the server request, never a browser supplied value.
 *
 * <p>Additional sources are intentionally not guessed here: route and form-field context need
 * their own authenticated transport contracts before they can participate in a mutation policy.</p>
 */
public final class PageContextServerValueResolver {
    private PageContextServerValueResolver() {
    }

    public static Optional<Object> resolve(PageContextBindingDefinition binding) {
        if (binding.source() != PageContextSource.SESSION) return Optional.empty();
        return CurrentUserContext.currentUser().flatMap(user -> sessionValue(user, binding.sourceKey()));
    }

    private static Optional<Object> sessionValue(CurrentUser user, String sourceKey) {
        return switch (sourceKey) {
            case "userId" -> Optional.of(user.userId());
            case "tenantId" -> Optional.ofNullable(user.tenantId());
            case "organizationId" -> Optional.ofNullable(user.organizationId());
            default -> Optional.empty();
        };
    }
}
