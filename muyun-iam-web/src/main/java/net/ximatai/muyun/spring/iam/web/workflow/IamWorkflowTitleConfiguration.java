package net.ximatai.muyun.spring.iam.web.workflow;

import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowUserTitleResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = {"net.ximatai.muyun.spring.platform.web.ActionEndpointWebConfiguration",
        "net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration"})
public class IamWorkflowTitleConfiguration {
    @Bean
    @ConditionalOnBean(UserAccountService.class)
    @ConditionalOnMissingBean(WorkflowUserTitleResolver.class)
    WorkflowUserTitleResolver workflowUserTitleResolver(UserAccountService userAccountService) {
        return new IamWorkflowUserTitleResolver(userAccountService);
    }
}
