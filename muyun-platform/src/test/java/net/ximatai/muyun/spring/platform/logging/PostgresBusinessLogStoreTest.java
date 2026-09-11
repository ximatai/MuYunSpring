package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStorageException;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresBusinessLogStoreTest {

    @Test
    void shouldPropagateStorageFailureToItsPublisher() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));
        PostgresBusinessLogStore store = new PostgresBusinessLogStore(dataSource);
        LoginLogEvent event = new LoginLogEvent(
                new BusinessLogContext("event-1", Instant.now(), Instant.now(), null,
                        null, null, "iam.login", "login"),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.FAILURE,
                        "BAD_CREDENTIALS", "127.0.0.1", "rui", null));

        assertThatThrownBy(() -> store.append(event))
                .isInstanceOf(BusinessLogStorageException.class)
                .hasMessageContaining("append business log event")
                .hasCauseInstanceOf(SQLException.class);
    }
}
