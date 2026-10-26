package net.ximatai.muyun.spring.iam.user;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSessionRevocationServiceSpringTest {
    @Test
    void springAssemblyUsesApplicationClockForBatchRevocation() {
        Instant now = Instant.parse("2031-04-05T06:07:08.123456Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UserSessionRecordService records = mock(UserSessionRecordService.class);
        when(records.revokeActiveByUserId("user-1", now, "security change")).thenReturn(List.of("session-1"));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, () -> clock);
            context.registerBean(UserSessionRecordService.class, () -> records);
            context.registerBean(UserSessionRevocationService.class);
            context.refresh();

            int revoked = context.getBean(UserSessionRevocationService.class)
                    .revokeUserSessions("user-1", "security change");

            assertThat(revoked).isOne();
            org.mockito.Mockito.verify(records).revokeActiveByUserId("user-1", now, "security change");
        }
    }
}
