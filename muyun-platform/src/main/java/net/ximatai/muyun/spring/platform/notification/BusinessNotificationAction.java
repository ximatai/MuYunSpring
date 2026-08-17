package net.ximatai.muyun.spring.platform.notification;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.ximatai.muyun.spring.common.util.Preconditions;

/** A declarative action; clients never execute code embedded in notification payloads. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BusinessNotificationNavigateAction.class, name = "navigate"),
        @JsonSubTypes.Type(value = BusinessNotificationCommandAction.class, name = "command")
})
public sealed interface BusinessNotificationAction permits BusinessNotificationNavigateAction, BusinessNotificationCommandAction {
    String key();
    String label();
    boolean dismissOnSuccess();

    static String required(String value, String name) {
        return Preconditions.requireText(value, name);
    }
}
