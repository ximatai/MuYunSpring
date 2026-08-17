package net.ximatai.muyun.spring.demo.notification;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandHandler;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandInvocation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Demo-only action handler. Production handlers must execute their domain service and audit real state changes. */
@Component
@Profile("school-demo")
public class DemoNotificationCommandHandler implements BusinessNotificationCommandHandler {
    @Override
    public String command() {
        return "education.demo.approve";
    }

    @Override
    public Object handle(BusinessNotificationCommandInvocation invocation) {
        CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        return Map.of("status", "approved", "notificationId", invocation.notificationId());
    }
}
