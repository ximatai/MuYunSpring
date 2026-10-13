package net.ximatai.muyun.spring.web.realtime;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformStompErrorHandlerTest {
    @Test
    void preservesMachineCodeThroughMessagingWrappersIndependentlyOfText() {
        var handler = new PlatformStompErrorHandler();
        for (String message : new String[]{"登录已失效", "arbitrary diagnostic"}) {
            var frame = handler.handleClientMessageProcessingError(null,
                    new MessageDeliveryException(org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0]).build(),
                            message, new PlatformException("AUTH_REQUIRED", 401, message)));
            var headers = StompHeaderAccessor.wrap(frame);
            assertThat(headers.getFirstNativeHeader("code")).isEqualTo("AUTH_REQUIRED");
            assertThat(headers.getMessage()).isEqualTo(message);
        }
        var frame = handler.handleClientMessageProcessingError(null,
                new IllegalArgumentException("realtime authentication required"));
        assertThat(StompHeaderAccessor.wrap(frame).getFirstNativeHeader("code")).isNull();
    }
}
