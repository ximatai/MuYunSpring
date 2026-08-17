package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.spring.common.identity.CurrentUser;

/** Covers identity dimensions available in {@link CurrentUser}; IAM extends this for department and position. */
public class DefaultBusinessNotificationRecipientResolver implements BusinessNotificationRecipientResolver {
    @Override
    public boolean matches(CurrentUser user, BusinessNotificationRecipients recipients) {
        if (user == null || recipients == null) return false;
        return recipients.systemWide()
                || recipients.userIds().contains(user.userId())
                || recipients.tenantIds().contains(user.tenantId())
                || recipients.organizationIds().contains(user.organizationId());
    }
}
