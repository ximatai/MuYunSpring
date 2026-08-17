package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.platform.notification.BusinessNotification;

public interface BusinessNotificationNotifier {
    void notifyUser(String userId, BusinessNotification notification);
}
