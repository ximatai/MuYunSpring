package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.spring.common.identity.CurrentUser;

/** Resolves a recipient selector against one authenticated, currently connected user. */
public interface BusinessNotificationRecipientResolver {
    boolean matches(CurrentUser currentUser, BusinessNotificationRecipients recipients);
}
