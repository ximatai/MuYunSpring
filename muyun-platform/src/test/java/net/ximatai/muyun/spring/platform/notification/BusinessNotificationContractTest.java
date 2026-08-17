package net.ximatai.muyun.spring.platform.notification;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessNotificationContractTest {
    @Test
    void shouldNormalizeRecipientDimensionsAndKeepActionsDeclarative() {
        BusinessNotification notification = new BusinessNotification(
                "notice-1", "workflow.approval-arrived", "有新的审批", "采购申请", "请及时处理", false,
                new BusinessNotificationRecipients(false, List.of("tenant-a", " tenant-a "),
                        List.of(), List.of("department-a"), List.of("position-a"), List.of("user-a", "user-a")),
                List.of(
                        new BusinessNotificationNavigateAction("view", "查看", "workflow.task", "task-1", "DETAIL",
                                Map.of(), false),
                        new BusinessNotificationCommandAction("approve", "同意", "workflow.approval.approve",
                                Map.of("taskId", "task-1"), false, null, true)));

        assertThat(notification.dismissible()).isFalse();
        assertThat(notification.recipients().tenantIds()).containsExactly("tenant-a");
        assertThat(notification.recipients().userIds()).containsExactly("user-a");
        assertThat(notification.actions()).hasSize(2);
        assertThat(notification.actions().get(1)).isInstanceOf(BusinessNotificationCommandAction.class);
    }

    @Test
    void shouldDeferDeliveryUntilTransactionCommit() {
        RecordingDelivery delivery = new RecordingDelivery();
        TransactionalBusinessNotificationService publisher = new TransactionalBusinessNotificationService(delivery);
        BusinessNotification notification = new BusinessNotification("notice-1", "demo.notice", "标题", null, "正文", true,
                BusinessNotificationRecipients.none(), List.of());

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.publish(notification);
            assertThat(delivery.notifications).isEmpty();
            TransactionSynchronizationUtils.triggerAfterCommit();
            assertThat(delivery.notifications).containsExactly(notification);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldDeliverImmediatelyWhenSynchronizationExistsWithoutActualTransaction() {
        RecordingDelivery delivery = new RecordingDelivery();
        TransactionalBusinessNotificationService publisher = new TransactionalBusinessNotificationService(delivery);
        BusinessNotification notification = new BusinessNotification("notice-1", "demo.notice", "标题", null, "正文", true,
                BusinessNotificationRecipients.none(), List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publish(notification);
            assertThat(delivery.notifications).containsExactly(notification);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final class RecordingDelivery implements BusinessNotificationDelivery {
        private final List<BusinessNotification> notifications = new java.util.ArrayList<>();

        @Override
        public void deliver(BusinessNotification notification) {
            notifications.add(notification);
        }
    }
}
