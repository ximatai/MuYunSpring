package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserTimeZoneResolver;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionServiceTest {
    private final PasswordHashingService passwordHashingService = new PasswordHashingService();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldCreateUserWithHashedPasswordAndLoginAsCurrentUser() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.insert(any())).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            user.setId("user-1");
            return "user-1";
        });
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);

        UserAccount user = new UserAccount();
        user.setUsername("alice");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            userService.createUser(user, "secret1");
        }

        assertThat(user.getPasswordHash()).startsWith("pbkdf2$");
        assertThat(user.getPasswordHash()).doesNotContain("secret1");

        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        AtomicReference<UserSession> persistedSession = captureInsertedSession(sessionDao);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);
        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");

        assertThat(login.tokenType()).isEqualTo("Bearer");
        assertThat(login.sessionId()).isEqualTo(persistedSession.get().getId());
        assertThat(login.sessionId()).isNotBlank();
        assertThat(login.issuedAt()).isEqualTo(clock.instant());
        assertThat(login.currentUser()).isEqualTo(
                CurrentUser.tenantUser("user-1", "alice", "tenant-a", null, true));
        assertThat(login.passwordChangeRequired()).isTrue();
        assertThat(login.passwordStatus()).isEqualTo(PasswordStatus.INITIAL);
        assertThat(persistedSession.get().getTenantId()).isEqualTo("tenant-a");
        assertThat(persistedSession.get().getUserId()).isEqualTo("user-1");
        assertThat(persistedSession.get().getPasswordChangeRequired()).isTrue();
        assertThat(persistedSession.get().getLoginIp()).isNull();
        assertThat(persistedSession.get().getLoginUserAgent()).isNull();
        assertThat(persistedSession.get().getTokenHash()).hasSize(64);
        assertThat(persistedSession.get().getTokenHash()).isNotEqualTo(login.token());
        assertThat(lifecycleEventPublisher.events)
                .containsExactly(UserSessionLifecycleEvent.loggedIn("user-1", login.sessionId()));
        assertThat(persistedSession.get().getExpiresAt()).isEqualTo(clock.instant().plusSeconds(43_200));
        assertThat(persistedSession.get().getMaxExpiresAt()).isEqualTo(clock.instant().plusSeconds(604_800));
        persistedSession.get().setLastSeenAt(clock.instant().minusSeconds(120));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(persistedSession.get()));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        assertThat(sessionService.currentUser(login.token())).contains(login.currentUser());
        assertThat(user.getLastLoginAt()).isEqualTo(clock.instant());
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(persistedSession.get().getLastSeenAt()).isEqualTo(clock.instant());
        verify(sessionDao).updateByIdAndVersion(persistedSession.get(), 0);
    }

    @Test
    void shouldNormalizeSessionTimesToDatabasePrecision() {
        Instant preciseNow = Instant.parse("2026-06-20T00:00:00.123456789Z");
        Clock preciseClock = Clock.fixed(preciseNow, ZoneOffset.UTC);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        AtomicReference<UserSession> persistedSession = captureInsertedSession(sessionDao);
        UserSessionService sessionService = sessionService(userService, sessionDao, preciseClock);

        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");

        Instant databaseNow = Instant.parse("2026-06-20T00:00:00.123456Z");
        assertThat(login.issuedAt()).isEqualTo(databaseNow);
        assertThat(persistedSession.get().getIssuedAt()).isEqualTo(databaseNow);
        assertThat(persistedSession.get().getLastSeenAt()).isEqualTo(databaseNow);
        assertThat(persistedSession.get().getExpiresAt()).isEqualTo(databaseNow.plusSeconds(43_200));
        assertThat(persistedSession.get().getMaxExpiresAt()).isEqualTo(databaseNow.plusSeconds(604_800));
    }

    @Test
    void shouldExposeResolvedDisplayTimeZoneOnCurrentUser() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        captureInsertedSession(sessionDao);
        CurrentUserTimeZoneResolver timeZoneResolver = currentUser -> Optional.of(ZoneId.of("Asia/Shanghai"));
        UserSessionService sessionService = sessionService(userService, sessionDao, clock, timeZoneResolver);

        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");

        assertThat(login.currentUser().timeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void shouldNotExposeDisplayTimeZoneWithoutResolver() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        captureInsertedSession(sessionDao);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");

        assertThat(login.currentUser().timeZone()).isNull();
    }

    @Test
    void shouldDropSessionWhenUserIsNoLongerActive() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount enabled = activeUser();
        UserAccount disabled = activeUser();
        disabled.setEnabled(Boolean.FALSE);
        when(dao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(enabled))
                .thenReturn(List.of(disabled));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        AtomicReference<UserSession> persistedSession = captureInsertedSession(sessionDao);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);

        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");

        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(persistedSession.get()));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        assertThat(sessionService.currentUser(login.token())).isEmpty();
        assertThat(persistedSession.get().getRevokedAt()).isEqualTo(clock.instant());
        assertThat(persistedSession.get().getRevokedReason()).isEqualTo("user inactive");
        assertThat(lifecycleEventPublisher.events).contains(
                UserSessionLifecycleEvent.revoked("user-1", persistedSession.get().getId()));
    }

    @Test
    void shouldRejectLoginWhenTenantIsNoLongerActive() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
            throw new PlatformException("Tenant is not active: " + tenantId);
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThatThrownBy(() -> sessionService.login("tenant-a", "alice", "secret1"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("invalid username or password");
        verify(dao, never()).query(any(Criteria.class), any(PageRequest.class));
        verify(sessionDao, never()).insert(any());
    }

    @Test
    void shouldRevokeSessionWhenTenantIsNoLongerActive() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
            throw new PlatformException("Tenant is not active: " + tenantId);
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);

        assertThat(sessionService.currentUser("token-1")).isEmpty();

        assertThat(session.getRevokedAt()).isEqualTo(clock.instant());
        assertThat(session.getRevokedReason()).isEqualTo("tenant inactive");
        assertThat(lifecycleEventPublisher.events)
                .containsExactly(UserSessionLifecycleEvent.revoked("user-1", "session-1"));
        verify(dao, never()).query(any(Criteria.class), any(PageRequest.class));
        verify(sessionDao).updateByIdAndVersion(session, 0);
    }

    @Test
    void shouldRejectInvalidPasswordWithoutIssuingSession() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccount user = new UserAccount();
        user.setId("user-1");
        user.setTenantId("tenant-a");
        user.setUsername("alice");
        user.setTitle("Alice");
        user.setEnabled(Boolean.TRUE);
        user.setPasswordHash(passwordHashingService.hash("secret1"));
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThatThrownBy(() -> sessionService.login("tenant-a", "alice", "wrong-password"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("invalid username or password");
        assertThat(user.getLastFailedLoginAt()).isEqualTo(clock.instant());
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        verify(dao).updateByIdAndVersion(user, 0);
        verify(sessionDao, never()).insert(any());
    }

    @Test
    void shouldReturnPasswordChangeRequiredForInitialPassword() {
        UserAccount user = activeUser();
        user.setPasswordStatus(PasswordStatus.INITIAL);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        AtomicReference<UserSession> persistedSession = captureInsertedSession(sessionDao);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        LoginResult login = sessionService.login("tenant-a", "alice", "secret1", "127.0.0.1", "Browser");

        assertThat(login.passwordChangeRequired()).isTrue();
        assertThat(login.passwordStatus()).isEqualTo(PasswordStatus.INITIAL);
        assertThat(persistedSession.get().getLoginIp()).isEqualTo("127.0.0.1");
        assertThat(persistedSession.get().getLoginUserAgent()).isEqualTo("Browser");
        assertThat(user.getLastLoginIp()).isEqualTo("127.0.0.1");
        assertThat(user.getLastLoginUserAgent()).isEqualTo("Browser");
    }

    @Test
    void shouldRejectExpiredResetPasswordWithoutIssuingSession() {
        UserAccount user = activeUser();
        user.setPasswordStatus(PasswordStatus.RESET_REQUIRED);
        user.setPasswordExpiresAt(clock.instant().minusSeconds(1));
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThatThrownBy(() -> sessionService.login("tenant-a", "alice", "secret1"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("temporary password expired");

        assertThat(user.getLastFailedLoginAt()).isEqualTo(clock.instant());
        verify(sessionDao, never()).insert(any());
    }

    @Test
    void shouldNotLoginTenantUserFromSystemWorkspace() {
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThatThrownBy(() -> sessionService.login(null, "admin", "secret1"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("invalid username or password");

        verify(sessionDao, never()).insert(any());
    }

    @Test
    void shouldRejectExpiredSession() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession expired = activeSession("session-1", "user-1");
        expired.setExpiresAt(clock.instant().minusSeconds(1));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(expired));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);

        assertThat(sessionService.currentUser("token-1")).isEmpty();
        assertThat(expired.getRevokedAt()).isEqualTo(clock.instant());
        assertThat(expired.getRevokedReason()).isEqualTo("session expired");
        assertThat(lifecycleEventPublisher.events)
                .containsExactly(UserSessionLifecycleEvent.revoked("user-1", "session-1"));
        verify(dao, never()).query(any(Criteria.class), any(PageRequest.class));
        verify(sessionDao).updateByIdAndVersion(expired, 0);
    }

    @Test
    void shouldPublishLifecycleEventWhenUserLogsOut() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);

        sessionService.logout("token-1");

        assertThat(session.getRevokedAt()).isEqualTo(clock.instant());
        assertThat(session.getRevokedReason()).isEqualTo("logout");
        assertThat(lifecycleEventPublisher.events)
                .containsExactly(UserSessionLifecycleEvent.loggedOut("user-1", "session-1"));
        verify(sessionDao).updateByIdAndVersion(session, 0);
    }

    @Test
    void shouldExtendSessionIdleExpirationOnAccessWithoutPassingAbsoluteExpiration() {
        Clock accessClock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        session.setIssuedAt(Instant.parse("2026-06-20T00:00:00Z"));
        session.setVersion(3);
        session.setLastSeenAt(accessClock.instant().minusSeconds(120));
        session.setExpiresAt(accessClock.instant().plusSeconds(600));
        session.setMaxExpiresAt(accessClock.instant().plusSeconds(3600));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        UserSessionService sessionService = sessionService(userService, sessionDao, accessClock);

        assertThat(sessionService.currentUser("token-1")).contains(
                CurrentUser.tenantUser("user-1", "alice", "tenant-a"));

        assertThat(session.getLastSeenAt()).isEqualTo(accessClock.instant());
        assertThat(session.getExpiresAt()).isEqualTo(session.getMaxExpiresAt());
        verify(sessionDao).updateByIdAndVersion(session, 3);
    }

    @Test
    void shouldResolveCurrentUserSnapshotWithoutTouchingSession() {
        Clock accessClock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        Instant originalLastSeenAt = accessClock.instant().minusSeconds(120);
        Instant originalExpiresAt = accessClock.instant().plusSeconds(600);
        session.setVersion(3);
        session.setIssuedAt(Instant.parse("2026-06-20T00:00:00Z"));
        session.setLastSeenAt(originalLastSeenAt);
        session.setExpiresAt(originalExpiresAt);
        session.setMaxExpiresAt(accessClock.instant().plusSeconds(3600));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        UserSessionService sessionService = sessionService(userService, sessionDao, accessClock);

        assertThat(sessionService.currentUserSnapshot("token-1")).contains(
                CurrentUser.tenantUser("user-1", "alice", "tenant-a"));

        assertThat(session.getLastSeenAt()).isEqualTo(originalLastSeenAt);
        assertThat(session.getExpiresAt()).isEqualTo(originalExpiresAt);
        verify(sessionDao, never()).updateByIdAndVersion(any(UserSession.class), any());
    }

    @Test
    void shouldResolveRestrictedCurrentUserFromPasswordChangeRequiredSession() {
        Clock accessClock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        session.setPasswordChangeRequired(Boolean.TRUE);
        session.setIssuedAt(Instant.parse("2026-06-20T00:00:00Z"));
        session.setLastSeenAt(accessClock.instant());
        session.setExpiresAt(accessClock.instant().plusSeconds(600));
        session.setMaxExpiresAt(accessClock.instant().plusSeconds(3600));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        UserSessionService sessionService = sessionService(userService, sessionDao, accessClock);

        assertThat(sessionService.currentUser("token-1")).contains(
                CurrentUser.tenantUser("user-1", "alice", "tenant-a", null, true));
    }

    @Test
    void shouldRejectCurrentUserWhenLastSeenRefreshConflictsWithConcurrentRevoke() {
        Clock accessClock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), ZoneOffset.UTC);
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(activeUser()));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession stale = activeSession("session-1", "user-1");
        stale.setVersion(5);
        stale.setExpiresAt(accessClock.instant().plusSeconds(3600));
        stale.setMaxExpiresAt(accessClock.instant().plusSeconds(7200));
        stale.setLastSeenAt(accessClock.instant().minusSeconds(120));
        UserSession revoked = activeSession("session-1", "user-1");
        revoked.setVersion(6);
        revoked.setExpiresAt(accessClock.instant().plusSeconds(3600));
        revoked.setMaxExpiresAt(accessClock.instant().plusSeconds(7200));
        revoked.setRevokedAt(accessClock.instant().minusSeconds(1));
        revoked.setRevokedReason("logout");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(stale))
                .thenReturn(List.of(revoked));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(0);
        UserSessionService sessionService = sessionService(userService, sessionDao, accessClock);

        assertThat(sessionService.currentUser("token-1")).isEmpty();

        verify(sessionDao).updateByIdAndVersion(stale, 5);
    }

    @Test
    void shouldAllowMultipleSessionsAndRevokeAllUserSessions() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession web = activeSession("session-web", "user-1");
        UserSession mobile = activeSession("session-mobile", "user-1");
        UserSession revoked = activeSession("session-old", "user-1");
        revoked.setRevokedAt(clock.instant().minusSeconds(60));
        revoked.setRevokedReason("logout");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(web, mobile, revoked));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao,
                UserSecurityEventPublisher.NOOP, lifecycleEventPublisher, clock);

        sessionService.revokeUserSessions("user-1");

        assertThat(web.getRevokedReason()).isEqualTo("user sessions revoked");
        assertThat(mobile.getRevokedReason()).isEqualTo("user sessions revoked");
        assertThat(revoked.getRevokedReason()).isEqualTo("logout");
        assertThat(lifecycleEventPublisher.events).containsExactly(
                UserSessionLifecycleEvent.revoked("user-1", "session-web"),
                UserSessionLifecycleEvent.revoked("user-1", "session-mobile")
        );
        verify(sessionDao).updateByIdAndVersion(web, 0);
        verify(sessionDao).updateByIdAndVersion(mobile, 0);
    }

    @Test
    void shouldListActiveSessionsOfUserWithoutTokenHash() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession active = activeSession("session-active", "user-1");
        active.setLoginIp("127.0.0.1");
        active.setLoginUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/150.0.0.0 Safari/537.36");
        UserSession expired = activeSession("session-expired", "user-1");
        expired.setExpiresAt(clock.instant().minusSeconds(1));
        UserSession revoked = activeSession("session-revoked", "user-1");
        revoked.setRevokedAt(clock.instant().minusSeconds(1));
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(active, expired, revoked));
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThat(sessionService.activeSessionsOfUser("user-1", null))
                .containsExactly(new UserSessionView(
                        "session-active",
                        "user-1",
                        "alice",
                        "tenant-a",
                        "org-1",
                        active.getIssuedAt(),
                        active.getExpiresAt(),
                        active.getMaxExpiresAt(),
                        active.getLastSeenAt(),
                        active.getPasswordChangeRequired(),
                        "127.0.0.1",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/150.0.0.0 Safari/537.36",
                        "desktopWeb",
                        "Web 桌面端",
                        "macos",
                        "macOS",
                        false,
                        false,
                        "offline",
                        "离线",
                        0,
                        null,
                        null
                ));
    }

    @Test
    void shouldAttachPresenceToActiveSessionsAndStatuses() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession active = activeSession("session-active", "user-1");
        UserSession idle = activeSession("session-idle", "user-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(active, idle));
        Instant connectedAt = clock.instant().minusSeconds(30);
        Instant observedAt = clock.instant().minusSeconds(5);
        UserSessionPresenceLookup presenceLookup = sessionId -> "session-active".equals(sessionId)
                ? new UserSessionPresence(sessionId, true, 2, connectedAt, observedAt)
                : UserSessionPresence.absent(sessionId);
        UserSessionService sessionService = sessionService(userService, new UserSessionRecordService(sessionDao),
                userService, null, () -> UserSecurityEventPublisher.NOOP,
                () -> UserSessionLifecycleEventPublisher.NOOP, clock,
                null, () -> presenceLookup);

        List<UserSessionView> sessions = sessionService.activeSessionsOfUser("user-1", null);
        List<UserSessionStatusView> statuses = sessionService.activeSessionStatuses(List.of("user-1"));

        assertThat(sessions).extracting(UserSessionView::id).containsExactly("session-active", "session-idle");
        assertThat(sessions.get(0).present()).isTrue();
        assertThat(sessions.get(0).presenceStatus()).isEqualTo("online");
        assertThat(sessions.get(0).connectionCount()).isEqualTo(2);
        assertThat(sessions.get(0).lastConnectedAt()).isEqualTo(connectedAt);
        assertThat(sessions.get(0).lastObservedAt()).isEqualTo(observedAt);
        assertThat(sessions.get(1).present()).isFalse();
        assertThat(statuses).containsExactly(new UserSessionStatusView("user-1", true, 2, true, 1, 0));
    }

    @Test
    void shouldMarkConnectedSessionIdleAfterThreeMinutesWithoutObservedActivity() {
        UserSession session = activeSession("session-idle", "user-1");
        session.setLastSeenAt(clock.instant().minus(UserSessionPresence.IDLE_TIMEOUT));
        Instant observedAt = clock.instant().minus(UserSessionPresence.IDLE_TIMEOUT);
        UserSessionView view = UserSessionView.from(session, false,
                new UserSessionPresence(session.getId(), true, 1, clock.instant().minusSeconds(240), observedAt),
                clock.instant());

        assertThat(view.present()).isTrue();
        assertThat(view.presenceStatus()).isEqualTo("idle");
        assertThat(view.presenceStatusTitle()).isEqualTo("闲置");
    }

    @Test
    void shouldKeepConnectedSessionIdleWhenOnlyHttpActivityIsRecentAfterObservedActivity() {
        UserSession session = activeSession("session-active", "user-1");
        session.setLastSeenAt(clock.instant().minusSeconds(30));
        Instant observedAt = clock.instant().minus(UserSessionPresence.IDLE_TIMEOUT);
        UserSessionView view = UserSessionView.from(session, false,
                new UserSessionPresence(session.getId(), true, 1, clock.instant().minusSeconds(240), observedAt),
                clock.instant());

        assertThat(view.presenceStatus()).isEqualTo("idle");
        assertThat(view.presenceStatusTitle()).isEqualTo("闲置");
    }

    @Test
    void shouldUseHttpActivityAsFallbackWhenObservedActivityIsMissing() {
        UserSession session = activeSession("session-active", "user-1");
        session.setLastSeenAt(clock.instant().minusSeconds(30));
        UserSessionView view = UserSessionView.from(session, false,
                new UserSessionPresence(session.getId(), true, 1, clock.instant().minusSeconds(240), null),
                clock.instant());

        assertThat(view.presenceStatus()).isEqualTo("online");
        assertThat(view.presenceStatusTitle()).isEqualTo("使用中");
    }

    @Test
    void shouldClassifySessionTerminalTypeFromUserAgent() {
        UserSession desktop = activeSession("desktop", "user-1");
        desktop.setLoginUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/150.0.0.0 Safari/537.36");
        UserSession windows = activeSession("windows", "user-1");
        windows.setLoginUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/150.0.0.0 Safari/537.36");
        UserSession mobile = activeSession("mobile", "user-1");
        mobile.setLoginUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148 Safari/604.1");
        UserSession android = activeSession("android", "user-1");
        android.setLoginUserAgent("Mozilla/5.0 (Linux; Android 15; Pixel 9) Mobile Safari/537.36");
        UserSession tablet = activeSession("tablet", "user-1");
        tablet.setLoginUserAgent("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) Version/18.0 Safari/604.1");
        UserSession desktopApp = activeSession("desktop-app", "user-1");
        desktopApp.setLoginUserAgent("Mozilla/5.0 Electron/30.0.0 Chrome/124.0.0.0");
        UserSession mobileApp = activeSession("mobile-app", "user-1");
        mobileApp.setLoginUserAgent("okhttp/4.12.0");

        assertThat(UserSessionView.from(desktop, false).terminalTypeTitle()).isEqualTo("Web 桌面端");
        assertThat(UserSessionView.from(desktop, false).platformTypeTitle()).isEqualTo("macOS");
        assertThat(UserSessionView.from(windows, false).platformTypeTitle()).isEqualTo("Windows");
        assertThat(UserSessionView.from(mobile, false).terminalTypeTitle()).isEqualTo("Web 移动端");
        assertThat(UserSessionView.from(mobile, false).platformTypeTitle()).isEqualTo("iOS");
        assertThat(UserSessionView.from(android, false).platformTypeTitle()).isEqualTo("Android");
        assertThat(UserSessionView.from(tablet, false).terminalTypeTitle()).isEqualTo("Web 平板端");
        assertThat(UserSessionView.from(tablet, false).platformTypeTitle()).isEqualTo("iOS");
        assertThat(UserSessionView.from(desktopApp, false).terminalTypeTitle()).isEqualTo("桌面客户端");
        assertThat(UserSessionView.from(mobileApp, false).terminalTypeTitle()).isEqualTo("移动客户端");
    }

    @Test
    void shouldRevokeSingleUserSession() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        RecordingUserSecurityEventPublisher eventPublisher = new RecordingUserSecurityEventPublisher();
        RecordingUserSessionLifecycleEventPublisher lifecycleEventPublisher =
                new RecordingUserSessionLifecycleEventPublisher();
        UserSessionService sessionService = sessionService(userService, sessionDao, eventPublisher,
                lifecycleEventPublisher, clock);

        assertThat(sessionService.revokeUserSession("user-1", "session-1", null)).isEqualTo(1);

        assertThat(session.getRevokedAt()).isEqualTo(clock.instant());
        assertThat(session.getRevokedReason()).isEqualTo("user session revoked by administrator");
        assertThat(eventPublisher.events).containsExactly(UserSecurityEvent.sessionRevoked("user-1", "session-1"));
        assertThat(lifecycleEventPublisher.events)
                .containsExactly(UserSessionLifecycleEvent.revoked("user-1", "session-1"));
        verify(sessionDao).updateByIdAndVersion(session, 0);
    }

    @Test
    void shouldIgnoreSessionThatDoesNotBelongToUser() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession session = activeSession("session-1", "user-2");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(session));
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThat(sessionService.revokeUserSession("user-1", "session-1", null)).isZero();

        verify(sessionDao, never()).updateByIdAndVersion(any(UserSession.class), any());
    }

    @Test
    void shouldRejectRevokingCurrentSessionFromUserManagement() {
        UserAccount user = activeUser();
        UserAccountDao dao = mock(UserAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(user));
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        AtomicReference<UserSession> persistedSession = captureInsertedSession(sessionDao);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);
        LoginResult login = sessionService.login("tenant-a", "alice", "secret1");
        persistedSession.get().setId("session-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(persistedSession.get()));

        assertThatThrownBy(() -> sessionService.revokeUserSession("user-1", "session-1", login.token()))
                .isInstanceOf(net.ximatai.muyun.spring.ability.action.BusinessException.class)
                .hasMessage("不能在用户管理中下线当前登录会话");

        verify(sessionDao, never()).updateByIdAndVersion(any(UserSession.class), any());
    }

    @Test
    void shouldRevokeSelectedUserSessions() {
        UserAccountDao dao = mock(UserAccountDao.class);
        UserAccountService userService = new UserAccountService(dao, tenantId -> {
        }, passwordHashingService);
        UserSessionDao sessionDao = mock(UserSessionDao.class);
        UserSession web = activeSession("session-web", "user-1");
        UserSession mobile = activeSession("session-mobile", "user-1");
        when(sessionDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(web))
                .thenReturn(List.of(mobile));
        when(sessionDao.updateByIdAndVersion(any(UserSession.class), any())).thenReturn(1);
        UserSessionService sessionService = sessionService(userService, sessionDao, clock);

        assertThat(sessionService.revokeUserSessions("user-1", List.of("session-web", "session-mobile"), null))
                .isEqualTo(2);

        assertThat(web.getRevokedReason()).isEqualTo("user session revoked by administrator");
        assertThat(mobile.getRevokedReason()).isEqualTo("user session revoked by administrator");
    }

    @Test
    void shouldTreatMalformedPasswordHashAsNotMatched() {
        assertThat(passwordHashingService.matches("secret1", "pbkdf2$bad$not-base64")).isFalse();
        assertThat(passwordHashingService.matches("secret1", "pbkdf2$1$a$b")).isFalse();
    }

    private UserSessionService sessionService(UserAccountService userAccountService,
                                              UserSessionDao userSessionDao,
                                              Clock clock) {
        return sessionService(userAccountService, userSessionDao, UserSecurityEventPublisher.NOOP,
                UserSessionLifecycleEventPublisher.NOOP, clock);
    }

    private UserSessionService sessionService(UserAccountService userAccountService,
                                              UserSessionDao userSessionDao,
                                              Clock clock,
                                              CurrentUserTimeZoneResolver timeZoneResolver) {
        return sessionService(userAccountService, new UserSessionRecordService(userSessionDao), userAccountService,
                null, () -> UserSecurityEventPublisher.NOOP, () -> UserSessionLifecycleEventPublisher.NOOP,
                clock, timeZoneResolver, () -> UserSessionPresenceLookup.NONE);
    }

    private UserSessionService sessionService(UserAccountService userAccountService,
                                              UserSessionDao userSessionDao,
                                              UserSecurityEventPublisher securityEventPublisher,
                                              Clock clock) {
        return sessionService(userAccountService, userSessionDao, securityEventPublisher,
                UserSessionLifecycleEventPublisher.NOOP, clock);
    }

    private UserSessionService sessionService(UserAccountService userAccountService,
                                              UserSessionDao userSessionDao,
                                              UserSecurityEventPublisher securityEventPublisher,
                                              UserSessionLifecycleEventPublisher lifecycleEventPublisher,
                                              Clock clock) {
        return sessionService(userAccountService, new UserSessionRecordService(userSessionDao), userAccountService,
                null, () -> securityEventPublisher, () -> lifecycleEventPublisher, clock, null,
                () -> UserSessionPresenceLookup.NONE);
    }

    private UserSessionService sessionService(
            UserAccountService userAccountService,
            UserSessionRecordService userSessionRecordService,
            net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier activeTenantVerifier,
            UserSessionRevocationService revocationService,
            Supplier<UserSecurityEventPublisher> securityEventPublisher,
            Supplier<UserSessionLifecycleEventPublisher> lifecycleEventPublisher,
            Clock clock,
            CurrentUserTimeZoneResolver timeZoneResolver,
            Supplier<UserSessionPresenceLookup> presenceLookup) {
        UserSessionCollaborators collaborators = new UserSessionCollaborators(
                () -> revocationService,
                securityEventPublisher,
                () -> event -> {
                    UserSessionLifecycleEventPublisher publisher = lifecycleEventPublisher == null
                            ? UserSessionLifecycleEventPublisher.NOOP
                            : lifecycleEventPublisher.get();
                    publisher.publish((UserSessionLifecycleEvent) event);
                },
                timeZoneResolver,
                null,
                presenceLookup);
        return new UserSessionService(userAccountService, userSessionRecordService, activeTenantVerifier,
                collaborators, clock);
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId("user-1");
        user.setTenantId("tenant-a");
        user.setUsername("alice");
        user.setTitle("Alice");
        user.setEnabled(Boolean.TRUE);
        user.setPasswordHash(passwordHashingService.hash("secret1"));
        return user;
    }

    private AtomicReference<UserSession> captureInsertedSession(UserSessionDao sessionDao) {
        AtomicReference<UserSession> reference = new AtomicReference<>();
        when(sessionDao.insert(any())).thenAnswer(invocation -> {
            UserSession session = invocation.getArgument(0);
            reference.set(session);
            return "session-1";
        });
        return reference;
    }

    private static final class RecordingUserSecurityEventPublisher implements UserSecurityEventPublisher {
        private final List<UserSecurityEvent> events = new ArrayList<>();

        @Override
        public void publish(UserSecurityEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingUserSessionLifecycleEventPublisher
            implements UserSessionLifecycleEventPublisher {
        private final List<UserSessionLifecycleEvent> events = new ArrayList<>();

        @Override
        public void publish(UserSessionLifecycleEvent event) {
            events.add(event);
        }
    }

    private UserSession activeSession(String id, String userId) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setTenantId("tenant-a");
        session.setUserId(userId);
        session.setUsername("alice");
        session.setOrganizationId("org-1");
        session.setTokenHash("hash-" + id);
        session.setIssuedAt(clock.instant());
        session.setExpiresAt(clock.instant().plusSeconds(3600));
        session.setMaxExpiresAt(clock.instant().plusSeconds(604_800));
        session.setLastSeenAt(clock.instant());
        return session;
    }
}
