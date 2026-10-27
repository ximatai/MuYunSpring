package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.common.identity.AuthenticatedUser;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class PersonalAccessTokenService {
    public static final String MODULE_ALIAS = "iam.personal_access_token";
    public static final String PREFIX = "muyun_pat_";
    public static final String METHOD = "personal_access_token";
    public static final String CREATE_ACTION = "create";
    public static final ActionExecutionPolicy CREATE_POLICY = new ActionExecutionPolicy(CREATE_ACTION,
            PlatformActionLevel.LIST, ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null);
    private final ActionExecutionPolicyService permissions;
    private final ActiveTenantVerifier tenants;
    private static final Pattern FORMAT = Pattern.compile("muyun_pat_[A-Za-z0-9_-]{43}");
    private final PersonalAccessTokenStore tokens;
    private final UserAccountService accounts;
    private final UserSessionService sessions;
    private final Clock clock;
    private final UserAccountIdentity identities;
    private final RuntimeEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public PersonalAccessTokenService(PersonalAccessTokenStore tokens, UserAccountService accounts,
            UserSessionService sessions, UserSessionCollaborators collaborators, Clock clock,
            RuntimeEventPublisher events, ActionExecutionPolicyService permissions, ActiveTenantVerifier tenants) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identities = new UserAccountIdentity(Objects.requireNonNull(collaborators, "collaborators"));
        this.events = Objects.requireNonNull(events, "events");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Transactional
    public IssuedPersonalAccessToken create(String sessionToken, String name, Integer validDays) {
        CurrentUser owner = requireOwner();
        requireInteractiveSession(owner, sessionToken);
        authorizeCreation(owner);
        if (name == null || name.isBlank() || name.trim().length() > 100
                || validDays == null || validDays < 1 || validDays > 365) {
            throw PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                    "名称须为 1—100 字符，有效期须为 1—365 天");
        }
        try (var ignored = ownerScope(owner); var acting = ActingContextHolder.without()) {
            return accounts.withPersonalAccessTokenOwner(owner, user -> {
                // Check the session again under the account write lock, including concurrent reset/revocation.
                requireInteractiveSession(owner, sessionToken);
                var authorization = authorizeCreation(owner);
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                PersonalAccessToken token = new PersonalAccessToken();
                token.setUserId(user.getId());
                token.setName(name.trim());
                token.setTokenHash(hash(secret));
                token.setDisplayPrefix(secret.substring(0, PREFIX.length() + 8));
                token.setExpiresAt(clock.instant().plus(Duration.ofDays(validDays)));
                tokens.issue(token);
                audit(owner, CREATE_ACTION, token.getId(), authorization);
                return new IssuedPersonalAccessToken(secret, PersonalAccessTokenView.of(token));
            });
        }
    }

    public boolean canCreate(String sessionToken) {
        CurrentUser owner = requireOwner();
        requireInteractiveSession(owner, sessionToken);
        try {
            authorizeCreation(owner);
            return true;
        } catch (PlatformAccessDeniedException denied) {
            return false;
        }
    }

    private ActionAuthorizationResult authorizeCreation(CurrentUser owner) {
        try (var ignored = ownerScope(owner); var acting = ActingContextHolder.without()) {
            return permissions.authorizeAction(MODULE_ALIAS, CREATE_POLICY, Optional.of(owner));
        }
    }

    public List<PersonalAccessTokenView> list(String sessionToken) {
        CurrentUser owner = requireOwner();
        requireInteractiveSession(owner, sessionToken);
        return tokens.ownedBy(owner.userId()).stream().map(PersonalAccessTokenView::of).toList();
    }

    @Transactional
    public void revoke(String sessionToken, String id) {
        CurrentUser owner = requireOwner();
        requireInteractiveSession(owner, sessionToken);
        // Repeated revocation and unknown/other-owner IDs have the same safe, idempotent outcome.
        if (tokens.revoke(owner.userId(), id, clock.instant())) audit(owner, "revoke", id, new ActionAuthorizationResult("LOGIN_REQUIRED",
                owner.userId(), owner.system() ? "SYSTEM" : "USER", null, null));
    }

    @Transactional
    public void delete(String sessionToken, String id) {
        CurrentUser owner = requireOwner();
        requireInteractiveSession(owner, sessionToken);
        if (tokens.deleteRevoked(owner.userId(), id, clock.instant())) audit(owner, "delete", id,
                new ActionAuthorizationResult("LOGIN_REQUIRED", owner.userId(), owner.system() ? "SYSTEM" : "USER", null, null));
    }

    public AuthenticatedUser authenticate(String secret) {
        if (secret == null || !FORMAT.matcher(secret).matches()) throw rejected("ACCESS_TOKEN_INVALID");
        PersonalAccessToken token = tokens.findByHash(hash(secret));
        if (token == null) throw rejected("ACCESS_TOKEN_INVALID");
        if (token.getRevokedAt() != null) throw rejected("ACCESS_TOKEN_REVOKED");
        if (!clock.instant().isBefore(token.getExpiresAt())) throw rejected("ACCESS_TOKEN_EXPIRED");
        try (var ignored = TenantContext.system("authenticate personal access token owner")) {
            UserAccount owner = accounts.select(token.getUserId());
            if (!available(owner)) throw rejected("ACCESS_TOKEN_OWNER_UNAVAILABLE");
            try (var realm = accountScope(owner)) {
                try {
                    verifyTenant(owner.getTenantId());
                } catch (PlatformException unavailable) {
                    if (unavailable.httpStatus() >= 500) throw unavailable;
                    throw rejected("ACCESS_TOKEN_OWNER_UNAVAILABLE");
                }
                tokens.touch(token, clock.instant());
                return new AuthenticatedUser(identities.currentUserOf(owner, false), METHOD, token.getId());
            }
        }
    }

    private void audit(CurrentUser owner, String action, String id, ActionAuthorizationResult authorization) {
        try (var acting = ActingContextHolder.without()) {
            var event = new RuntimeEvent(null,
                RequestTraceContext.currentTraceId().orElse(null), RuntimeEventType.ACTION_EXECUTED,
                MODULE_ALIAS, "personal_access_token", id, action, owner.tenantId(), false, owner.userId(),
                owner.system() ? "SYSTEM" : "USER", authorization.decision(), authorization.permissionCode(),
                authorization.permissionActionCode(), RuntimeMutationSource.BUSINESS,
                Map.of("resultType", "SUCCESS"), clock.instant());
            events.publishAfterCommit(event);
        }
    }

    private CurrentUser requireOwner() {
        CurrentUser user = CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationRequiredException("请先登录"));
        if (user.passwordChangeRequired()) throw new PlatformAccessDeniedException("请先完成密码修改");
        try (var ignored = TenantContext.system("verify personal access token owner")) {
            var account = accounts.select(user.userId());
            if (!available(account) || !Objects.equals(account.getTenantId(), user.tenantId()))
                throw new PlatformAccessDeniedException("访问密钥所属账号不可用");
        }
        verifyTenant(user.tenantId());
        return user;
    }

    private void verifyTenant(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) tenants.verifyActiveTenant(tenantId);
    }

    private static TenantContext.Scope ownerScope(CurrentUser owner) {
        return owner.system() ? TenantContext.system("personal access token owner realm") : TenantContext.use(owner.tenantId());
    }

    private static TenantContext.Scope accountScope(UserAccount owner) {
        return owner.getTenantId() == null || owner.getTenantId().isBlank()
                ? TenantContext.system("personal access token owner realm") : TenantContext.use(owner.getTenantId());
    }

    private void requireInteractiveSession(CurrentUser owner, String token) {
        try (var ignored = TenantContext.system("verify personal access token management session")) {
            var sessionUser = sessions.currentUserSnapshot(token).orElseThrow(() ->
                    new PlatformAccessDeniedException("请使用网页登录会话管理访问密钥"));
            if (!owner.userId().equals(sessionUser.userId()) || !Objects.equals(owner.tenantId(), sessionUser.tenantId()) || sessionUser.passwordChangeRequired())
                throw new PlatformAccessDeniedException("请使用本人的网页登录会话");
        }
    }

    private static boolean available(UserAccount owner) {
        return owner != null && !Boolean.TRUE.equals(owner.getDeleted()) && Boolean.TRUE.equals(owner.getEnabled());
    }
    private static PlatformException rejected(String code) {
        return error(code, 401, switch (code) {
            case "ACCESS_TOKEN_EXPIRED" -> "访问密钥已过期，请更新工具凭据";
            case "ACCESS_TOKEN_REVOKED" -> "访问密钥已撤销，请更新工具凭据";
            case "ACCESS_TOKEN_OWNER_UNAVAILABLE" -> "访问密钥所属账号不可用";
            default -> "访问密钥无效";
        });
    }
    private static PlatformException error(String code, int status, String message) {
        return new PlatformException(code, status, message, null, ErrorScope.empty(), List.of(), Map.of(), Map.of(),
                TextReference.code("iam.access-token." + code.toLowerCase(Locale.ROOT), message));
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
