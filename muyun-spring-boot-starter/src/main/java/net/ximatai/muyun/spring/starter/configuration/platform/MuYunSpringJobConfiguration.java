package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MuYunSpringJobProperties.class)
public class MuYunSpringJobConfiguration {
    @Bean
    @ConditionalOnMissingBean(JobFailureRecipientResolver.class)
    JobFailureRecipientResolver jobFailureRecipientResolver() {
        return tenant -> net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients.none();
    }

    @Bean
    @ConditionalOnMissingBean(JobManagementAccess.class)
    JobManagementAccess jobManagementAccess() {
        return tenant -> { throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                "Job management requires an administrator resolver"); };
    }

    @Bean
    @ConditionalOnMissingBean
    JobRegistry jobRegistry(List<JobHandler> handlers) {
        return new JobRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    JobService jobService(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> database,
                          PlatformTransactionManager transactions, JobRegistry registry,
                          ObjectProvider<JobFailureRecipientResolver> recipients,
                          ObjectProvider<BusinessNotificationDelivery> notifications,
                          ObjectProvider<JobManagementAccess> access,
                          MuYunSpringJobProperties properties) {
        properties.validate();
        return new JobService(plans, runs, database, transactions, registry, recipients, notifications, access,
                properties.getLeaseDuration(), properties.getRetentionDays());
    }

    @Bean
    @ConditionalOnMissingBean
    JobNodeExecutor jobNodeExecutor(JobService jobs, JobRegistry registry, MuYunSpringJobProperties properties) {
        return new JobNodeExecutor(jobs, registry, properties);
    }
}
