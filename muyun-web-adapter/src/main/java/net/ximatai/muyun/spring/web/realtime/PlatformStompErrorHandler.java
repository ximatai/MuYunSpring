package net.ximatai.muyun.spring.web.realtime;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/** Delivers machine-readable platform failures without interpreting diagnostic text. */
public class PlatformStompErrorHandler extends StompSubProtocolErrorHandler {
    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaders, byte[] payload,
                                              Throwable cause, StompHeaderAccessor clientHeaders) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof PlatformException error) {
                errorHeaders.setNativeHeader("code", error.code());
                errorHeaders.setMessage(error.getMessage());
                break;
            }
        }
        return super.handleInternal(errorHeaders, payload, cause, clientHeaders);
    }
}
