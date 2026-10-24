package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.federation.ExternalLoginService;
import net.ximatai.muyun.spring.iam.web.security.BearerTokens;
import net.ximatai.muyun.spring.iam.user.LoginResult;
import net.ximatai.muyun.spring.iam.user.CurrentUserProfile;
import net.ximatai.muyun.spring.iam.user.CurrentUserProfileService;
import net.ximatai.muyun.spring.iam.user.UpdateCurrentUserProfileRequest;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.tenant.TenantBranding;
import net.ximatai.muyun.spring.iam.tenant.TenantLoginContext;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionDeclaration;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.AnonymousEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@PlatformStaticActionDeclaration(module = "iam.user")
@RequestMapping("/iam.auth")
public class LoginWebController {
    private final UserSessionService userSessionService;
    private final TenantService tenantService;
    private final CurrentUserProfileService currentUserProfileService;
    private ObjectProvider<ExternalLoginService> externalLoginService;

    public LoginWebController(UserSessionService userSessionService) {
        this.userSessionService = userSessionService;
        this.tenantService = null;
        this.currentUserProfileService = null;
    }

    public LoginWebController(UserSessionService userSessionService, TenantService tenantService) {
        this(userSessionService, tenantService, null);
    }

    @Autowired
    public LoginWebController(UserSessionService userSessionService, TenantService tenantService,
                              CurrentUserProfileService currentUserProfileService) {
        this.userSessionService = userSessionService;
        this.tenantService = tenantService;
        this.currentUserProfileService = currentUserProfileService;
    }

    /** Federation is optional; without it the login entry simply offers no external methods. */
    @Autowired
    void setExternalLoginService(ObjectProvider<ExternalLoginService> externalLoginService) {
        this.externalLoginService = externalLoginService;
    }

    @AnonymousEndpoint
    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        validateLoginRequest(request);
        return userSessionService.login(request.tenantId(), request.username(), request.password(),
                clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/changeOwnPassword")
    public void changeOwnPassword(@RequestBody ChangeOwnPasswordRequest request) {
        CurrentUser currentUser = CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("current user context is not available"));
        userSessionService.changeOwnPassword(currentUser.userId(), request.currentPassword(), request.newPassword());
    }

    @AnonymousEndpoint
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        userSessionService.logout(bearerToken(request));
    }

    @GetMapping("/context")
    public CurrentUser context() {
        return CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("current user context is not available"));
    }

    @GetMapping("/profile")
    @CustomActionEndpoint(value = "selfProfile", title = "个人资料", level = PlatformActionLevel.RECORD,
            accessMode = ActionAccessMode.LOGIN_REQUIRED, actionAuth = false)
    public CurrentUserProfile profile() {
        return requireCurrentUserProfileService().currentProfile(currentUser());
    }

    @PostMapping("/profile")
    @CustomActionEndpoint(value = "selfProfile", title = "个人资料", level = PlatformActionLevel.RECORD,
            accessMode = ActionAccessMode.LOGIN_REQUIRED, actionAuth = false)
    public CurrentUserProfile updateProfile(@RequestBody UpdateCurrentUserProfileRequest request) {
        return requireCurrentUserProfileService().updateCurrentProfile(currentUser(), request);
    }

    @GetMapping("/tenant-branding")
    public TenantBranding tenantBranding() {
        CurrentUser currentUser = CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("current user context is not available"));
        String tenantId = currentUser.tenantId();
        return tenantId == null || tenantId.isBlank() || tenantService == null
                ? TenantBranding.empty()
                : tenantService.branding(tenantId);
    }

    /**
     * Resolves branding for a tenant explicitly selected by the unauthenticated login entry.
     */
    @GetMapping("/login-context")
    @CustomActionEndpoint(value = "loginContext", title = "获取登录入口上下文",
            accessMode = ActionAccessMode.ANONYMOUS_ALLOWED, actionAuth = false, dataAuth = false)
    public TenantLoginContext loginContext(@RequestParam String tenantId) {
        TenantService service = requireTenantService();
        service.requireActiveTenant(tenantId);
        ExternalLoginService federation = externalLoginService == null ? null : externalLoginService.getIfAvailable();
        return new TenantLoginContext(tenantId, service.branding(tenantId),
                federation == null ? java.util.List.of() : federation.loginMethods(tenantId));
    }

    private String bearerToken(HttpServletRequest request) {
        return BearerTokens.from(request);
    }

    private CurrentUser currentUser() {
        return CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("current user context is not available"));
    }

    private CurrentUserProfileService requireCurrentUserProfileService() {
        if (currentUserProfileService == null) {
            throw new IllegalStateException("current user profile service is not available");
        }
        return currentUserProfileService;
    }

    private TenantService requireTenantService() {
        if (tenantService == null) {
            throw new IllegalStateException("tenant service is not available");
        }
        return tenantService;
    }

    private String clientIp(HttpServletRequest request) {
        // Proxy forwarding is not trusted until a dedicated trusted-proxy policy is configured.
        return request.getRemoteAddr();
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null || isBlank(request.username())) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "登录请求缺少用户名",
                    java.util.List.of(ErrorTarget.field("username")));
        }
        if (isBlank(request.password())) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, "登录请求缺少密码",
                    java.util.List.of(ErrorTarget.field("password")));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginRequest(String tenantId, String username, String password) {
    }

    public record ChangeOwnPasswordRequest(String currentPassword, String newPassword) {
    }
}
