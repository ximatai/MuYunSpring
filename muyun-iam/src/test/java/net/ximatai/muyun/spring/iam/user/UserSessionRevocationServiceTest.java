package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSessionRevocationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void retriesHeartbeatConflictsRatherThanLeavingAnActiveSessionBehind() {
        var records = recordsWithConcurrentVersions();
        when(records.updateSession(any(), any(), eq(NOW))).thenReturn(0, 0, 1);
        var events = new ArrayList<UserSessionLifecycleEvent>();
        var service = new UserSessionRevocationService(records, (UserSessionLifecycleEventPublisher) events::add, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(service.revoke(session(0), NOW, "user disabled")).isTrue();
        assertThat(events).singleElement().satisfies(event -> assertThat(event.userId()).isEqualTo("user-1"));
    }

    @Test
    void persistentConflictsAbortTheEnclosingSecurityWriteInsteadOfSilentlySkippingRevocation() {
        var records = recordsWithConcurrentVersions();
        when(records.updateSession(any(), any(), eq(NOW))).thenReturn(0);
        var events = new ArrayList<UserSessionLifecycleEvent>();
        var service = new UserSessionRevocationService(records, (UserSessionLifecycleEventPublisher) events::add, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.revoke(session(0), NOW, "user disabled"))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(events).isEmpty();
    }

    private UserSessionRecordService recordsWithConcurrentVersions() {
        var records = mock(UserSessionRecordService.class);
        var version = new AtomicInteger();
        when(records.findById("session-1")).thenAnswer(call -> session(version.incrementAndGet()));
        return records;
    }

    private UserSession session(int version) {
        var session = new UserSession();
        session.setId("session-1"); session.setUserId("user-1"); session.setVersion(version);
        return session;
    }
}
