package net.ximatai.muyun.spring.platform.web.notification;

import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.web.realtime.RealtimeDestinations;
import net.ximatai.muyun.spring.web.realtime.RealtimeEnvelope;
import net.ximatai.muyun.spring.web.realtime.RealtimeMessagePublisher;

public class StompBusinessNotificationNotifier implements BusinessNotificationNotifier {
    public static final String MESSAGE_TYPE = "platform.business-notification";

    private final RealtimeMessagePublisher messagePublisher;

    public StompBusinessNotificationNotifier(RealtimeMessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    @Override
    public void notifyUser(String userId, BusinessNotification notification) {
        if (userId == null || userId.isBlank() || notification == null) return;
        messagePublisher.sendToUser(userId, RealtimeDestinations.USER_BUSINESS_NOTIFICATIONS,
                RealtimeEnvelope.of(MESSAGE_TYPE, RequestTraceContext.currentTraceId().orElse(null),
                        WebBusinessNotification.from(notification)));
    }
}
