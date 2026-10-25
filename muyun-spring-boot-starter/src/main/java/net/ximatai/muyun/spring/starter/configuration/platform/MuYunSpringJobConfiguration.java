package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.TrustedExecutionIdentityResolver;
import net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.module.ModuleActionInvoker;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationDelivery;
import net.ximatai.muyun.spring.platform.notification.BusinessNotificationRecipients;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MuYunSpringJobProperties.class, MuYunSpringContentGenerationProperties.class})
public class MuYunSpringJobConfiguration {
    @Bean
    @ConditionalOnBean(ModuleActionInvoker.class)
    ModuleActionJob tenantModuleActionJob(
            ObjectProvider<ModuleActionInvoker> actions,
            ScheduledActionPlanDao plans,
            JobSystemActionPolicy whitelist,
            ObjectProvider<TrustedExecutionIdentityResolver> identities,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        return new ModuleActionJob(JobScope.TENANT, actions, plans, whitelist, identities, json);
    }

    @Bean
    @ConditionalOnBean(ModuleActionInvoker.class)
    ModuleActionJob platformModuleActionJob(
            ObjectProvider<ModuleActionInvoker> actions,
            ScheduledActionPlanDao plans,
            JobSystemActionPolicy whitelist,
            ObjectProvider<TrustedExecutionIdentityResolver> identities,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        return new ModuleActionJob(JobScope.PLATFORM, actions, plans, whitelist, identities, json);
    }

    @Bean
    @ConditionalOnMissingBean(JobFailureRecipientResolver.class)
    JobFailureRecipientResolver jobFailureRecipientResolver() {
        return tenant -> BusinessNotificationRecipients.none();
    }

    @Bean
    @ConditionalOnMissingBean(JobManagementAccess.class)
    JobManagementAccess jobManagementAccess() {
        return tenant -> {
            throw new PlatformAccessDeniedException(
                    "Job management requires an administrator resolver");
        };
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
    JobNodeExecutor jobNodeExecutor(
            JobService jobs,
            JobRegistry registry,
            MuYunSpringJobProperties properties,
            MuYunSpringContentGenerationProperties content,
            Environment environment) {
        content.configure(properties, environment);
        if (registry.definitions().stream().anyMatch(type -> "directory-sync".equals(type.channel())))
            properties.getChannels().computeIfAbsent("directory-sync", ignored -> new MuYunSpringJobProperties.Channel());
        return new JobNodeExecutor(jobs, registry, properties);
    }
}
