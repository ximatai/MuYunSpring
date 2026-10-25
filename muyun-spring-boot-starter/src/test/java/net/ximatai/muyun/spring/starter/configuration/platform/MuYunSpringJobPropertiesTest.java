package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.job.JobRegistry;
import net.ximatai.muyun.spring.platform.job.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MuYunSpringJobPropertiesTest {
    @Test
    void invalidBoundConfigurationFailsContextStartup() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(MuYunSpringJobConfiguration.class)
                .withBean(JobRegistry.class, () -> new JobRegistry(List.of()))
                .withBean(JobService.class, () -> mock(JobService.class));
        runner.withPropertyValues("muyun.platform.job.scheduled-enabled=false",
                        "muyun.platform.job.channels.default.threads=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("muyun.platform.job.scheduled-enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JobNodeExecutor.class));
    }

    @Test
    void defaultsEnableExistingMaintenanceAndBoundTheQueue() {
        var properties = new MuYunSpringJobProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isScheduledEnabled()).isTrue();
        assertThat(properties.getRetentionDays()).isEqualTo(90);
        assertThat(properties.getChannels().get("default").getClaimInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void invalidDeploymentLimitsFailEvenWhenAutomaticSchedulesAreDisabled() {
        var properties = new MuYunSpringJobProperties();
        properties.setScheduledEnabled(false);
        properties.setLeaseDuration(Duration.ZERO);
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setScanInterval(Duration.ZERO);
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
        properties.setScanInterval(Duration.ofMinutes(1));
        properties.setRetentionDays(0);
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
    }

    @Test
    void validatesEachIndependentChannel() {
        var properties = new MuYunSpringJobProperties();
        var channel = new MuYunSpringJobProperties.Channel();
        properties.setChannels(Map.of("directory", channel));
        channel.setThreads(0);
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
        channel.setThreads(2);
        channel.setClaimInterval(Duration.ofMinutes(2));
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
        channel.setClaimInterval(Duration.ofSeconds(1));
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setChannels(Map.of("invalid.name", channel));
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
        channel.setThreads(64);
        properties.setChannels(Map.of("a", channel, "b", channel, "c", channel, "d", channel, "e", channel));
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
    }
}
