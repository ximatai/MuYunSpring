package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.spring.common.util.Preconditions;

import java.time.Instant;
import java.util.List;

/**
 * An ephemeral business reminder delivered to currently connected users.
 * It intentionally has no delivery persistence or acknowledgement semantics.
 */
public record BusinessNotification(
        String id,
        String code,
        String title,
        String subtitle,
        String content,
        boolean dismissible,
        BusinessNotificationRecipients recipients,
        List<BusinessNotificationAction> actions,
        BusinessNotificationTone tone,
        Instant occurredAt
) {
    /**
     * Keeps existing business callers source- and binary-compatible while presentation metadata is optional.
     */
    public BusinessNotification(String id, String code, String title, String subtitle, String content,
                                boolean dismissible, BusinessNotificationRecipients recipients,
                                List<BusinessNotificationAction> actions) {
        this(id, code, title, subtitle, content, dismissible, recipients, actions, null, null);
    }

    public BusinessNotification {
        id = Preconditions.requireText(id, "business notification id");
        code = Preconditions.requireText(code, "business notification code");
        title = Preconditions.requireText(title, "business notification title");
        subtitle = normalize(subtitle);
        content = Preconditions.requireText(content, "business notification content");
        recipients = recipients == null ? BusinessNotificationRecipients.none() : recipients;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
