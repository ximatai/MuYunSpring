package net.ximatai.muyun.spring.platform.content;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessContentGenerationSchedulingTest {
    @Test
    void shouldKeepDefaultSerialExecutionWhileAProviderCallIsPending() throws Exception {
        verifyBoundedConcurrency(1);
    }

    @Test
    void shouldUseTheConfiguredBoundedWorkersWhileProviderCallsArePending() throws Exception {
        verifyBoundedConcurrency(2);
    }

    private void verifyBoundedConcurrency(int workers) throws Exception {
        var generations = mock(BusinessContentGenerationService.class);
        var entered = new CountDownLatch(workers);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        when(generations.runOne()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                release.await();
                return true;
            } finally {
                active.decrementAndGet();
            }
        });
        var scheduler = new BusinessContentGenerationScheduling(generations, 0, 1000, workers);
        try {
            scheduler.start();
            scheduler.start(); // Repeated lifecycle start must not install another set of workers.
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(workers);
            assertThat(maximum.get()).isEqualTo(workers);
        } finally {
            scheduler.stop();
            release.countDown();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void shouldRejectUnboundedOrInvalidWorkerCounts() {
        for (int workers : new int[] {0, -1, 9, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new BusinessContentGenerationScheduling(
                    mock(BusinessContentGenerationService.class), 0, 1000, workers))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Content generation workers must be between 1 and 8");
        }
    }
}
