package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.StoreBackedBusinessLogPublisher;
import net.ximatai.muyun.spring.iam.user.BusinessLogLoginAuditLogger;
import net.ximatai.muyun.spring.iam.user.LoginAuditLogger;
import net.ximatai.muyun.spring.platform.logging.BusinessLogSchemaBootstrapTask;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogSchemaInitializer;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.RuntimeActionBusinessLogEventListener;
import net.ximatai.muyun.spring.platform.logging.StoreBackedBusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.platform.web.BusinessLogPageAccessRecorder;
import net.ximatai.muyun.spring.web.RequestErrorLogRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;

/** Assembles the neutral business-log publisher with the default PostgreSQL store. */
@Configuration(proxyBeanMethods = false)
public class MuYunSpringBusinessLoggingConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(BusinessLogStore.class)
    PostgresBusinessLogStore postgresBusinessLogStore(DataSource dataSource) {
        return new PostgresBusinessLogStore(dataSource);
    }

    @Bean
    @ConditionalOnBean(PostgresBusinessLogStore.class)
    @ConditionalOnMissingBean(PostgresBusinessLogSchemaInitializer.class)
    PostgresBusinessLogSchemaInitializer postgresBusinessLogSchemaInitializer(DataSource dataSource) {
        return new PostgresBusinessLogSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnBean(PostgresBusinessLogSchemaInitializer.class)
    @ConditionalOnMissingBean(BusinessLogSchemaBootstrapTask.class)
    BusinessLogSchemaBootstrapTask businessLogSchemaBootstrapTask(
            PostgresBusinessLogSchemaInitializer schemaInitializer) {
        return new BusinessLogSchemaBootstrapTask(schemaInitializer);
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
    @ConditionalOnBean(BusinessLogStore.class)
    @ConditionalOnMissingBean(BusinessLogStatisticsReader.class)
    BusinessLogStatisticsReader businessLogStatisticsReader(BusinessLogStore store) {
        return new StoreBackedBusinessLogStatisticsReader(store);
    }
}
