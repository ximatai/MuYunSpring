package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.job.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The old private scheduler contract now runs against the platform channel executor. */
class BusinessContentGenerationSchedulingTest {
    @Test void shouldKeepDefaultSerialExecutionWhileAProviderCallIsPending() throws Exception { concurrency(1); }
    @Test void shouldUseTheConfiguredBoundedWorkersWhileProviderCallsArePending() throws Exception { concurrency(2); }
    private void concurrency(int workers) throws Exception {
        var jobs = mock(JobService.class); var entered = new CountDownLatch(workers); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        when(jobs.runOne(eq("content-generation"), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max); entered.countDown();
            try { release.await(); return true; } finally { active.decrementAndGet(); }
        });
        var content = new MuYunSpringContentGenerationProperties(); content.setGenerationWorkers(workers); content.setGenerationInitialDelayMs(0);
        var properties = properties(content, new MockEnvironment());
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties);
        try {
            executor.start(); executor.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(workers); assertThat(maximum.get()).isEqualTo(workers);
        } finally { executor.stop(); release.countDown(); }
        assertThat(executor.isRunning()).isFalse();
    }
    @Test void shouldRejectUnboundedOrInvalidWorkerCountsAndIntervals() {
        for (int workers : new int[]{0, -1, 9, Integer.MAX_VALUE}) {
            var content = new MuYunSpringContentGenerationProperties(); content.setGenerationWorkers(workers);
            assertThatThrownBy(() -> properties(content, new MockEnvironment())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Content generation workers must be between 1 and 8");
        }
        for (long delay : new long[]{-1, 0, 99, 60001}) {
            var content = new MuYunSpringContentGenerationProperties(); content.setGenerationDelayMs(delay);
            assertThatThrownBy(() -> properties(content, new MockEnvironment())).isInstanceOf(IllegalArgumentException.class);
        }
        for (long delay : new long[]{-1, 300001}) {
            var content = new MuYunSpringContentGenerationProperties(); content.setGenerationInitialDelayMs(delay);
            assertThatThrownBy(() -> properties(content, new MockEnvironment())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void shouldMapLegacyDefaultsAndRejectConflictingExplicitConfiguration() {
        var content = new MuYunSpringContentGenerationProperties(); var properties = properties(content, new MockEnvironment());
        var channel = properties.getChannels().get("content-generation");
        assertThat(channel.getThreads()).isEqualTo(1); assertThat(channel.getClaimInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(channel.getInitialDelay()).isEqualTo(Duration.ofMinutes(1)); assertThat(channel.isDelayAfterExecution()).isTrue();
        content.setGenerationWorkers(2);
        assertThatThrownBy(() -> content.configure(properties, new MockEnvironment()
                .withProperty("muyun.content.generation-workers", "2")
                .withProperty("muyun.platform.job.channels.content-generation.threads", "1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting");
    }
    @Test void committedWakeupCannotBypassInitialDelayOrTheDelayAfterExecution() throws Exception {
        var content = new MuYunSpringContentGenerationProperties(); content.setGenerationInitialDelayMs(300); content.setGenerationDelayMs(300);
        var properties = properties(content, new MockEnvironment()); var jobs = mock(JobService.class);
        var calls = new CopyOnWriteArrayList<Long>(); var second = new CountDownLatch(2);
        when(jobs.runOne(eq("content-generation"), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            calls.add(System.nanoTime()); second.countDown(); return true;
        });
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties);
        long start = System.nanoTime();
        try {
            executor.start();
            var wake = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(jobs).onEnqueued(wake.capture());
            for (int index = 0; index < 10; index++) wake.getValue().accept("content-generation");
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get(0) - start).isGreaterThanOrEqualTo(Duration.ofMillis(290).toNanos());
            assertThat(calls.get(1) - calls.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(290).toNanos());
        } finally { executor.stop(); }
    }
    private MuYunSpringJobProperties properties(MuYunSpringContentGenerationProperties content, MockEnvironment environment) {
        var jobs = new MuYunSpringJobProperties(); jobs.setScheduledEnabled(false); jobs.setShutdownGrace(Duration.ZERO);
        content.configure(jobs, environment); jobs.validate(); return jobs;
    }
}
