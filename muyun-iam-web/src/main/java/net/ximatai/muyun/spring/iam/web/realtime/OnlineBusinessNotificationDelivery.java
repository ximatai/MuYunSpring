package net.ximatai.muyun.spring.iam.web.realtime;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.web.realtime.*;
import net.ximatai.muyun.spring.platform.notification.BusinessNotification;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipientResolver;
import net.ximatai.muyun.spring.platform.web.notification.BusinessNotificationNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Delivers ephemeral notifications only to users with a live WebSocket connection. */
public class OnlineBusinessNotificationDelivery implements BusinessNotificationDelivery {
    private static final Logger LOGGER = LoggerFactory.getLogger(OnlineBusinessNotificationDelivery.class);

    private final RealtimeConnectionRegistry connectionRegistry;
    private final UserSessionService userSessionService;
    private final BusinessNotificationRecipientResolver recipientResolver;
    private final BusinessNotificationNotifier notifier;

    public OnlineBusinessNotificationDelivery(RealtimeConnectionRegistry connectionRegistry,
                                              UserSessionService userSessionService,
                                              BusinessNotificationRecipientResolver recipientResolver,
                                              BusinessNotificationNotifier notifier) {
        this.connectionRegistry = connectionRegistry;
        this.userSessionService = userSessionService;
        this.recipientResolver = recipientResolver;
        this.notifier = notifier;
    }

    @Override
    public void deliver(BusinessNotification notification) {
        if (notification == null) return;
        Set<String> delivered = new HashSet<>();
        for (CurrentUserPrincipal principal : connectionRegistry.principals()) {
            currentUser(principal).filter(user -> delivered.add(user.userId()))
                    .ifPresent(user -> deliverIfMatched(user, notification));
        }
    }

    private void deliverIfMatched(CurrentUser user, BusinessNotification notification) {
        if (user.passwordChangeRequired()) return;
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(user);
             TenantContext.Scope ignoredTenant = tenantScope(user)) {
            if (recipientResolver.matches(user, notification.recipients())) {
                notifier.notifyUser(user.userId(), notification);
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to deliver business notification: userId={}, notificationCode={}",
                    user.userId(), notification.code(), exception);
        }
    }

    private Optional<CurrentUser> currentUser(CurrentUserPrincipal principal) {
        if (principal == null) return Optional.empty();
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("business notification recipient lookup")) {
            return userSessionService.currentUserSnapshot(principal.token());
        }
    }

    private TenantContext.Scope tenantScope(CurrentUser user) {
        if (user.system() || user.tenantId() == null || user.tenantId().isBlank()) {
            return TenantContext.system("business notification delivery");
        }
        return TenantContext.use(user.tenantId());
    }
}
