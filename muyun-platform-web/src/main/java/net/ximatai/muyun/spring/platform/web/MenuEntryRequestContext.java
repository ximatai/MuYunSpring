package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.menu.Menu;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Request-scoped optional page-entry context for a visible menu entry that opened a module page.
 *
 * <p>The value is created only by {@link MenuEntryRequestInterceptor}; callers must not use the
 * browser header directly as an authorization or data-scope authority.</p>
 */
public record MenuEntryRequestContext(String menuId, String moduleAlias, String entryParamsJson) {
    public static final String HEADER_NAME = "X-MuYun-Menu-Id";
    private static final String ATTRIBUTE_NAME = MenuEntryRequestContext.class.getName();

    static void bind(HttpServletRequest request, Menu menu) {
        request.setAttribute(ATTRIBUTE_NAME, new MenuEntryRequestContext(
                menu.getId(), menu.getModuleAlias(), menu.getEntryParamsJson()));
    }

    static void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE_NAME);
    }

    public static Optional<MenuEntryRequestContext> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.getAttribute(ATTRIBUTE_NAME, RequestAttributes.SCOPE_REQUEST);
        return value instanceof MenuEntryRequestContext context ? Optional.of(context) : Optional.empty();
    }

    /**
     * Rejects using one visible menu as a capability to invoke a different module.
     */
    public static void requireModuleAlias(String requestedModuleAlias) {
        requireModuleAlias(requestedModuleAlias, null);
    }

    /**
     * Allows a statically declared child module to serve a page opened through its parent menu.
     * The parent relation is server-side module metadata, never a browser-supplied override.
     */
    public static void requireModuleAlias(String requestedModuleAlias, String pageEntryParentModuleAlias) {
        current().ifPresent(context -> {
            if (!context.moduleAlias.equals(requestedModuleAlias)
                    && !context.moduleAlias.equals(pageEntryParentModuleAlias)) {
                throw new PlatformException("Menu entry module mismatch: menu " + context.menuId
                        + " targets " + context.moduleAlias + ", requested " + requestedModuleAlias);
            }
        });
    }
}
