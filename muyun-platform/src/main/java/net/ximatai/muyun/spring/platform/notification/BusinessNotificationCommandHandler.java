package net.ximatai.muyun.spring.platform.notification;

/**
 * Business modules register one handler per stable notification command.
 * This is an authenticated business command, not a proof that the caller received a notification.
 * Handlers must treat the invocation as untrusted and re-check permission, data scope and current business state.
 */
public interface BusinessNotificationCommandHandler {
    String command();

    Object handle(BusinessNotificationCommandInvocation invocation);
}
