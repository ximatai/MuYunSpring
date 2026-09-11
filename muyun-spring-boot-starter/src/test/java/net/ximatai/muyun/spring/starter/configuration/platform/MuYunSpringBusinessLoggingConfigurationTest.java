package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.BusinessLogSchemaBootstrapTask;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogSchemaInitializer;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.RuntimeActionBusinessLogEventListener;
import net.ximatai.muyun.spring.iam.user.LoginAuditLogger;
import net.ximatai.muyun.spring.web.RequestErrorLogRecorder;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.platform.web.BusinessLogPageAccessRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MuYunSpringBusinessLoggingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    void shouldExposeNeutralPublisherAndDefaultPostgresAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BusinessLogStore.class);
            assertThat(context).hasSingleBean(PostgresBusinessLogStore.class);
            assertThat(context).hasSingleBean(PostgresBusinessLogSchemaInitializer.class);
            assertThat(context).hasSingleBean(BusinessLogSchemaBootstrapTask.class);
            assertThat(context).hasSingleBean(BusinessLogPublisher.class);
            assertThat(context).hasSingleBean(LoginAuditLogger.class);
            assertThat(context).hasSingleBean(RuntimeActionBusinessLogEventListener.class);
            assertThat(context).hasSingleBean(RequestErrorLogRecorder.class);
            assertThat(context).hasSingleBean(BusinessLogPageAccessRecorder.class);
            assertThat(context).hasSingleBean(BusinessLogStatisticsReader.class);
        });
    }

    @Test
    void shouldExposeNoopPageRecorderWhenBusinessLogStorageIsNotConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(BusinessLogPageAccessRecorder.class));
    }
}
