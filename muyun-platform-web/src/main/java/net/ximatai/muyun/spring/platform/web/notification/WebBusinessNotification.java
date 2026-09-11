package net.ximatai.muyun.spring.platform.web.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationAction;

import java.time.Instant;
import java.util.List;

/** Browser-safe projection. Recipient scope remains a server-only concern. */
public record WebBusinessNotification(
        String id,
        String code,
        String title,
        String subtitle,
        String content,
        boolean dismissible,
        List<BusinessNotificationAction> actions,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tone,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant occurredAt
) {
    /** Keeps existing browser contract callers compatible with the optional presentation fields. */
    public WebBusinessNotification(String id, String code, String title, String subtitle, String content,
                                   boolean dismissible, List<BusinessNotificationAction> actions) {
        this(id, code, title, subtitle, content, dismissible, actions, null, null);
    }

    public static WebBusinessNotification from(BusinessNotification notification) {
        return new WebBusinessNotification(notification.id(), notification.code(), notification.title(),
                notification.subtitle(), notification.content(), notification.dismissible(), notification.actions(),
                notification.tone() == null ? null : notification.tone().value(), notification.occurredAt());
    }
}
