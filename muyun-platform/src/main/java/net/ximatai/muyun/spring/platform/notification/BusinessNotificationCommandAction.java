package net.ximatai.muyun.spring.platform.notification;

import java.util.Map;

/** Invokes a server-side handler registered by the owning business module. */
public record BusinessNotificationCommandAction(
        String key,
        String label,
        String command,
        Map<String, Object> arguments,
        boolean danger,
        String confirmation,
        boolean dismissOnSuccess
) implements BusinessNotificationAction {
    public BusinessNotificationCommandAction {
        key = BusinessNotificationAction.required(key, "business notification action key");
        label = BusinessNotificationAction.required(label, "business notification action label");
        command = BusinessNotificationAction.required(command, "business notification command");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        confirmation = confirmation == null || confirmation.isBlank() ? null : confirmation.trim();
    }
}
