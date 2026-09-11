package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.identity.CurrentUserTimeZoneResolver;
import net.ximatai.muyun.spring.common.identity.CurrentUserOrganizationResolver;

import java.util.function.Supplier;

public record UserSessionCollaborators(
        Supplier<UserSessionRevocationService> revocationService,
        Supplier<UserSecurityEventPublisher> securityEventPublisher,
        Supplier<UserSessionLifecycleEventPublisher> lifecycleEventPublisher,
        CurrentUserTimeZoneResolver timeZoneResolver,
        CurrentUserOrganizationResolver organizationResolver,
        Supplier<UserSessionPresenceLookup> presenceLookup,
        Supplier<LoginAuditLogger> loginAuditLogger
) {
    public UserSessionCollaborators {
        revocationService = revocationService == null ? () -> null : revocationService;
        securityEventPublisher = securityEventPublisher == null
                ? () -> UserSecurityEventPublisher.NOOP
                : securityEventPublisher;
        lifecycleEventPublisher = lifecycleEventPublisher == null
                ? () -> UserSessionLifecycleEventPublisher.NOOP
                : lifecycleEventPublisher;
        timeZoneResolver = timeZoneResolver == null ? CurrentUserTimeZoneResolver.NONE : timeZoneResolver;
        organizationResolver = organizationResolver == null ? CurrentUserOrganizationResolver.NONE : organizationResolver;
        presenceLookup = presenceLookup == null ? () -> UserSessionPresenceLookup.NONE : presenceLookup;
        loginAuditLogger = loginAuditLogger == null ? () -> LoginAuditLogger.NOOP : loginAuditLogger;
    }

    public UserSessionCollaborators(Supplier<UserSessionRevocationService> revocationService,
                                   Supplier<UserSecurityEventPublisher> securityEventPublisher,
                                   Supplier<UserSessionLifecycleEventPublisher> lifecycleEventPublisher,
                                   CurrentUserTimeZoneResolver timeZoneResolver,
                                   CurrentUserOrganizationResolver organizationResolver,
                                   Supplier<UserSessionPresenceLookup> presenceLookup) {
        this(revocationService, securityEventPublisher, lifecycleEventPublisher, timeZoneResolver,
                organizationResolver, presenceLookup, null);
    }

    public static UserSessionCollaborators empty() {
        return new UserSessionCollaborators(null, null, null, null, null, null, null);
    }
}
