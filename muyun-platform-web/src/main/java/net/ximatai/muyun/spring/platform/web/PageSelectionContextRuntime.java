package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-scoped resolver for opaque, server-authoritative page selections. */
final class PageSelectionContextRuntime {
    private PageSelectionContextRuntime() {
    }

    static PageContextValue requiredValue(PageContextBindingDefinition binding,
                                          String moduleAlias,
                                          PlatformAction action,
                                          PageSelectionContextResolverRegistry resolvers) {
        if (moduleAlias == null || action == null || resolvers == null) {
            throw new IllegalStateException("resolved selection requires module, action and resolver registry");
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw missingScope(binding);
        }
        PageSelectionContextRequestHeader.SelectionReference reference = PageSelectionContextRequestHeader.parse(
                attributes.getRequest().getHeader(PageSelectionContextRequestHeader.HEADER_NAME));
        if (reference == null || !binding.sourceKey().equals(reference.kind())) throw missingScope(binding);
        CurrentUser user = CurrentUserContext.currentUser().orElseThrow(() ->
                PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                        "Page selection requires an authenticated user"));
        PageContextValue value = resolve(attributes.getRequest(), moduleAlias, reference, action, resolvers, user)
                .values().get(binding.targetKey());
        if (value == null || !value.present()) {
            throw new IllegalStateException("page selection resolver did not resolve field: " + binding.targetKey());
        }
        return value;
    }

    private static ResolvedPageSelectionContext resolve(HttpServletRequest request,
                                                        String moduleAlias,
                                                        PageSelectionContextRequestHeader.SelectionReference reference,
                                                        PlatformAction action,
                                                        PageSelectionContextResolverRegistry resolvers,
                                                        CurrentUser user) {
        String attributeName = PageSelectionContextRuntime.class.getName() + ".selection."
                + moduleAlias + "." + action.name() + "." + reference.kind() + "." + reference.key();
        Object cached = request.getAttribute(attributeName);
        if (cached instanceof ResolvedPageSelectionContext resolved) return resolved;
        ResolvedPageSelectionContext resolved = resolvers.resolve(new PageSelectionContextRequest(
                moduleAlias, reference.kind(), reference.key(), action, user,
                MenuEntryRequestContext.current().map(MenuEntryRequestContext::menuId).orElse(null)));
        request.setAttribute(attributeName, resolved);
        return resolved;
    }

    private static RuntimeException missingScope(PageContextBindingDefinition binding) {
        return PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                "Page navigator scope is required: " + binding.sourceKey());
    }
}
