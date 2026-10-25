package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.notification.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Duration;
import java.util.List;
import static org.mockito.Mockito.*;

@TestConfiguration(proxyBeanMethods = false)
@EnableMuYunRepositories(basePackageClasses = JobRunDao.class)
class BusinessContentJobTestConfiguration {
    volatile BusinessContentGenerationService engine;
    @Bean @SuppressWarnings("unchecked")
    JobService contentJobService(JobPlanDao plans, JobRunDao runs, IDatabaseOperations<?> db,
                                 PlatformTransactionManager transactions, BusinessContentGenerationStore store) {
        var provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenAnswer(call -> engine);
        var registry = new JobRegistry(List.of(BusinessContentGenerationJob.handler(JobScope.TENANT, provider, store),
                BusinessContentGenerationJob.handler(JobScope.PLATFORM, provider, store),
                new BusinessContentGenerationRetentionJob(store, java.time.Clock.systemUTC())));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        return new JobService(plans, runs, db, transactions, registry, beans.getBeanProvider(JobFailureRecipientResolver.class),
                beans.getBeanProvider(BusinessNotificationDelivery.class), beans.getBeanProvider(JobManagementAccess.class), Duration.ofMinutes(1), 90);
    }
    @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegerJdbiConfigurer() {
        return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
            protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                return (position, statement, context) -> statement.setLong(position, value.longValueExact());
            }
        });
    }
}
