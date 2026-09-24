package net.ximatai.muyun.spring.dynamic.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

class DynamicRuntimePublicationTest {
    @Test
    void existingExecutionsFinishBeforePublicationAndNewExecutionsWaitForTheCompletePair() throws Exception {
        var publication = new DynamicRuntimePublication();
        var existing = publication.execution();
        var installing = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                // Independent readers must not serialize ordinary requests.
                assertThat(executor.submit(() -> {
                    try (var ignored = publication.execution()) { return "parallel"; }
                }).get(5, TimeUnit.SECONDS)).isEqualTo("parallel");
                var writer = executor.submit(() -> {
                    try (var ignored = publication.publication()) {
                        installing.countDown();
                        assertThat(complete.await(5, TimeUnit.SECONDS)).isTrue();
                        throw new IllegalArgumentException("page installation failed");
                    }
                });
                assertThat(installing.await(100, TimeUnit.MILLISECONDS)).isFalse();
                existing.close();
                assertThat(installing.await(5, TimeUnit.SECONDS)).isTrue();
                var incoming = executor.submit(() -> {
                    try (var ignored = publication.execution()) { return "withdrawn"; }
                });
                assertThatThrownBy(() -> incoming.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                complete.countDown();
                assertThatThrownBy(() -> writer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalArgumentException.class);
                assertThat(incoming.get(5, TimeUnit.SECONDS)).isEqualTo("withdrawn");
            } finally {
                existing.close();
                complete.countDown();
            }
        }
    }

    @Test
    void publishingFromAnExecutionFailsImmediatelyInsteadOfDeadlocking() {
        var publication = new DynamicRuntimePublication();
        try (var ignored = publication.execution()) {
            assertThatThrownBy(publication::publication).isInstanceOf(IllegalStateException.class);
        }
        try (var writer = publication.publication(); var reader = publication.execution()) {
            assertThatCode(() -> publication.publication().close()).doesNotThrowAnyException();
        }
    }
}
