package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationAction;

import java.util.List;

/** Browser-safe projection. Recipient scope remains a server-only concern. */
public record WebBusinessNotification(
        String id,
        String code,
        String title,
        String subtitle,
        String content,
        boolean dismissible,
        List<BusinessNotificationAction> actions
) {
    public static WebBusinessNotification from(BusinessNotification notification) {
        return new WebBusinessNotification(notification.id(), notification.code(), notification.title(),
                notification.subtitle(), notification.content(), notification.dismissible(), notification.actions());
    }
}
