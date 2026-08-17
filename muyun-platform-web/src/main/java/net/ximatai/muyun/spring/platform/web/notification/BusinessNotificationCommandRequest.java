package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.platform.notification.BusinessNotificationCommandInvocation;
import java.util.Map;

/** Browser-supplied command data. All fields are untrusted and must be validated by the business handler. */
public record BusinessNotificationCommandRequest(String notificationId, String actionKey, Map<String, Object> arguments) {
    BusinessNotificationCommandInvocation invocation() {
        return new BusinessNotificationCommandInvocation(notificationId, actionKey, arguments);
    }
}
