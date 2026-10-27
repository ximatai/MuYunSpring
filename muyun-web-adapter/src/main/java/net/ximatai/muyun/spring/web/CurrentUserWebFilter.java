package net.ximatai.muyun.spring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.AuthenticatedUser;
import net.ximatai.muyun.spring.common.identity.RequestAuthenticationContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.i18n.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

public class CurrentUserWebFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CurrentUserWebFilter.class);
    public static final String TENANT_HEADER = "X-MuYun-Tenant-Id";
    private final CurrentUserProvider currentUserProvider;
    private final RequestTenantVerifier requestTenantVerifier;
    private TextResolver textResolver;
    private LanguageCatalog languageCatalog;
    private ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTextResolver(TextResolver resolver) { this.textResolver = resolver; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLanguageCatalog(LanguageCatalog catalog) { this.languageCatalog = catalog; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setObjectMapper(ObjectMapper mapper) { this.objectMapper = mapper; }

    /**
     * 仅供不接受业务租户请求的轻量装配使用。生产 Web 应用应使用双参数构造器，
     * 否则携带 {@link #TENANT_HEADER} 的请求会按 fail-closed 规则被拒绝。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CurrentUserWebFilter(CurrentUserProvider currentUserProvider) {
        this(currentUserProvider, null);
    }

    public CurrentUserWebFilter(CurrentUserProvider currentUserProvider, RequestTenantVerifier requestTenantVerifier) {
        this.currentUserProvider = currentUserProvider;
        this.requestTenantVerifier = requestTenantVerifier;
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
        Optional<AuthenticatedUser> authentication;
        try {
            authentication = currentUserProvider.authenticatedUser();
        } catch (PlatformException rejected) {
            if (rejected.httpStatus() != 401 && rejected.httpStatus() != 403) throw rejected;
            var error = PlatformWebError.of(rejected).presented(textResolver, languageCatalog);
            response.setStatus(error.status());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(objectMapper.writeValueAsString(error));
            RequestErrorLogRecorder.markFilterResponse(request, error.code(), error.status(), error.message());
            return;
        }
        Optional<CurrentUser> currentUser = authentication.map(AuthenticatedUser::user);
        if (currentUser.isEmpty()) {
            if (request.getHeader(TENANT_HEADER) != null) {
                rejectAuthenticationRequired(request, response);
                return;
            }
            if (hasBearerToken(request)) {
                rejectAuthenticationRequired(request, response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        RequestErrorLogRecorder.snapshotCurrentUser(request, currentUser.get());
        RequestErrorLogRecorder.snapshotAuthentication(request, authentication.orElseThrow());
        if (currentUser.get().passwordChangeRequired() && !isPasswordChangeAllowed(request)) {
            rejectPasswordChangeRequired(request, response);
            return;
        }
        try (var provenance = RequestAuthenticationContext.use(authentication.orElseThrow());
             CurrentUserContext.Scope ignored = CurrentUserContext.use(currentUser.get())) {
            doFilterWithTenantScope(currentUser.get(), request, response, filterChain);
        }
    }

    private void doFilterWithTenantScope(CurrentUser currentUser,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain filterChain) throws ServletException, IOException {
        String requestedTenant = request.getHeader(TENANT_HEADER);
        if (requestedTenant != null) {
            try {
                int tenantHeaderCount = java.util.Collections.list(request.getHeaders(TENANT_HEADER)).size();
                boolean identityMatches = currentUser.system() || requestedTenant.equals(currentUser.tenantId());
                if (requestedTenant.isBlank() || tenantHeaderCount != 1 || requestTenantVerifier == null
                        || !identityMatches) {
                    throw new IllegalArgumentException("invalid business tenant: blank=" + requestedTenant.isBlank()
                            + ", headerCount=" + tenantHeaderCount
                            + ", verifierAvailable=" + (requestTenantVerifier != null)
                            + ", identityMatches=" + identityMatches);
                }
                requestTenantVerifier.verify(requestedTenant);
            } catch (net.ximatai.muyun.spring.common.exception.PlatformException | IllegalArgumentException denied) {
                LOGGER.warn("Business tenant request rejected for identity tenant {}: {}",
                        currentUser.tenantId(), denied.getMessage());
                reject(request, response, "TENANT_ACCESS_DENIED", HttpServletResponse.SC_FORBIDDEN,
                        TextReference.code("platform.error.tenant-access-denied", "业务租户不可用或无权访问"));
                return;
            }
            RequestErrorLogRecorder.snapshotTenant(request, requestedTenant);
            try (TenantContext.Scope ignored = TenantContext.use(requestedTenant)) {
                filterChain.doFilter(request, response);
            }
            return;
        }
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

    private void rejectAuthenticationRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reject(request, response, "AUTH_REQUIRED", HttpServletResponse.SC_UNAUTHORIZED,
                TextReference.code("platform.error.authentication-required", "current user context is not available"));
    }

    private void rejectPasswordChangeRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        reject(request, response, "PASSWORD_CHANGE_REQUIRED", HttpServletResponse.SC_FORBIDDEN,
                TextReference.code("platform.error.password-change-required", "password change required"));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code, int status,
                         TextReference source) throws IOException {
        var error = PlatformWebError.of(code, status, source.originalText()).withTextSource(source)
                .presented(textResolver, languageCatalog);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(error));
        RequestErrorLogRecorder.markFilterResponse(request, code, status, source.originalText());
    }
}
