package net.ximatai.muyun.spring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

public class CurrentUserWebFilter extends OncePerRequestFilter {
    private final CurrentUserProvider currentUserProvider;

    public CurrentUserWebFilter(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * MVC asynchronous results resume through a separate Servlet dispatch.  The request-bound
     * identity scopes from the initial dispatch have already been closed at that point, while
     * MVC interceptors run again to authorize the resumed endpoint.  Bind a fresh scope for
     * every dispatch instead of allowing a resumed protected endpoint to observe no user.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<CurrentUser> currentUser = currentUserProvider.currentUser();
        if (currentUser.isEmpty()) {
            if (hasBearerToken(request)) {
                rejectAuthenticationRequired(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        if (currentUser.get().passwordChangeRequired() && !isPasswordChangeAllowed(request)) {
            rejectPasswordChangeRequired(response);
            return;
        }
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(currentUser.get())) {
            doFilterWithTenantScope(currentUser.get(), request, response, filterChain);
        }
    }

    private void doFilterWithTenantScope(CurrentUser currentUser,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain filterChain) throws ServletException, IOException {
        if (currentUser.system()) {
            try (TenantContext.Scope ignored = TenantContext.system("system user web request")) {
                filterChain.doFilter(request, response);
            }
            return;
        }
        String tenantId = currentUser.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            filterChain.doFilter(request, response);
        }
    }

    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        String path = requestPath(request);
        return "/iam.auth/context".equals(path)
                || "/iam.auth/changeOwnPassword".equals(path)
                || "/iam.auth/logout".equals(path);
    }

    /** Servlet context is transport deployment detail, not part of the platform endpoint identity. */
    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri != null && contextPath != null && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private boolean hasBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String prefix = "Bearer ";
        return header != null && header.regionMatches(true, 0, prefix, 0, prefix.length())
                && !header.substring(prefix.length()).isBlank();
    }

    private void rejectAuthenticationRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"code":"AUTH_REQUIRED","status":401,"message":"current user context is not available"}
                """);
    }

    private void rejectPasswordChangeRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"code":"PASSWORD_CHANGE_REQUIRED","status":403,"message":"password change required"}
                """);
    }
}
