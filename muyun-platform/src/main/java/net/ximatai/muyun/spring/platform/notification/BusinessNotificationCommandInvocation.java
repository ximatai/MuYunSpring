package net.ximatai.muyun.spring.platform.notification;

import java.util.Map;

public record BusinessNotificationCommandInvocation(
        String notificationId,
        String actionKey,
        Map<String, Object> arguments
) {
    public BusinessNotificationCommandInvocation {
        notificationId = BusinessNotificationAction.required(notificationId, "business notification id");
        actionKey = BusinessNotificationAction.required(actionKey, "business notification action key");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
