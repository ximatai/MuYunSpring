package net.ximatai.muyun.spring.platform.notification;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.ximatai.muyun.spring.common.util.Preconditions;

/** A declarative action; clients never execute code embedded in notification payloads. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BusinessNotificationNavigateAction.class, name = "navigate"),
        @JsonSubTypes.Type(value = BusinessNotificationRecordAction.class, name = "record")
})
public sealed interface BusinessNotificationAction permits BusinessNotificationNavigateAction,
        BusinessNotificationRecordAction {
    String key();
    String label();
    boolean dismissOnSuccess();

    static String required(String value, String name) {
        return Preconditions.requireText(value, name);
    }

    static String placement(String value) {
        if (value == null || value.isBlank()) return "leading";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("leading") && !normalized.equals("trailing")) {
            throw new IllegalArgumentException("business notification action placement is invalid");
        }
        return normalized;
    }
}
