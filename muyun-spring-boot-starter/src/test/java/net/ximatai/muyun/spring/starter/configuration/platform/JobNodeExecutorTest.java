package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.platform.job.JobRegistry;
import net.ximatai.muyun.spring.platform.job.JobService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobNodeExecutorTest {
    @Test
    @SuppressWarnings("unchecked")
    void disabledSchedulesKeepQueueActiveAndCommittedEnqueueWakesSleepingWorkers() throws Exception {
        var jobs = mock(JobService.class);
        var properties = properties();
        properties.getChannels().get("default").setClaimInterval(Duration.ofMinutes(1));
        var firstClaim = new CountDownLatch(1);
        var secondClaim = new CountDownLatch(1);
        var claims = new AtomicInteger();
        when(jobs.runOne(eq("default"), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            if (claims.incrementAndGet() == 1) firstClaim.countDown(); else secondClaim.countDown();
            return false;
        });
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties);
        var listener = ArgumentCaptor.forClass(Consumer.class);
        verify(jobs).onEnqueued(listener.capture());
        assertThat(executor.isAutoStartup()).isFalse();
        try {
            executor.start();
            assertThat(firstClaim.await(2, TimeUnit.SECONDS)).isTrue();
            listener.getValue().accept("default");
            assertThat(secondClaim.await(2, TimeUnit.SECONDS)).isTrue();
            verify(jobs, never()).scan();
            verify(jobs).registerDefaults();
        } finally { executor.stop(); }
        verify(jobs, atLeastOnce()).releaseNode(anyString());
    }

    @Test
    void blockedHandlerDoesNotBlockLeaseRenewalAndStopReleasesTheNode() throws Exception {
        var jobs = mock(JobService.class);
        var handlerEntered = new CountDownLatch(1);
        var renewed = new CountDownLatch(1);
        var block = new CountDownLatch(1);
        when(jobs.runOne(anyString(), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            handlerEntered.countDown();
            block.await();
            return false;
        });
        doAnswer(invocation -> { renewed.countDown(); return null; }).when(jobs).renewNode(anyString());
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties());
        try {
            executor.start();
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
        } finally { block.countDown(); executor.stop(); }
        assertThat(executor.isRunning()).isFalse();
        verify(jobs, atLeastOnce()).releaseNode(anyString());
    }

    @Test
    void oneBlockedLeaseDoesNotDelayAnotherWorkersRenewal() throws Exception {
        var jobs = mock(JobService.class);
        var properties = properties();
        properties.getChannels().get("default").setThreads(2);
        var blockedOwner = new AtomicReference<String>();
        var secondRenewed = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        doAnswer(invocation -> {
            String owner = invocation.getArgument(0);
            if (blockedOwner.compareAndSet(null, owner)) {
                try { unblock.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else if (!owner.equals(blockedOwner.get())) secondRenewed.countDown();
            return null;
        }).when(jobs).renewNode(anyString());
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties);
        try {
            executor.start();
            assertThat(secondRenewed.await(3, TimeUnit.SECONDS)).isTrue();
        } finally { unblock.countDown(); executor.stop(); }
    }

    @Test
    void stopFencesTheOwnerBeforeInterruptingItsResidualHandler() throws Exception {
        var jobs = mock(JobService.class);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var released = new AtomicBoolean();
        var fencedBeforeInterrupt = new AtomicBoolean();
        when(jobs.runOne(anyString(), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) { fencedBeforeInterrupt.set(released.get()); interrupted.countDown(); }
            return false;
        });
        doAnswer(invocation -> { released.set(true); return null; }).when(jobs).releaseNode(anyString());
        var executor = new JobNodeExecutor(jobs, new JobRegistry(List.of()), properties());
        try {
            executor.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { executor.stop(); }
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(fencedBeforeInterrupt).isTrue();
    }

    private MuYunSpringJobProperties properties() {
        var properties = new MuYunSpringJobProperties();
        properties.setScheduledEnabled(false);
        properties.setShutdownGrace(Duration.ZERO);
        properties.getChannels().get("default").setThreads(1);
        return properties;
    }
}
