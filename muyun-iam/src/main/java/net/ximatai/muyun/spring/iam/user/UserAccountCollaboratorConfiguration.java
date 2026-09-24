package net.ximatai.muyun.spring.iam.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UserAccountCollaboratorConfiguration {
    @Bean
    UserAccountSecurityServices userAccountSecurityServices(
            ObjectProvider<PasswordPolicyRuleService> passwordPolicyRuleService,
            ObjectProvider<UserSecurityEventPublisher> securityEventPublisher,
            UserSessionRevocationService sessionRevocationService,
            UserSessionPresenceService sessionPresenceService) {
        return new UserAccountSecurityServices(
                java.util.Optional.ofNullable(passwordPolicyRuleService.getIfAvailable()),
                event -> securityEventPublisher
                        .getIfAvailable(() -> UserSecurityEventPublisher.NOOP)
                        .publish(event),
                sessionRevocationService,
                sessionPresenceService);
    }
}
