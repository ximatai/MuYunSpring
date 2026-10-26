package net.ximatai.muyun.spring.iam.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

@Service
public class UserSessionRevocationService {
    private static final int MAX_REVOKE_ATTEMPTS = 64;
    private final UserSessionRecordService userSessionRecordService;
    private final Supplier<UserSessionLifecycleEventPublisher> userSessionLifecycleEventPublisher;
    private final Clock clock;

    @Autowired
    public UserSessionRevocationService(
            UserSessionRecordService userSessionRecordService,
            ApplicationEventPublisher applicationEventPublisher,
            Clock clock) {
        this(userSessionRecordService,
                () -> event -> applicationEventPublisher.publishEvent(event),
                clock);
    }

    UserSessionRevocationService(UserSessionRecordService userSessionRecordService,
                                 UserSessionLifecycleEventPublisher userSessionLifecycleEventPublisher,
                                 Clock clock) {
        this(userSessionRecordService,
                () -> userSessionLifecycleEventPublisher == null
                        ? UserSessionLifecycleEventPublisher.NOOP
                        : userSessionLifecycleEventPublisher,
                clock);
    }

    UserSessionRevocationService(UserSessionRecordService userSessionRecordService,
                                 Supplier<UserSessionLifecycleEventPublisher> userSessionLifecycleEventPublisher,
                                 Clock clock) {
        this.userSessionRecordService = userSessionRecordService;
        this.userSessionLifecycleEventPublisher = userSessionLifecycleEventPublisher == null
                ? () -> UserSessionLifecycleEventPublisher.NOOP
                : userSessionLifecycleEventPublisher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public int revokeUserSessions(String userId, String reason) {
        String validUserId = normalizeBlank(userId);
        if (validUserId == null) {
            return 0;
        }
        List<String> sessionIds = userSessionRecordService.revokeActiveByUserId(validUserId, now(), reason);
        for (String sessionId : sessionIds) {
            userSessionLifecycleEventPublisher.get().publish(UserSessionLifecycleEvent.revoked(validUserId, sessionId));
        }
        return sessionIds.size();
    }

    public boolean revoke(UserSession session, Instant now, String reason) {
        return revoke(session, now, reason, UserSessionLifecycleEvent.Type.REVOKED);
    }

    public boolean logout(UserSession session, Instant now) {
        return revoke(session, now, "logout", UserSessionLifecycleEvent.Type.LOGGED_OUT);
    }

    private boolean revoke(UserSession session, Instant now, String reason, UserSessionLifecycleEvent.Type eventType) {
        UserSession current = session;
        for (int attempt = 0; attempt < MAX_REVOKE_ATTEMPTS && current != null && current.getRevokedAt() == null; attempt++) {
            Integer expectedVersion = current.getVersion();
            current.setRevokedAt(now);
            current.setRevokedReason(reason);
            int updated = userSessionRecordService.updateSession(current, expectedVersion, now);
            if (updated > 0) {
                publishSessionLifecycleEvent(current, eventType);
                return true;
            }
            current = sessionById(current.getId());
        }
        if (current != null && current.getRevokedAt() == null) {
            // Do not let an enclosing disable/password-reset transaction commit with a live session.
            throw new net.ximatai.muyun.spring.ability.OptimisticLockException("会话状态发生并发变化，请重试");
        }
        return false;
    }

    private void publishSessionLifecycleEvent(UserSession session, UserSessionLifecycleEvent.Type eventType) {
        String userId = normalizeBlank(session.getUserId());
        String sessionId = normalizeBlank(session.getId());
        if (userId == null || sessionId == null) {
            return;
        }
        userSessionLifecycleEventPublisher.get().publish(lifecycleEvent(eventType, userId, sessionId));
    }

    private UserSessionLifecycleEvent lifecycleEvent(
            UserSessionLifecycleEvent.Type eventType,
            String userId,
            String sessionId) {
        if (UserSessionLifecycleEvent.Type.LOGGED_OUT.equals(eventType)) {
            return UserSessionLifecycleEvent.loggedOut(userId, sessionId);
        }
        return UserSessionLifecycleEvent.revoked(userId, sessionId);
    }

    private UserSession sessionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return userSessionRecordService.findById(id);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
