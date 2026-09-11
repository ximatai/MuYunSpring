package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessLogLoginAuditLoggerTest {

    @Test
    void shouldFailOpenWhenNeutralPublisherCannotStoreLoginFact() {
        BusinessLogPublisher publisher = mock(BusinessLogPublisher.class);
        doThrow(new IllegalStateException("database unavailable")).when(publisher).publish(any(LoginLogEvent.class));
        BusinessLogLoginAuditLogger logger = new BusinessLogLoginAuditLogger(publisher);
        LoginLogEvent event = new LoginLogEvent(
                new BusinessLogContext("event-1", Instant.now(), Instant.now(), null,
                        "tenant-a", null, "iam.user", "login"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.FAILURE,
                        "BAD_CREDENTIALS", "127.0.0.1", "alice", null));

        assertThatCode(() -> logger.record(event)).doesNotThrowAnyException();

        verify(publisher).publish(event);
    }
}
