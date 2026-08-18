package net.ximatai.muyun.spring.platform.notification;

import java.util.Map;

/**
 * Invokes a record-scoped endpoint owned by the business module.
 *
 * <p>The client derives the target from {@code /{moduleAlias}/{recordId}/{endpoint}}.  This
 * keeps notification delivery declarative without routing business state transitions through a
 * platform command dispatcher or accepting an arbitrary URL from a notification payload.</p>
 */
public record BusinessNotificationRecordAction(
        String key,
        String label,
        String moduleAlias,
        String recordId,
        String endpoint,
        Map<String, Object> arguments,
        boolean danger,
        String confirmation,
        String placement,
        boolean dismissOnSuccess
) implements BusinessNotificationAction {
    public BusinessNotificationRecordAction {
        key = BusinessNotificationAction.required(key, "business notification action key");
        label = BusinessNotificationAction.required(label, "business notification action label");
        moduleAlias = BusinessNotificationAction.required(moduleAlias, "business notification module alias");
        recordId = BusinessNotificationAction.required(recordId, "business notification record id");
        endpoint = normalizeEndpoint(endpoint);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        confirmation = confirmation == null || confirmation.isBlank() ? null : confirmation.trim();
        placement = BusinessNotificationAction.placement(placement);
    }

    public BusinessNotificationRecordAction(String key, String label, String moduleAlias, String recordId,
                                            String endpoint, Map<String, Object> arguments, boolean danger,
                                            String confirmation, boolean dismissOnSuccess) {
        this(key, label, moduleAlias, recordId, endpoint, arguments, danger, confirmation, "leading", dismissOnSuccess);
    }

    private static String normalizeEndpoint(String endpoint) {
        String value = BusinessNotificationAction.required(endpoint, "business notification endpoint");
        if (value.startsWith("/") || value.endsWith("/") || value.contains("..")
                || !value.matches("[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*")) {
            throw new IllegalArgumentException("business notification endpoint is invalid");
        }
        return value;
    }
}
