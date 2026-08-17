package net.ximatai.muyun.spring.platform.notification;

/** Stable business-facing facade. Business code must not depend on STOMP destinations. */
public interface BusinessNotificationService {
    void publish(BusinessNotification notification);
}
