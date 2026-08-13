package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserTimeZoneResolver;
import net.ximatai.muyun.spring.common.identity.CurrentUserOrganizationResolver;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class UserSessionService {
    private static final int TOKEN_BYTES = 32;
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(12);
    private static final Duration SESSION_ABSOLUTE_TTL = Duration.ofDays(7);
    private static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofSeconds(60);

    private final UserAccountService userAccountService;
    private final UserSessionRecordService userSessionRecordService;
    private final UserSessionRevocationService userSessionRevocationService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final Supplier<UserSecurityEventPublisher> userSecurityEventPublisher;
    private final Supplier<UserSessionLifecycleEventPublisher> userSessionLifecycleEventPublisher;
    private final Supplier<UserSessionPresenceLookup> userSessionPresenceLookup;
    private final Clock clock;
    private final CurrentUserTimeZoneResolver currentUserTimeZoneResolver;
    private final CurrentUserOrganizationResolver currentUserOrganizationResolver;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public UserSessionService(UserAccountService userAccountService,
                              UserSessionRecordService userSessionRecordService,
                              ActiveTenantVerifier activeTenantVerifier,
                              UserSessionCollaborators collaborators,
                              Clock clock) {
        UserSessionCollaborators resolved = collaborators == null ? UserSessionCollaborators.empty() : collaborators;
        Clock effectiveClock = clock == null ? Clock.systemUTC() : clock;
        this.userAccountService = userAccountService;
        this.userSessionRecordService = userSessionRecordService;
        Supplier<UserSessionLifecycleEventPublisher> lifecycleEventPublisher = resolved.lifecycleEventPublisher();
        UserSessionRevocationService revocationService = resolved.revocationService().get();
        this.userSessionRevocationService = revocationService == null
                ? new UserSessionRevocationService(userSessionRecordService, lifecycleEventPublisher, effectiveClock)
                : revocationService;
        this.activeTenantVerifier = activeTenantVerifier;
        this.userSecurityEventPublisher = resolved.securityEventPublisher();
        this.userSessionLifecycleEventPublisher = lifecycleEventPublisher;
        this.clock = effectiveClock;
        this.currentUserTimeZoneResolver = resolved.timeZoneResolver();
        this.currentUserOrganizationResolver = resolved.organizationResolver();
        this.userSessionPresenceLookup = resolved.presenceLookup();
    }

    public LoginResult login(String tenantId, String username, String password) {
        return login(tenantId, username, password, null, null);
    }

    public LoginResult login(String tenantId, String username, String password, String ip, String userAgent) {
        String normalizedTenantId = normalizeBlank(tenantId);
        try (TenantContext.Scope ignored = loginTenantScope(normalizedTenantId)) {
            if (normalizedTenantId != null) {
                verifyActiveTenantForLogin(normalizedTenantId);
            }
            UserAccount user = userAccountService.requireActiveUser(normalizedTenantId, username);
            if (!userAccountService.passwordMatches(user, password)) {
                userAccountService.recordLoginFailure(user, now());
                throw new AuthenticationFailedException("invalid username or password");
            }
            String token = newToken();
            Instant issuedAt = now();
            if (userAccountService.resetPasswordExpired(user, issuedAt)) {
                userAccountService.recordLoginFailure(user, issuedAt);
                throw new AuthenticationFailedException("temporary password expired");
            }
            boolean passwordChangeRequired = userAccountService.passwordChangeRequired(user, issuedAt);
            CurrentUser currentUser = currentUserOf(user, passwordChangeRequired);
            Instant maxExpiresAt = issuedAt.plus(SESSION_ABSOLUTE_TTL);
            UserSession session = new UserSession();
            session.setTenantId(currentUser.tenantId());
            session.setUserId(currentUser.userId());
            session.setUsername(currentUser.username());
            session.setOrganizationId(currentUser.organizationId());
            session.setTokenHash(tokenHash(token));
            session.setIssuedAt(issuedAt);
            session.setExpiresAt(nextIdleExpiresAt(issuedAt, maxExpiresAt));
            session.setMaxExpiresAt(maxExpiresAt);
            session.setLastSeenAt(issuedAt);
            session.setPasswordChangeRequired(passwordChangeRequired);
            session.setLoginIp(normalizeBlank(ip));
            session.setLoginUserAgent(normalizeBlank(userAgent));
            String sessionId = userSessionRecordService.issue(session);
            if (session.getId() == null || session.getId().isBlank()) {
                session.setId(sessionId);
            }
            userAccountService.recordLoginSuccess(user.getId(), issuedAt, ip, userAgent);
            userSessionLifecycleEventPublisher.get()
                    .publish(UserSessionLifecycleEvent.loggedIn(currentUser.userId(), session.getId()));
            return LoginResult.bearer(token, session.getId(), issuedAt, currentUser,
                    passwordChangeRequired,
                    userAccountService.effectivePasswordStatus(user),
                    user.getPasswordExpiresAt());
        }
    }

    public Optional<CurrentUser> currentUser(String token) {
        return currentUser(token, true);
    }

    public Optional<CurrentUser> currentUserSnapshot(String token) {
        return currentUser(token, false);
    }

    private Optional<CurrentUser> currentUser(String token, boolean touchSession) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        UserSession session = sessionByToken(token);
        if (session == null || session.getRevokedAt() != null) {
            return Optional.empty();
        }
        Instant now = now();
        if (isExpired(session, now)) {
            if (touchSession) {
                userSessionRevocationService.revoke(session, now, "session expired");
            }
            return Optional.empty();
        }
        try (TenantContext.Scope ignored = sessionTenantScope(session.getTenantId())) {
            if (!verifyActiveTenantForSession(session, now, touchSession)) {
                return Optional.empty();
            }
            UserAccount user = userAccountService.select(session.getUserId());
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
                if (touchSession) {
                    userSessionRevocationService.revoke(session, now, "user inactive");
                }
                return Optional.empty();
            }
            if (touchSession && !updateLastSeenIfDue(session, now)) {
                return Optional.empty();
            }
            return Optional.of(currentUserOf(user, Boolean.TRUE.equals(session.getPasswordChangeRequired())));
        }
    }

    public void logout(String token) {
        UserSession session = sessionByToken(token);
        if (session != null) {
            userSessionRevocationService.logout(session, now());
        }
    }

    public int changeOwnPassword(String userId, String currentPassword, String newPassword) {
        return userAccountService.changeOwnPassword(userId, currentPassword, newPassword);
    }

    private void verifyActiveTenantForLogin(String tenantId) {
        try {
            activeTenantVerifier.verifyActiveTenant(tenantId);
        } catch (PlatformException exception) {
            throw new AuthenticationFailedException("invalid username or password", exception);
        }
    }

    private boolean verifyActiveTenantForSession(UserSession session, Instant now, boolean revokeWhenInactive) {
        if (session.getTenantId() == null || session.getTenantId().isBlank()) {
            return true;
        }
        try {
            activeTenantVerifier.verifyActiveTenant(session.getTenantId());
            return true;
        } catch (PlatformException exception) {
            if (revokeWhenInactive) {
                userSessionRevocationService.revoke(session, now, "tenant inactive");
            }
            return false;
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public int revokeUserSessions(String userId) {
        return userSessionRevocationService.revokeUserSessions(userId, "user sessions revoked");
    }

    public List<UserSessionView> activeSessionsOfUser(String userId, String currentToken) {
        String validUserId = normalizeBlank(userId);
        if (validUserId == null) {
            return List.of();
        }
        Instant now = now();
        String currentTokenHash = currentTokenHash(currentToken);
        UserSessionPresenceLookup presenceLookup = userSessionPresenceLookup();
        return userSessionRecordService.listByUserId(validUserId).stream()
                .filter(session -> isActive(session, now))
                .map(session -> UserSessionView.from(session, currentTokenHash != null
                        && currentTokenHash.equals(session.getTokenHash()),
                        presenceLookup.presenceOf(session.getId()), now))
                .toList();
    }

    public List<UserSessionStatusView> activeSessionStatuses(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Instant now = now();
        return userIds.stream()
                .map(this::normalizeBlank)
                .filter(userId -> userId != null)
                .distinct()
                .map(userId -> {
                    List<UserSession> activeSessions = userSessionRecordService.listByUserId(userId).stream()
                            .filter(session -> isActive(session, now))
                            .toList();
                    UserSessionPresenceLookup presenceLookup = userSessionPresenceLookup();
                    List<SessionPresenceSnapshot> snapshots = activeSessions.stream()
                            .map(session -> new SessionPresenceSnapshot(session,
                                    presenceLookup.presenceOf(session.getId())))
                            .toList();
                    long presentCount = snapshots.stream()
                            .map(SessionPresenceSnapshot::presence)
                            .filter(UserSessionPresence::present)
                            .count();
                    long idleCount = snapshots.stream()
                            .filter(snapshot -> snapshot.presence().idleSince(snapshot.session().getLastSeenAt(), now))
                            .count();
                    return new UserSessionStatusView(userId, !activeSessions.isEmpty(), activeSessions.size(),
                            presentCount > 0, presentCount, idleCount);
                })
                .toList();
    }

    public UserSessionStatusView activeSessionStatus(String userId) {
        String validUserId = normalizeBlank(userId);
        if (validUserId == null) {
            return new UserSessionStatusView(null, false, 0, false, 0, 0);
        }
        return activeSessionStatuses(List.of(validUserId)).stream()
                .findFirst()
                .orElse(new UserSessionStatusView(validUserId, false, 0, false, 0, 0));
    }

    public Optional<String> currentSessionId(String token) {
        UserSession session = sessionByToken(token);
        if (!isActive(session, now())) {
            return Optional.empty();
        }
        return Optional.ofNullable(normalizeBlank(session.getId()));
    }

    public boolean sessionIdleSince(String sessionId, Instant lastObservedAt, Instant now) {
        UserSession session = sessionById(sessionId);
        if (!isActive(session, now)) {
            return false;
        }
        return new UserSessionPresence(session.getId(), true, 1, null, lastObservedAt)
                .idleSince(session.getLastSeenAt(), now);
    }

    public int revokeUserSession(String userId, String sessionId, String currentToken) {
        String validUserId = normalizeBlank(userId);
        String validSessionId = normalizeBlank(sessionId);
        if (validUserId == null || validSessionId == null) {
            return 0;
        }
        UserSession session = userSessionRecordService.findById(validSessionId);
        if (session == null || !validUserId.equals(session.getUserId())) {
            return 0;
        }
        rejectCurrentSessionRevoke(session, currentToken);
        Instant now = now();
        if (!isActive(session, now)) {
            return 0;
        }
        if (!userSessionRevocationService.revoke(session, now, "user session revoked by administrator")) {
            return 0;
        }
        userSecurityEventPublisher.get().publish(UserSecurityEvent.sessionRevoked(validUserId, validSessionId));
        return 1;
    }

    public int revokeUserSessions(String userId, List<String> sessionIds, String currentToken) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String sessionId : sessionIds) {
            count += revokeUserSession(userId, sessionId, currentToken);
        }
        return count;
    }

    private UserSession sessionByToken(String token) {
        String normalized = normalizeToken(token);
        if (normalized == null) {
            return null;
        }
        return userSessionRecordService.findByTokenHash(tokenHash(normalized));
    }

    private String currentTokenHash(String token) {
        String normalized = normalizeToken(token);
        return normalized == null ? null : tokenHash(normalized);
    }

    private String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private boolean updateLastSeenIfDue(UserSession session, Instant now) {
        Instant lastSeenAt = session.getLastSeenAt();
        if (lastSeenAt != null && lastSeenAt.plus(LAST_SEEN_WRITE_INTERVAL).isAfter(now)) {
            return true;
        }
        Integer expectedVersion = session.getVersion();
        session.setLastSeenAt(now);
        session.setMaxExpiresAt(effectiveMaxExpiresAt(session));
        session.setExpiresAt(nextIdleExpiresAt(now, session.getMaxExpiresAt()));
        int updated = userSessionRecordService.updateSession(session, expectedVersion, now);
        if (updated > 0) {
            return true;
        }
        UserSession latest = sessionById(session.getId());
        return latest != null && latest.getRevokedAt() == null && !isExpired(latest, now);
    }

    private boolean isExpired(UserSession session, Instant now) {
        return !now.isBefore(session.getExpiresAt()) || !now.isBefore(effectiveMaxExpiresAt(session));
    }

    private boolean isActive(UserSession session, Instant now) {
        return session != null && session.getRevokedAt() == null && !isExpired(session, now);
    }

    private UserSessionPresenceLookup userSessionPresenceLookup() {
        UserSessionPresenceLookup lookup = userSessionPresenceLookup.get();
        return lookup == null ? UserSessionPresenceLookup.NONE : lookup;
    }

    private void rejectCurrentSessionRevoke(UserSession session, String currentToken) {
        String currentTokenHash = currentTokenHash(currentToken);
        if (currentTokenHash != null && currentTokenHash.equals(session.getTokenHash())) {
            throw BusinessExceptions.warning("iam.user-session.revoke-current-denied",
                    "不能在用户管理中下线当前登录会话");
        }
    }

    private Instant effectiveMaxExpiresAt(UserSession session) {
        if (session.getMaxExpiresAt() != null) {
            return session.getMaxExpiresAt();
        }
        if (session.getIssuedAt() != null) {
            return session.getIssuedAt().plus(SESSION_ABSOLUTE_TTL);
        }
        return session.getExpiresAt();
    }

    private Instant nextIdleExpiresAt(Instant now, Instant maxExpiresAt) {
        Instant idleExpiresAt = now.plus(SESSION_IDLE_TIMEOUT);
        return idleExpiresAt.isBefore(maxExpiresAt) ? idleExpiresAt : maxExpiresAt;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private TenantContext.Scope loginTenantScope(String tenantId) {
        return tenantId == null
                ? TenantContext.system("system user login")
                : TenantContext.use(tenantId);
    }

    private TenantContext.Scope sessionTenantScope(String tenantId) {
        return tenantId == null || tenantId.isBlank()
                ? TenantContext.system("system user session")
                : TenantContext.use(tenantId);
    }

    private CurrentUser currentUserOf(UserAccount user, boolean passwordChangeRequired) {
        CurrentUser currentUser;
        if (user.getTenantId() == null || user.getTenantId().isBlank()) {
            currentUser = CurrentUser.systemUser(user.getId(), user.getUsername(), passwordChangeRequired);
        } else {
            currentUser = CurrentUser.tenantUser(user.getId(), user.getUsername(), user.getTenantId(),
                    null, passwordChangeRequired);
        }
        currentUser = currentUserOrganizationResolver.resolveOrganizationId(currentUser)
                .map(currentUser::withOrganizationId)
                .orElse(currentUser);
        CurrentUser resolvedCurrentUser = currentUser;
        return currentUserTimeZoneResolver.resolveZoneId(resolvedCurrentUser)
                .map(zoneId -> resolvedCurrentUser.withTimeZone(zoneId.getId()))
                .orElse(resolvedCurrentUser);
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UserSession sessionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return userSessionRecordService.findById(id);
    }

    private record SessionPresenceSnapshot(UserSession session, UserSessionPresence presence) {
    }
}
