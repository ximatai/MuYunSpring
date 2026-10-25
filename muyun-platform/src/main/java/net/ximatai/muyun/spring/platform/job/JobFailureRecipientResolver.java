package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;

/** Resolves administrators of a job's owning scope; null tenant denotes the platform. */
public interface JobFailureRecipientResolver {
    BusinessNotificationRecipients resolve(String tenantId);
}
