package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.StoreBackedBusinessLogPublisher;
import net.ximatai.muyun.spring.iam.user.BusinessLogLoginAuditLogger;
import net.ximatai.muyun.spring.iam.user.LoginAuditLogger;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.RuntimeActionBusinessLogEventListener;
import net.ximatai.muyun.spring.platform.logging.StoreBackedBusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.platform.web.BusinessLogPageAccessRecorder;
import net.ximatai.muyun.spring.platform.web.StaticCrudActionLogRecorder;
import net.ximatai.muyun.spring.web.RequestErrorLogRecorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.jdbi.v3.core.Jdbi;


/** Assembles the neutral business-log publisher with the platform-owned Jdbi connection runtime. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "net.ximatai.muyun.database.spring.boot.MuYunDatabaseAutoConfiguration"
}, beforeName = "net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration")
public class MuYunSpringBusinessLoggingConfiguration {
    @Bean
    @ConditionalOnBean(Jdbi.class)
    @ConditionalOnMissingBean(BusinessLogStore.class)
    PostgresBusinessLogStore postgresBusinessLogStore(Jdbi jdbi) {
        return new PostgresBusinessLogStore(jdbi);
    }

    @Bean
    @ConditionalOnBean(BusinessLogStore.class)
    @ConditionalOnMissingBean(BusinessLogPublisher.class)
    BusinessLogPublisher businessLogPublisher(BusinessLogStore store) {
        return new StoreBackedBusinessLogPublisher(store);
    }

    @Bean
    @ConditionalOnBean(BusinessLogPublisher.class)
    @ConditionalOnMissingBean(LoginAuditLogger.class)
    LoginAuditLogger loginAuditLogger(BusinessLogPublisher publisher) {
        return new BusinessLogLoginAuditLogger(publisher);
    }

    @Bean
    @ConditionalOnBean(BusinessLogPublisher.class)
    @ConditionalOnMissingBean(RuntimeActionBusinessLogEventListener.class)
    RuntimeActionBusinessLogEventListener runtimeActionBusinessLogEventListener(BusinessLogPublisher publisher) {
        return new RuntimeActionBusinessLogEventListener(publisher);
    }

    @Bean
    @ConditionalOnBean(BusinessLogPublisher.class)
    @ConditionalOnMissingBean(RequestErrorLogRecorder.class)
    RequestErrorLogRecorder requestErrorLogRecorder(BusinessLogPublisher publisher) {
        return new RequestErrorLogRecorder(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(BusinessLogPageAccessRecorder.class)
    BusinessLogPageAccessRecorder businessLogPageAccessRecorder(ObjectProvider<BusinessLogPublisher> publisher) {
        return new BusinessLogPageAccessRecorder(publisher.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(StaticCrudActionLogRecorder.class)
    StaticCrudActionLogRecorder staticCrudActionLogRecorder(ObjectProvider<BusinessLogPublisher> publisher) {
        return new StaticCrudActionLogRecorder(publisher.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean(BusinessLogStore.class)
    @ConditionalOnMissingBean(BusinessLogStatisticsReader.class)
    BusinessLogStatisticsReader businessLogStatisticsReader(BusinessLogStore store) {
        return new StoreBackedBusinessLogStatisticsReader(store);
    }

    @Bean
    @ConditionalOnBean({BusinessLogStore.class, BusinessLogStatisticsReader.class})
    @ConditionalOnMissingBean(BusinessLogGovernanceService.class)
    BusinessLogGovernanceService businessLogGovernanceService(BusinessLogStore store,
                                                              BusinessLogStatisticsReader statisticsReader) {
        return new BusinessLogGovernanceService(store, statisticsReader);
    }

}
