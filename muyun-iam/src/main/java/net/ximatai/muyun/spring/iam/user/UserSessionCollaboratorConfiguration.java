package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.identity.CurrentUserTimeZoneResolver;
import net.ximatai.muyun.spring.common.identity.CurrentUserOrganizationResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UserSessionCollaboratorConfiguration {
    @Bean
    UserSessionCollaborators userSessionCollaborators(
            ObjectProvider<UserSessionRevocationService> revocationService,
            ObjectProvider<UserSecurityEventPublisher> securityEventPublisher,
            ObjectProvider<CurrentUserTimeZoneResolver> timeZoneResolver,
            ObjectProvider<CurrentUserOrganizationResolver> organizationResolver,
            ObjectProvider<UserSessionPresenceLookup> presenceLookup,
            ApplicationEventPublisher applicationEventPublisher) {
        return new UserSessionCollaborators(
                revocationService::getIfAvailable,
                () -> securityEventPublisher.getIfAvailable(() -> UserSecurityEventPublisher.NOOP),
                () -> event -> applicationEventPublisher.publishEvent(event),
                timeZoneResolver.getIfAvailable(() -> CurrentUserTimeZoneResolver.NONE),
                organizationResolver.getIfAvailable(() -> CurrentUserOrganizationResolver.NONE),
                () -> presenceLookup.getIfAvailable(() -> UserSessionPresenceLookup.NONE));
    }
}
