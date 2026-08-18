package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.Map;

/**
 * Invokes a record action owned by the business module.
 *
 * <p>The client uses the standard record action target {@code /{moduleAlias}/{actionCode}/{recordId}}.
 * This keeps notification delivery declarative without routing business state transitions through a
 * platform command dispatcher or introducing a second record-action URL contract.</p>
 */
public record BusinessNotificationRecordAction(
        String key,
        String label,
        String moduleAlias,
        String recordId,
        String actionCode,
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
        actionCode = normalizeActionCode(actionCode);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        confirmation = confirmation == null || confirmation.isBlank() ? null : confirmation.trim();
        placement = BusinessNotificationAction.placement(placement);
    }

    public BusinessNotificationRecordAction(String key, String label, String moduleAlias, String recordId,
                                            String actionCode, Map<String, Object> arguments, boolean danger,
                                            String confirmation, boolean dismissOnSuccess) {
        this(key, label, moduleAlias, recordId, actionCode, arguments, danger, confirmation, "leading", dismissOnSuccess);
    }

    private static String normalizeActionCode(String actionCode) {
        return PlatformNameRules.requireActionCode(actionCode, "business notification action code");
    }
}
