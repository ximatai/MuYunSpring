package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogRetentionPolicyStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionService;
import net.ximatai.muyun.spring.platform.logging.BusinessLogRetentionExecutionLimits;
import net.ximatai.muyun.spring.platform.logging.PostgresBusinessLogStore;
import net.ximatai.muyun.spring.platform.logging.RuntimeActionBusinessLogEventListener;
import net.ximatai.muyun.spring.iam.user.LoginAuditLogger;
import net.ximatai.muyun.spring.web.RequestErrorLogRecorder;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.platform.web.BusinessLogPageAccessRecorder;
import org.junit.jupiter.api.Test;
import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MuYunSpringBusinessLoggingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
            .withBean(Jdbi.class, () -> mock(Jdbi.class));

    @Test
    void shouldExposeNeutralPublisherAndDefaultPostgresAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BusinessLogStore.class);
            assertThat(context).hasSingleBean(PostgresBusinessLogStore.class);
            assertThat(context).hasSingleBean(BusinessLogPublisher.class);
            assertThat(context).hasSingleBean(LoginAuditLogger.class);
            assertThat(context).hasSingleBean(RuntimeActionBusinessLogEventListener.class);
            assertThat(context).hasSingleBean(RequestErrorLogRecorder.class);
            assertThat(context).hasSingleBean(BusinessLogPageAccessRecorder.class);
            assertThat(context).hasSingleBean(BusinessLogStatisticsReader.class);
            assertThat(context).hasSingleBean(BusinessLogGovernanceService.class);
            assertThat(context).hasSingleBean(BusinessLogRetentionService.class);
            assertThat(context).hasSingleBean(BusinessLogRetentionPolicyStore.class);
            assertThat(context).hasSingleBean(BusinessLogRetentionExecutionLimits.class);
            assertThat(context).hasSingleBean(BusinessLogRetentionScheduler.class);
        });
    }

    @Test
    void shouldRemainOptionalWhenNoJdbiOrCustomStoreIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BusinessLogStore.class);
                    assertThat(context).doesNotHaveBean(BusinessLogPublisher.class);
                    assertThat(context).doesNotHaveBean(LoginAuditLogger.class);
                    assertThat(context).doesNotHaveBean(RuntimeActionBusinessLogEventListener.class);
                    assertThat(context).doesNotHaveBean(RequestErrorLogRecorder.class);
                    assertThat(context).doesNotHaveBean(BusinessLogStatisticsReader.class);
                    assertThat(context).doesNotHaveBean(BusinessLogGovernanceService.class);
                    assertThat(context).doesNotHaveBean(BusinessLogRetentionService.class);
                    assertThat(context).doesNotHaveBean(BusinessLogRetentionExecutionLimits.class);
                    assertThat(context).doesNotHaveBean(BusinessLogRetentionScheduler.class);
                    assertThat(context).hasSingleBean(BusinessLogPageAccessRecorder.class);
                });
    }

    @Test
    void shouldRetainTheNeutralLoggingPathWhenAnApplicationSuppliesItsOwnStore() {
        new ApplicationContextRunner()
                .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
                .withBean(BusinessLogStore.class, () -> mock(BusinessLogStore.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(BusinessLogPageAccessRecorder.class);
                    assertThat(context).doesNotHaveBean(PostgresBusinessLogStore.class);
                    assertThat(context).hasSingleBean(BusinessLogPublisher.class);
                    assertThat(context).hasSingleBean(LoginAuditLogger.class);
                    assertThat(context).hasSingleBean(RuntimeActionBusinessLogEventListener.class);
                    assertThat(context).hasSingleBean(RequestErrorLogRecorder.class);
                    assertThat(context).hasSingleBean(BusinessLogStatisticsReader.class);
                    assertThat(context).hasSingleBean(BusinessLogGovernanceService.class);
                    assertThat(context).doesNotHaveBean(BusinessLogRetentionService.class);
                });
    }

    @Test
    void shouldAllowOperationsToDisableOnlyTheScheduledExecutor() {
        contextRunner
                .withPropertyValues("muyun.platform.business-log.retention.scheduled-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(BusinessLogRetentionService.class);
                    assertThat(context).doesNotHaveBean(BusinessLogRetentionScheduler.class);
                });
    }

    @Test
    void shouldSupportAReplacementStoreWithAnExplicitRetentionCapability() {
        new ApplicationContextRunner()
                .withUserConfiguration(MuYunSpringBusinessLoggingConfiguration.class)
                .withBean(BusinessLogStore.class, () -> mock(BusinessLogStore.class))
                .withBean(BusinessLogRetentionStore.class, () -> mock(BusinessLogRetentionStore.class))
                .withBean(BusinessLogRetentionPolicyStore.class, () -> mock(BusinessLogRetentionPolicyStore.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PostgresBusinessLogStore.class);
                    assertThat(context).hasSingleBean(BusinessLogRetentionService.class);
                    assertThat(context).hasSingleBean(BusinessLogRetentionScheduler.class);
                });
    }

    @Test
    void shouldFailFastWhenRetentionExecutionLimitsAreUnsafe() {
        contextRunner
                .withPropertyValues(
                        "muyun.platform.business-log.retention.batch-size=10001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailFastWhenRetentionScheduleCouldFormATightLoop() {
        contextRunner
                .withPropertyValues(
                        "muyun.platform.business-log.retention.scan-delay=0s")
                .run(context -> assertThat(context).hasFailed());
    }
}
