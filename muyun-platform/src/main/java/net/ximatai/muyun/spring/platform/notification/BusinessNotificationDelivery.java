package net.ximatai.muyun.spring.platform.notification;

/** Internal delivery boundary so transport and recipient lookup remain replaceable. */
public interface BusinessNotificationDelivery {
    void deliver(BusinessNotification notification);
}
