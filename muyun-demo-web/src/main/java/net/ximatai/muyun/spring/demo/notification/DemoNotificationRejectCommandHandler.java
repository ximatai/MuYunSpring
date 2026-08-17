package net.ximatai.muyun.spring.demo.notification;

import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandHandler;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandInvocation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("school-demo")
public class DemoNotificationRejectCommandHandler implements BusinessNotificationCommandHandler {
    @Override
    public String command() {
        return "education.demo.reject";
    }

    @Override
    public Object handle(BusinessNotificationCommandInvocation invocation) {
        CurrentUserContext.currentUser().orElseThrow(() -> new AuthenticationFailedException("authentication is required"));
        return Map.of("status", "rejected", "notificationId", invocation.notificationId());
    }
}
