package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import java.util.Objects;

/** Publishes only after a surrounding transaction has committed. */
public class TransactionalBusinessNotificationService implements BusinessNotificationService {
    private final BusinessNotificationDelivery delivery;

    public TransactionalBusinessNotificationService(BusinessNotificationDelivery delivery) {
        this.delivery = Objects.requireNonNull(delivery, "business notification delivery must not be null");
    }

    @Override
    public void publish(BusinessNotification notification) {
        if (notification == null) return;
        TransactionScopeSupport.afterCommitOrNow(() -> delivery.deliver(notification));
    }
}
