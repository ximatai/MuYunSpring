package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UserAccountCollaboratorConfiguration {
    @Bean
    UserAccountAuthorizationServices userAccountAuthorizationServices(
            ObjectProvider<DataScopeCriteriaService> dataScopeCriteriaService,
            AccountRoleGrantDao accountRoleGrantDao) {
        return new UserAccountAuthorizationServices(
                () -> dataScopeCriteriaService.getIfAvailable(AllowAllDataScopeCriteriaService::new),
                accountRoleGrantDao);
    }

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
