package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.i18n.TextPresentation;
import java.util.Map;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant occurredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String displayPolicy,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant expiresAt,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, TextPresentation> textResources
) {
    public WebBusinessNotification(String id, String code, String title, String subtitle, String content,
            boolean dismissible, List<BusinessNotificationAction> actions, String tone, Instant occurredAt,
            Map<String, TextPresentation> textResources) {
        this(id, code, title, subtitle, content, dismissible, actions, tone, occurredAt, null, null, textResources);
    }

    public WebBusinessNotification(String id, String code, String title, String subtitle, String content,
            boolean dismissible, List<BusinessNotificationAction> actions, String tone, Instant occurredAt) {
        this(id, code, title, subtitle, content, dismissible, actions, tone, occurredAt, Map.of());
    }

    public WebBusinessNotification withTextResources(Map<String, TextPresentation> resources) {
        return new WebBusinessNotification(id, code, title, subtitle, content, dismissible, actions, tone, occurredAt, displayPolicy, expiresAt, Map.copyOf(resources));
    }
    /** Keeps existing browser contract callers compatible with the optional presentation fields. */
    public WebBusinessNotification(String id, String code, String title, String subtitle, String content,
                                   boolean dismissible, List<BusinessNotificationAction> actions) {
        this(id, code, title, subtitle, content, dismissible, actions, null, null);
    }

    public static WebBusinessNotification from(BusinessNotification notification) {
        return new WebBusinessNotification(notification.id(), notification.code(), notification.title(),
                notification.subtitle(), notification.content(), notification.dismissible(), notification.actions(),
                notification.tone() == null ? null : notification.tone().value(), notification.occurredAt(),
                notification.displayPolicy() == null ? null : notification.displayPolicy().value(),
                notification.expiresAt(), Map.of());
    }
}
