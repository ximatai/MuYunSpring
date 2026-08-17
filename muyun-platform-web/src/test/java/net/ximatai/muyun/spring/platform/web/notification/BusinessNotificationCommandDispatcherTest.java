package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessNotificationCommandDispatcherTest {
    @Test
    void shouldRequireAnAuthenticatedOperator() {
        BusinessNotificationCommandDispatcher dispatcher = new BusinessNotificationCommandDispatcher(List.of());

        assertThatThrownBy(() -> dispatcher.dispatch("workflow.approve",
                new BusinessNotificationCommandRequest("notice-1", "approve", Map.of())))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
