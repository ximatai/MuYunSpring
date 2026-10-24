package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class UserSessionService {
    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);
    private static final int TOKEN_BYTES = 32;
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofHours(12);
    private static final Duration SESSION_ABSOLUTE_TTL = Duration.ofDays(7);
    private static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofSeconds(60);
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final UserAccountService userAccountService;
    private final UserSessionRecordService userSessionRecordService;
    private final UserSessionRevocationService userSessionRevocationService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final Supplier<UserSecurityEventPublisher> userSecurityEventPublisher;
    private final Supplier<UserSessionLifecycleEventPublisher> userSessionLifecycleEventPublisher;
    private final Supplier<UserSessionPresenceLookup> userSessionPresenceLookup;
    private final Supplier<LoginAuditLogger> loginAuditLogger;
    private final Clock clock;
    private final UserAccountIdentity identities;
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
        this.identities = new UserAccountIdentity(resolved);
        this.userSessionPresenceLookup = resolved.presenceLookup();
        this.loginAuditLogger = resolved.loginAuditLogger();
    }

    public LoginResult login(String tenantId, String username, String password) {
        return login(tenantId, username, password, null, null);
    }

    public LoginResult login(String tenantId, String username, String password, String ip, String userAgent) {
        LoginAttempt attempt = new LoginAttempt(normalizeBlank(tenantId), username, AuthenticationMethod.PASSWORD,
                ip, userAgent);
        try (TenantContext.Scope ignored = loginTenantScope(attempt.tenantId)) {
            if (attempt.tenantId != null) {
                attempt.reasonCode = "TENANT_UNAVAILABLE";
                verifyActiveTenantForLogin(attempt.tenantId);
            }
            UserAccount user = userAccountService.findUserForLogin(attempt.tenantId, username);
            attempt.user = user;
            if (user == null) {
                throw rejectedWithoutVerification(attempt, password, "UNKNOWN_ACCOUNT");
            }
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                throw rejectedWithoutVerification(attempt, password, "ACCOUNT_UNAVAILABLE");
            }
            Instant attemptedAt = now();
            // A locked account never evaluates the password, so the lock cannot be used as a guessing oracle.
            if (userAccountService.loginLocked(user, attemptedAt)) {
                throw rejectedWithoutVerification(attempt, password, "ACCOUNT_LOCKED");
            }
            if (!userAccountService.hasLocalPassword(user)) {
                throw rejectedWithoutVerification(attempt, password, "PASSWORD_NOT_SET");
            }
            if (!userAccountService.passwordMatches(user, password)) {
                recordLoginFailure(attempt, user, attemptedAt);
                throw attempt.rejected("BAD_CREDENTIALS");
            }
            if (userAccountService.resetPasswordExpired(user, attemptedAt)) {
                recordLoginFailure(attempt, user, attemptedAt);
                throw attempt.rejected("TEMPORARY_PASSWORD_EXPIRED");
            }
            return issueSession(attempt, user);
        } catch (RuntimeException exception) {
            attempt.recordFailureAudit();
            throw exception;
        }
    }

    /**
     * Opens a session for an account whose identity a trusted platform flow has already verified through
     * another authentication method, such as external identity federation.
     *
     * @hidden Internal IAM collaborator: the caller must have verified the identity server-side and resolved
     * it to {@code userId} inside {@code tenantId}; business code signs users in through the HTTP login entries.
     */
    public LoginResult loginWithVerifiedIdentity(String tenantId, String userId, AuthenticationMethod method,
                                                 String claimedAccount, String ip, String userAgent) {
        if (Objects.requireNonNull(method, "method").isPassword()) {
            throw new IllegalArgumentException("password login must verify the password through login()");
        }
        LoginAttempt attempt = new LoginAttempt(normalizeBlank(tenantId), claimedAccount, method, ip, userAgent);
        if (attempt.tenantId == null) {
            // Platform super administrators only sign in with their local password.
            RuntimeException rejected = attempt.rejected("SYSTEM_ACCOUNT_NOT_ALLOWED");
            attempt.recordFailureAudit();
            throw rejected;
        }
        try (TenantContext.Scope ignored = TenantContext.use(attempt.tenantId)) {
            attempt.reasonCode = "TENANT_UNAVAILABLE";
            verifyActiveTenantForLogin(attempt.tenantId);
            UserAccount user = userAccountService.select(userId);
            attempt.user = user;
            if (user == null) {
                throw attempt.rejected("UNKNOWN_ACCOUNT");
            }
            if (!Boolean.TRUE.equals(user.getEnabled())) {
                throw attempt.rejected("ACCOUNT_UNAVAILABLE");
            }
            return issueSession(attempt, user);
        } catch (RuntimeException exception) {
            attempt.recordFailureAudit();
            throw exception;
        }
    }

    /**
     * Whether the session behind {@code token} is active and was issued within {@code window}.
     *
     * @hidden Internal IAM collaborator for recent-authentication checks.
     */
    public boolean authenticatedWithin(String token, Duration window) {
        UserSession session = sessionByToken(token);
        Instant now = now();
        return isActive(session, now) && session.getIssuedAt() != null
                && !session.getIssuedAt().isBefore(now.minus(window));
    }

    /**
     * The single exit through which every authentication method obtains a platform session. Callers verify
     * the credential inside the account's tenant scope; this entry then re-admits the account against its
     * latest stored state before any session exists, so a lock, disablement or password change that raced
     * the credential check still prevents issuance, and records session, audit and lifecycle event the same way for all
     * methods.
     */
    private LoginResult issueSession(LoginAttempt attempt, UserAccount verified) {
        String token = newToken();
        Instant issuedAt = now();
        Instant maxExpiresAt = issuedAt.plus(SESSION_ABSOLUTE_TTL);
        attempt.reasonCode = "ACCOUNT_LOGIN_STATE_WRITE_FAILED";
        // Admission and the session insert share one transaction: the session is built from the admitted
        // latest account, and no session is ever persisted for an account whose admission fails. The login
        // lock defends the local password, so it neither blocks nor is cleared by other methods.
        IssuedSession issued = userAccountService.admitLogin(verified, attempt.method.isPassword(), issuedAt,
                attempt.ip, attempt.userAgent, user -> {
                    attempt.reasonCode = "SESSION_ISSUE_FAILED";
                    // Forced password change only constrains sessions that were opened with the password itself.
                    boolean passwordChangeRequired = attempt.method.isPassword()
                            && userAccountService.passwordChangeRequired(user, issuedAt);
                    CurrentUser currentUser = currentUserOf(user, passwordChangeRequired);
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
                    session.setAuthenticationMethod(attempt.method.code());
                    session.setLoginIp(normalizeBlank(attempt.ip));
                    session.setLoginUserAgent(normalizeBlank(attempt.userAgent));
                    String sessionId = userSessionRecordService.issue(session);
                    if (session.getId() == null || session.getId().isBlank()) {
                        session.setId(sessionId);
                    }
                    return new IssuedSession(user, session.getId(), currentUser, passwordChangeRequired);
                }).orElseThrow(() -> attempt.rejected("ACCOUNT_STATE_CHANGED"));
        attempt.recordSuccessAudit(issuedAt, issued.currentUser().organizationId());
        userSessionLifecycleEventPublisher.get()
                .publish(UserSessionLifecycleEvent.loggedIn(issued.currentUser().userId(), issued.sessionId()));
        return LoginResult.bearer(token, issued.sessionId(), issuedAt, issued.currentUser(),
                issued.passwordChangeRequired(),
                userAccountService.effectivePasswordStatus(issued.account()),
                issued.account().getPasswordExpiresAt());
    }

    private record IssuedSession(UserAccount account, String sessionId, CurrentUser currentUser,
                                 boolean passwordChangeRequired) {
    }

    /** Rejections that skip the password check still spend its cost, so timing does not reveal the account state. */
    private AuthenticationFailedException rejectedWithoutVerification(LoginAttempt attempt, String password, String reason) {
        userAccountService.spendPasswordVerification(password);
        return attempt.rejected(reason);
    }

    private void recordLoginFailure(LoginAttempt attempt, UserAccount user, Instant failedAt) {
        attempt.reasonCode = "ACCOUNT_LOGIN_STATE_WRITE_FAILED";
        userAccountService.recordLoginFailure(user, failedAt);
    }

    /** Audit state of one login attempt; every rejection reaches clients as the same authentication failure. */
    private final class LoginAttempt {
        private final String tenantId;
        private final String claimedAccount;
        private final AuthenticationMethod method;
        private final String ip;
        private final String userAgent;
        private UserAccount user;
        private String reasonCode = "LOGIN_FAILED";
        private boolean auditRecorded;

        private LoginAttempt(String tenantId, String claimedAccount, AuthenticationMethod method,
                             String ip, String userAgent) {
            this.tenantId = tenantId;
            this.claimedAccount = claimedAccount;
            this.method = method;
            this.ip = ip;
            this.userAgent = userAgent;
        }

        private AuthenticationFailedException rejected(String reason) {
            reasonCode = reason;
            return new AuthenticationFailedException("invalid username or password");
        }

        private void recordSuccessAudit(Instant occurredAt, String organizationId) {
            recordLoginAudit(user, tenantId, claimedAccount, method, ip, occurredAt,
                    LoginLogDetails.LoginOutcome.SUCCESS, null, organizationId);
            auditRecorded = true;
        }

        private void recordFailureAudit() {
            if (!auditRecorded) {
                recordLoginAudit(user, tenantId, claimedAccount, method, ip, now(),
                        LoginLogDetails.LoginOutcome.FAILURE, reasonCode, null);
                auditRecorded = true;
            }
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

    private void recordLoginAudit(UserAccount user, String tenantId, String username, AuthenticationMethod method,
                                  String ip, Instant occurredAt, LoginLogDetails.LoginOutcome outcome, String reasonCode,
                                  String operatorOrganizationId) {
        try {
            String confirmedAccount = user == null ? null : user.getUsername();
            String organizationId = operatorOrganizationId == null && user != null
                    ? currentUserOf(user, false).organizationId()
                    : operatorOrganizationId;
            LoginLogEvent event = new LoginLogEvent(
                    new BusinessLogContext(Ids.newId(), occurredAt, now(),
                            logTraceId(), limit(tenantId, 128),
                            user == null ? null : user.getId(), limit(confirmedAccount, 256), organizationId,
                            "iam.user", "login"),
                    new LoginLogDetails(method.code(), outcome, reasonCode, limit(ip, 64),
                            limit(username, 256), limit(confirmedAccount, 256)));
            LoginAuditLogger logger = loginAuditLogger.get();
            (logger == null ? LoginAuditLogger.NOOP : logger).record(event);
        } catch (RuntimeException ignored) {
            // The event and exception may contain credentials; keep the emergency diagnostic fixed and safe.
            log.warn("Login audit publication failed");
        }
    }

    private void rejectCurrentSessionRevoke(UserSession session, String currentToken) {
        String currentTokenHash = currentTokenHash(currentToken);
        if (currentTokenHash != null && currentTokenHash.equals(session.getTokenHash())) {
            throw BusinessExceptions.warningText("iam.user-session.revoke-current-denied",
                TextReference.code("iam.user-session.revoke-current-denied", "不能在用户管理中下线当前登录会话"));
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
        return identities.currentUserOf(user, passwordChangeRequired);
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String logTraceId() {
        String traceId = RequestTraceContext.currentTraceId().orElse(null);
        return traceId != null && traceId.length() <= 128 && TRACE_ID.matcher(traceId).matches() ? traceId : null;
    }

    private String limit(String value, int maximumLength) {
        String normalized = normalizeBlank(value);
        return normalized == null || normalized.length() <= maximumLength
                ? normalized
                : normalized.substring(0, maximumLength);
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
